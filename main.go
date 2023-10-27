package main

import (
	"context"
	"crypto/md5"
	"encoding/base64"
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
	if !filenameRegex.MatchString(filename) {
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

	var reticleNames []string
	for _, file := range files {
		if file.IsDir() {
			reticleNames = append(reticleNames, file.Name())
		}
	}

	json.NewEncoder(w).Encode(reticleNames)
}

func handleGetReticle(dir string, w http.ResponseWriter, r *http.Request) {
	folderName := r.URL.Query().Get("folderName")
	folderPath := filepath.Join(dir, folderName)

	if _, err := os.Stat(folderPath); os.IsNotExist(err) {
		respondWithError(w, http.StatusNotFound, "Reticle folder not found")
		return
	}

	files, err := os.ReadDir(folderPath)
	if err != nil {
		log.Printf("Error reading reticle folder: %v", err)
		respondWithError(w, http.StatusInternalServerError, "Server error")
		return
	}

	var images []ImageInfo
	for _, file := range files {
		if !file.IsDir() {
			imagePath := filepath.Join(folderPath, file.Name())
			imageBytes, err := os.ReadFile(imagePath)
			if err != nil {
				log.Printf("Error reading image: %v", err)
				respondWithError(w, http.StatusInternalServerError, "Server error")
				return
			}

			imageInfo := ImageInfo{
				FileName:  file.Name(),
				Base64Str: base64.StdEncoding.EncodeToString(imageBytes),
			}
			images = append(images, imageInfo)
		}
	}

	json.NewEncoder(w).Encode(images)
}

func handlePutReticle(dir string, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Invalid request method", http.StatusMethodNotAllowed)
		return
	}

	data, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read request body", http.StatusBadRequest)
		log.Printf("Failed to read request body: %v\n", err)
		return
	}

	var req []struct {
		FileName  string `json:"fileName"`
		Base64Str string `json:"base64Str"`
	}

	if err := json.Unmarshal(data, &req); err != nil {
		http.Error(w, "Failed to parse JSON data", http.StatusBadRequest)
		log.Printf("Failed to parse JSON data: %v\n", err)
		return
	}

	folderName := r.URL.Query().Get("folderName")
	if folderName == "" {
		http.Error(w, "Missing foldername parameter", http.StatusBadRequest)
		return
	}

	if matched, _ := regexp.MatchString("^[a-zA-Z0-9][a-zA-Z0-9_.\\- ]*$", folderName); !matched {
		http.Error(w, "Invalid folderName parameter", http.StatusBadRequest)
		return
	}

	folderPath := filepath.Join(dir, folderName)

	_, err = os.Stat(folderPath)
	if os.IsNotExist(err) {
		err := os.MkdirAll(folderPath, 0755)
		if err != nil {
			http.Error(w, "Failed to create folder", http.StatusInternalServerError)
			log.Printf("Failed to create folder: %v\n", err)
			return
		}
	}

	for _, item := range req {
		validFileNames := map[string]bool{"1": true, "2": true, "3": true, "4": true, "6": true}
		if !validFileNames[item.FileName] {
			http.Error(w, "Invalid fileName parameter", http.StatusBadRequest)
			return
		}

		dec := base64.NewDecoder(base64.StdEncoding, strings.NewReader(item.Base64Str))
		f, err := os.Create(folderPath + "/" + item.FileName + ".bmp")
		if err != nil {
			http.Error(w, "Failed to create file", http.StatusInternalServerError)
			log.Printf("Failed to create file: %v\n", err)
			return
		}
		defer f.Close()

		if _, err := io.Copy(f, dec); err != nil {
			http.Error(w, "Failed to save file", http.StatusInternalServerError)
			log.Printf("Failed to save file: %v\n", err)
			return
		}
	}

	w.WriteHeader(http.StatusCreated)
	w.Write([]byte("Files uploaded and saved"))
}

func handleDeleteReticleFolder(dir string, w http.ResponseWriter, r *http.Request) {
	folderName := r.URL.Query().Get("folderName")
	folderPath := filepath.Join(dir, folderName)

	if _, err := os.Stat(folderPath); os.IsNotExist(err) {
		respondWithError(w, http.StatusNotFound, "Reticle folder not found")
		return
	}

	if err := os.RemoveAll(folderPath); err != nil {
		log.Printf("Error deleting reticle folder: %v", err)
		respondWithError(w, http.StatusInternalServerError, "Server error")
		return
	}

	w.Write([]byte("OK"))
}

