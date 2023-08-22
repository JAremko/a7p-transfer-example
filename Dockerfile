# Builder stage for Go
FROM golang:latest as go-builder

# Set the working directory in the Docker image
WORKDIR /app

# Install the protobuf compiler
RUN apt-get update && apt-get -y install curl protobuf-compiler

# Install Go protobuf plugins
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
RUN go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

# Install buf tool using the official installation method
RUN curl -sSL "https://github.com/bufbuild/buf/releases/download/v1.26.1/buf-Linux-x86_64" -o /usr/local/bin/buf && \
    chmod +x /usr/local/bin/buf

# Add Go binaries to PATH
ENV PATH=$PATH:/go/bin

# Initialize a Go module
RUN go mod init github.com/jaremko/a7p_transfer_example

# Download all required dependencies
RUN go get github.com/gorilla/websocket
RUN go get github.com/golang/protobuf/proto
RUN go get github.com/golang/protobuf/jsonpb
RUN go get google.golang.org/grpc
RUN go get google.golang.org/protobuf/reflect/protoreflect
RUN go get google.golang.org/protobuf/runtime/protoimpl
RUN go get google.golang.org/grpc/codes
RUN go get google.golang.org/grpc/status
RUN go get github.com/bufbuild/protovalidate-go

COPY profedit_validate.proto .
COPY buf.gen.yaml .
COPY buf.yaml .

RUN buf mod update

# Compile the protobuf files using buf
RUN mkdir -p profedit && buf generate

# Copy Go files and build
COPY main.go .
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o main

# Make the main executable
RUN chmod +x main

# Node.js builder stage
FROM node:latest as node-builder

WORKDIR /app

# Install necessary utilities and Java
RUN apt-get update && \
    apt-get install -y wget unzip && \
    wget https://download.java.net/java/GA/jdk11/9/GPL/openjdk-11.0.2_linux-x64_bin.tar.gz && \
    tar -xvf openjdk-11.0.2_linux-x64_bin.tar.gz && \
    rm openjdk-11.0.2_linux-x64_bin.tar.gz && \
    mv jdk-11.0.2 /usr/lib

ENV PATH="/usr/lib/jdk-11.0.2/bin:${PATH}"

# Initialize a Node.js project and install shadow-cljs
RUN npm init -y && \
    npm install --save shadow-cljs

COPY src ./src
COPY shadow-cljs.edn .

RUN npx shadow-cljs release transform-to-editable-worker transform-from-editable-worker

# Final stage
FROM ubuntu:latest

WORKDIR /root/

# Copy the executable and other assets
COPY --from=go-builder /app/main .
COPY index.html /www/
COPY main.js /www/
COPY bulma.css /www/
COPY monokai-sublime.min.css /www/
COPY highlight.min.js /www/
COPY protobuf.js /www/
COPY profedit.proto /www/
# Copy worker JavaScript files
COPY --from=node-builder /app/public/js/transform-to-editable-worker.js /www/
COPY --from=node-builder /app/public/js/transform-from-editable-worker.js /www/
COPY favicon.ico /www/

EXPOSE 8080
CMD ["./main", "-dir=/data"]
