package main

import (
	"crypto/md5"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io/ioutil"
	"log"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"encoding/json"
	"strings"

	"google.golang.org/protobuf/proto"
	"github.com/bufbuild/protovalidate-go"
	"github.com/jaremko/a7p_transfer_example/profedit"
)

var v *protovalidate.Validator

func ValidatorInit() {
	var err error
	v, err = protovalidate.New()
	if err != nil {
		log.Fatal("[Go] failed to initialize validator:", err)
	}
}

func validateProtoPayload(w http.ResponseWriter, pb proto.Message) error {
	if err := v.Validate(pb); err != nil {
		log.Println("[Go] validation failed:", err)
		respondWithError(w, http.StatusBadRequest, fmt.Sprintf("Validation failed: %v", err))
		return err
	}
	log.Println("[Go] validation succeeded")
	return nil
}

func checksum(data []byte) string {
	h := md5.New()
	h.Write(data)
	return hex.EncodeToString(h.Sum(nil))
}

func validateAndStripChecksum(data []byte) ([]byte, error) {
	if len(data) <= 32 {
		return nil, fmt.Errorf("data too short for a checksum")
	}
	prefix, content := data[:32], data[32:]
	calculatedChecksum := checksum(content)
	if string(prefix) != calculatedChecksum {
		return nil, fmt.Errorf("checksum mismatch: expected %s, got %s", calculatedChecksum, string(prefix))
	}
	return content, nil
}

var filenameRegex = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.\- ]*\.a7p$`)

func sanitizeFilename(filename string) (string, error) {
	if !filenameRegex.MatchString(filename) {
		return "", errors.New("invalid filename: only alphanumeric characters, underscore, dot, space, and hyphen allowed. filename must start with an alphanumeric character and end with '.a7p'")
	}
	return filepath.Clean(filename), nil
}

func respondWithError(w http.ResponseWriter, code int, message string) {
	resp := map[string]string{"error": message}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(resp)
}

func handleStaticFiles(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	safePath := path.Clean(r.URL.Path)
	filePath := path.Join("/www", safePath)
	http.ServeFile(w, r, filePath)
}

func handleFileList(dir string, w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")

	switch r.Method {
	case http.MethodGet:
		files, err := ioutil.ReadDir(dir)
		if err != nil {
			log.Printf("Error reading directory: %v", err)
			respondWithError(w, http.StatusInternalServerError, "Server error")
			return
		}

		var fileNames []string
		for _, file := range files {
			if strings.HasSuffix(file.Name(), ".a7p") {
				fileNames = append(fileNames, file.Name())
			}
		}

		json.NewEncoder(w).Encode(fileNames)

	case http.MethodPost:
		_, err := os.OpenFile("/tmp/refresh_file_list", os.O_RDONLY|os.O_CREATE, 0644)
		if err != nil {
			log.Printf("Error creating/touching flag file: %v", err)
			respondWithError(w, http.StatusInternalServerError, "Server error")
			return
		}

		w.Write([]byte("Flag file created/refreshed"))

	default:
		respondWithError(w, http.StatusMethodNotAllowed, "Invalid request method")
	}
}

func handleGetFile(dir string, w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")

	filename, err := sanitizeFilename(r.URL.Query().Get("filename"))
	if err != nil {
		log.Printf("Invalid filename: %v", err)
		respondWithError(w, http.StatusBadRequest, "Invalid filename")
		return
	}

	data, err := ioutil.ReadFile(filepath.Join(dir, filename))
	if err != nil {
		log.Printf("Error reading file: %v", err)
		respondWithError(w, http.StatusNotFound, "File not found")
		return
	}

	content, err := validateAndStripChecksum(data)
	if err != nil {
		log.Printf("Error validating or stripping checksum: %v", err)
		respondWithError(w, http.StatusInternalServerError, "Server error")
		return
	}

	pb := &profedit.Payload{}
	if err := proto.Unmarshal(content, pb); err != nil {
		log.Printf("Error unmarshalling protobuf: %v", err)
		respondWithError(w, http.StatusInternalServerError, "Server error")
		return
	}

	if err := validateProtoPayload(w, pb); err != nil {
		return
	}

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Write(content)
}

func handlePutFile(dir string, w http.ResponseWriter, r *http.Request) {
	filename, err := sanitizeFilename(r.URL.Query().Get("filename"))
	if err != nil {
		log.Printf("Invalid filename: %v", err)
		respondWithError(w, http.StatusBadRequest, "Invalid filename")
		return
	}

	content, err := ioutil.ReadAll(r.Body)
	if err != nil {
		log.Printf("Error reading request body: %v", err)
		respondWithError(w, http.StatusBadRequest, "Bad request")
		return
	}

	pb := &profedit.Payload{}
	if err := proto.Unmarshal(content, pb); err != nil {
		log.Printf("Error unmarshalling protobuf: %v", err)
		respondWithError(w, http.StatusInternalServerError, "Server error")
		return
	}

	if err := validateProtoPayload(w, pb); err != nil {
		return
	}

	checksum := checksum(content)
	data := append([]byte(checksum), content...)

	if err := ioutil.WriteFile(filepath.Join(dir, filename), data, 0644); err != nil {
		log.Printf("Error writing file: %v", err)
		respondWithError(w, http.StatusInternalServerError, "Server error")
		return
	}

	w.Write([]byte("OK"))
}

func handleDeleteFile(dir string, w http.ResponseWriter, r *http.Request) {
	filename, err := sanitizeFilename(r.URL.Query().Get("filename"))
	if err != nil {
		log.Printf("Invalid filename: %v", err)
		respondWithError(w, http.StatusBadRequest, "Invalid filename")
		return
	}

	if err := os.Remove(filepath.Join(dir, filename)); err != nil {
		log.Printf("Error deleting file: %v", err)
		respondWithError(w, http.StatusNotFound, "File not found")
		return
	}

	w.Write([]byte("OK"))
}

func main() {
	ValidatorInit()

	dirPtr := flag.String("dir", ".", "directory to serve")

	flag.Parse()

	log.Printf("Starting localhost server at http://localhost:8080")

	http.HandleFunc("/", handleStaticFiles)
	http.HandleFunc("/filelist", func(w http.ResponseWriter, r *http.Request) {
		handleFileList(*dirPtr, w, r)
	})
	http.HandleFunc("/files", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			handleGetFile(*dirPtr, w, r)
		case http.MethodPut:
			handlePutFile(*dirPtr, w, r)
		case http.MethodDelete:
			handleDeleteFile(*dirPtr, w, r)
		default:
			respondWithError(w, http.StatusMethodNotAllowed, "Invalid request method")
		}
	})

	log.Fatal(http.ListenAndServe(":8080", nil))
}
