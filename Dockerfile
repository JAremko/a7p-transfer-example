# Start from the latest golang base image as the builder stage
FROM golang:latest as builder

# Set the Current Working Directory inside the container
WORKDIR /app

# Initialize a Go module
RUN go mod init github.com/jaremko/a7p_transfer_example

# Install protobuf compiler and Go protobuf plugin
RUN apt-get update && \
    apt-get install -y openssl protobuf-compiler && \
    go install google.golang.org/protobuf/cmd/protoc-gen-go@latest

RUN mkdir -p /certs && \
	openssl req -x509 -newkey rsa:4096 -keyout /certs/key.pem -out /certs/cert.pem -days 365 -nodes -subj "/C=US/ST=California/L=San Francisco/O=JustAnExample/CN=localhost"

ENV PATH=$PATH:/go/bin

# Download all dependencies.
RUN go get github.com/golang/protobuf/jsonpb
RUN go get github.com/golang/protobuf/proto
RUN go get google.golang.org/protobuf/reflect/protoreflect
RUN go get google.golang.org/protobuf/runtime/protoimpl

# Copy protobuf files from the current directory to the Working Directory inside the container
COPY profedit.proto ./

RUN mkdir -p ./profedit

# Compile the protobuf files
RUN protoc --go_out=./profedit --go_opt=paths=source_relative ./profedit.proto

# Copy the source
COPY main.go ./

# Build the Go app
RUN go build -o main .

RUN chmod +x main

# executable image
FROM ubuntu:latest

WORKDIR /root/

# Copy the executable from the builder stage
COPY --from=builder /app/main .
COPY index.html .
COPY main.js .
COPY bulma.css .
COPY --from=builder /certs /certs

# Expose port 443 to the outside
EXPOSE 443

# Command to run the executable
CMD ["./main", "-dir=/data", "-cert=/certs/cert.pem", "-key=/certs/key.pem"]