func deleteReticle(dir string, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Invalid request method", http.StatusMethodNotAllowed)
		return
	}

	folderName := r.URL.Query().Get("folderName")
	if folderName == "" {
		http.Error(w, "Missing folderName parameter", http.StatusBadRequest)
		return
	}

	folderPath := filepath.Join(dir, folderName)

	_, err := os.Stat(folderPath)
	if os.IsNotExist(err) {
		http.Error(w, "Folder does not exist", http.StatusNotFound)
		return
	}

	data, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read request body", http.StatusBadRequest)
		log.Printf("Failed to read request body: %v\n", err)
		return
	}

	var filesToDelete []string

	if err := json.Unmarshal(data, &filesToDelete); err != nil {
		http.Error(w, "Failed to parse JSON data", http.StatusBadRequest)
		log.Printf("Failed to parse JSON data: %v\n", err)
		return
	}

	for _, fileName := range filesToDelete {
		filePath := folderPath + "/" + fileName + ".bmp"
		err := os.Remove(filePath)
		if err != nil {
			http.Error(w, "Failed to delete file", http.StatusInternalServerError)
			log.Printf("Failed to delete file: %v\n", err)
			return
		}
	}

	files, err := os.ReadDir(folderPath)
	if err != nil {
		http.Error(w, "Failed to list files in the folder", http.StatusInternalServerError)
		log.Printf("Failed to list files in the folder: %v\n", err)
		return
	}

	if len(files) == 0 {
		err := os.Remove(folderPath)
		if err != nil {
			http.Error(w, "Failed to delete folder", http.StatusInternalServerError)
			log.Printf("Failed to delete folder: %v\n", err)
			return
		}
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte("Files deleted"))
}

func replaceFile(dir string, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Invalid request method", http.StatusMethodNotAllowed)
		return
	}

	data, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read request body", http.StatusBadRequest)
		log.Printf("Failed to read request body: %v\n", err)
		return
	}

	var req struct {
		OriginalFileName  string `json:"originalFileName"`
		OriginalBase64Str string `json:"originalBase64Str"`
		NewFileName       string `json:"newFileName"`
		NewBase64Str      string `json:"newBase64Str"`
	}

	if err := json.Unmarshal(data, &req); err != nil {
		http.Error(w, "Failed to parse JSON data", http.StatusBadRequest)
		log.Printf("Failed to parse JSON data: %v\n", err)
		return
	}

	if req.OriginalFileName == req.NewFileName && req.OriginalBase64Str == req.NewBase64Str {
		http.Error(w, "Parameters are identical", http.StatusBadRequest)
		return
	}

	folderName := r.URL.Query().Get("folderName")

	if matched, _ := regexp.MatchString("^[a-zA-Z0-9][a-zA-Z0-9_.\\- ]*$", folderName); !matched {
		http.Error(w, "Invalid folderName parameter", http.StatusBadRequest)
		return
	}

	folderPath := filepath.Join(dir, folderName)

	originalFilePath := folderPath + "/" + req.OriginalFileName + ".bmp"

	if req.OriginalFileName == req.NewFileName {
		decodedData, err := base64.StdEncoding.DecodeString(req.NewBase64Str)
		if err != nil {
			http.Error(w, "Failed to decode base64 data for the new file", http.StatusBadRequest)
			log.Printf("Failed to decode base64 data for the new file: %v\n", err)
			return
		}

		err = os.WriteFile(originalFilePath, decodedData, 0644)
		if err != nil {
			http.Error(w, "Failed to replace file", http.StatusInternalServerError)
			log.Printf("Failed to replace file: %v\n", err)
			return
		}
	} else {
		err := os.Remove(originalFilePath)
		if err != nil {
			http.Error(w, "Failed to delete original file", http.StatusInternalServerError)
			log.Printf("Failed to delete original file: %v\n", err)
			return
		}

		newFilePath := folderPath + "/" + req.NewFileName + ".bmp"
		decodedData, err := base64.StdEncoding.DecodeString(req.NewBase64Str)
		if err != nil {
			http.Error(w, "Failed to decode base64 data for the new file", http.StatusBadRequest)
			log.Printf("Failed to decode base64 data for the new file: %v\n", err)
			return
		}

		err = os.WriteFile(newFilePath, decodedData, 0644)
		if err != nil {
			http.Error(w, "Failed to create new file", http.StatusInternalServerError)
			log.Printf("Failed to create new file: %v\n", err)
			return
		}
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte("File replaced successfully"))
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

	http.HandleFunc("/getReticleImages", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		handleGetReticle(*dirRtr, w, r)
	}))

	http.HandleFunc("/uploadReticleImages", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		handlePutReticle(*dirRtr, w, r)
	}))

	http.HandleFunc("/deleteReticleFolder", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		handleDeleteReticleFolder(*dirRtr, w, r)
	}))

	http.HandleFunc("/deleteReticle", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		deleteReticle(*dirRtr, w, r)
	}))

	http.HandleFunc("/replaceFile", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		replaceFile(*dirRtr, w, r)
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
