package main

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"io/ioutil"
	"log"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/bufbuild/protovalidate-go"
	"github.com/jaremko/a7p_transfer_example/profedit"
	"google.golang.org/protobuf/proto"
)

var v *protovalidate.Validator

type ImageInfo struct {
	FileName  string `json:"fileName"`
	Base64Str string `json:"base64Str"`
}

func ValidatorInit() {
	var err error
	v, err = protovalidate.New()
	if err != nil {
		log.Fatal("[Go] failed to initialize validator:", err)
	}
}

// Helper function to check if the flag file exists
func flagFileExists() bool {
	_, err := os.Stat("/tmp/refresh_file_list")
	return !os.IsNotExist(err)
}

func flagrFileExists() bool {
	_, err := os.Stat("/tmp/refresh_reticle_file_list")
	return !os.IsNotExist(err)
}

func flashFlagFileExists() bool {
	_, err := os.Stat("/tmp/flash.txt")
	return !os.IsNotExist(err)
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
	if (!filenameRegex.MatchString(filename)) && (filename != "profiletabl") {
		return "", errors.New("invalid filename: only alphanumeric characters, underscore, dot, space, and hyphen allowed. filename must start with an alphanumeric character and end with '.a7p'")
	}
	return filepath.Clean(filename), nil
}

var filenameRegexR = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.\- ]*\.tar$`)

func sanitizerFilename(filename string) (string, error) {
	if !filenameRegexR.MatchString(filename) {
		return "", errors.New("invalid filename: only alphanumeric characters, underscore, dot, space, and hyphen allowed. filename must start with an alphanumeric character and end with '.tar'")
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
	// if flagFileExists() {
	// 	respondWithError(w, http.StatusServiceUnavailable, "Server is busy")
	// 	return
	// }
	w.Header().Set("Cache-Control", "no-store")
	safePath := path.Clean(r.URL.Path)
	filePath := path.Join("/www", safePath)
	http.ServeFile(w, r, filePath)
}

func handleFileList(dir string, w http.ResponseWriter, r *http.Request) {
	// if flagFileExists() {
	// 	respondWithError(w, http.StatusServiceUnavailable, "Server is busy")
	// 	return
	// }

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
		// Creating or touching the /tmp/refresh_file_list flag file
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
	// if flagFileExists() {
	// 	respondWithError(w, http.StatusServiceUnavailable, "Server is busy")
	// 	return
	// }

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
	// if flagFileExists() {
	// 	respondWithError(w, http.StatusServiceUnavailable, "Server is busy")
	// 	return
	// }

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
	// if flagFileExists() {
	// 	respondWithError(w, http.StatusServiceUnavailable, "Server is busy")
	// 	return
	// }

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

func corsMiddleware(handler http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {

		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "POST, GET, OPTIONS, PUT, DELETE")
		w.Header().Set("Access-Control-Allow-Headers", "Accept, Content-Type, Content-Length, Accept-Encoding, Authorization")

		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		handler(w, r)
	}
}

func getReticlesList(dir string, w http.ResponseWriter, r *http.Request) {

	files, err := os.ReadDir(dir)
	if err != nil {
		log.Printf("Error reading reticles directory: %v", err)
		respondWithError(w, http.StatusInternalServerError, "Server error")
		return
	}

	var fileNames []string
	for _, file := range files {
		if filepath.Ext(file.Name()) == ".zip" {
			fileNames = append(fileNames, file.Name())
		}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(fileNames)
}

func handleGetReticle(dir string, w http.ResponseWriter, r *http.Request) {
	fileName := r.URL.Query().Get("name")
	filePath := filepath.Join(dir, fileName)

	if _, err := os.Stat(filePath); os.IsNotExist(err) {
		respondWithError(w, http.StatusNotFound, "Reticles not found")
		return
	}

	http.ServeFile(w, r, filePath)
}

func handlePutReticle(dir string, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Invalid request method", http.StatusMethodNotAllowed)
		return
	}

	file, header, err := r.FormFile("file")

	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	defer file.Close()
	filePath := filepath.Join(dir, header.Filename)
	out, err := os.Create(filePath)

	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer out.Close()
	_, err = io.Copy(out, file)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	w.Write([]byte("File uploaded successfully"))

}

func handleDeleteReticles(dir string, w http.ResponseWriter, r *http.Request) {
	fileName := r.URL.Query().Get("name")
	filePath := filepath.Join(dir, fileName)

	if _, err := os.Stat(filePath); os.IsNotExist(err) {
		respondWithError(w, http.StatusNotFound, "Reticles not found")
		return
	}

	if err := os.Remove(filePath); err != nil {
		log.Printf("Error deleting reticles: %v", err)
		respondWithError(w, http.StatusInternalServerError, "Server error")
		return
	}

	w.Write([]byte("OK"))
}

func main() {
	ValidatorInit()

	dirPtr := flag.String("dir", "/usr/mmcdata/mmcblk2p8/profiles/", "directory to serve")
	dirRtr := flag.String("rdir", "/usr/mmcdata/mmcblk2p8/reticles/", "directёry to serve")
	flag.Parse()

	//dirPtr := "/usr/mmcdata/mmcblk2p8/profiles/"
	//dirRtr := "/usr/mmcdata/mmcblk2p8/reticles_tar/"

	log.Printf("Starting localhost server at http://localhost:8080")

	http.HandleFunc("/", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		// if flashFlagFileExists() {
		// 	respondWithError(w, http.StatusServiceUnavailable, "Server is busy")
		// 	os.Exit(0)
		// }
		handleStaticFiles(w, r)
	}))

	http.HandleFunc("/filelist", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		// if flashFlagFileExists() {
		// 	respondWithError(w, http.StatusServiceUnavailable, "Server is busy")
		// 	os.Exit(0)
		// }
		handleFileList(*dirPtr, w, r)
	}))

	http.HandleFunc("/files", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {

		// if flashFlagFileExists() {
		// 	respondWithError(w, http.StatusServiceUnavailable, "Server is busy")
		// 	os.Exit(0)
		// }

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
	}))

	http.HandleFunc("/getReticlesList", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		getReticlesList(*dirRtr, w, r)
	}))

	http.HandleFunc("/getReticle", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		handleGetReticle(*dirRtr, w, r)
	}))

	http.HandleFunc("/uploadReticleFile", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		handlePutReticle(*dirRtr, w, r)
	}))

	http.HandleFunc("/deleteReticle", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		handleDeleteReticles(*dirRtr, w, r)
	}))

	// Start the goroutine that checks for /tmp/foobar.txt
	shutdownCh := make(chan struct{})

	go func() {
		ticker := time.NewTicker(10 * time.Millisecond)
		defer ticker.Stop()

		for {
			select {
			case <-ticker.C:
				if _, err := os.Stat("/tmp/flash.txt"); err == nil {
					close(shutdownCh)
					return
				}
			case <-shutdownCh:
				return
			}
		}
	}()

	server := &http.Server{Addr: ":8080"}

	go func() {
		if err := server.ListenAndServe(); err != http.ErrServerClosed {
			log.Fatalf("ListenAndServe(): %v", err)
		}
	}()

	<-shutdownCh

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := server.Shutdown(ctx); err != nil {
		log.Fatalf("Server Shutdown Failed:%v", err)
	}
	log.Println("Server exited properly")
}
