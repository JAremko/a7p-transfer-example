# Start from the latest golang base image as the builder stage
FROM golang:latest as go-builder

# Set the Current Working Directory inside the container
WORKDIR /app

# Initialize a Go module
RUN go mod init github.com/jaremko/a7p_transfer_example

# Install protobuf compiler and Go protobuf plugin
RUN apt-get update && \
    apt-get install -y protobuf-compiler && \
    go install google.golang.org/protobuf/cmd/protoc-gen-go@latest

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

# Start from Node.js and OpenJDK base images for the second builder stage
FROM node:latest as node-builder

# Set the Current Working Directory inside the container
WORKDIR /app

# Install necessary utilities
RUN apt-get update && \
    apt-get install -y wget unzip

# Install Java
RUN wget https://download.java.net/java/GA/jdk11/9/GPL/openjdk-11.0.2_linux-x64_bin.tar.gz && \
    tar -xvf openjdk-11.0.2_linux-x64_bin.tar.gz && \
    rm openjdk-11.0.2_linux-x64_bin.tar.gz && \
    mv jdk-11.0.2 /usr/lib

ENV PATH="/usr/lib/jdk-11.0.2/bin:${PATH}"

# Initialize a Node.js project and install shadow-cljs
RUN npm init -y && \
    npm install --save shadow-cljs

# Copy the ClojureScript source files and shadow-cljs configuration
COPY src ./src
COPY shadow-cljs.edn ./

# Build the JavaScript workers using shadow-cljs
RUN npx shadow-cljs release transform-to-editable-worker \
                            transform-from-editable-worker

# executable image
FROM ubuntu:latest

WORKDIR /root/

# Copy the executable from the Go builder stage
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

# Expose port 8080 to the outside
EXPOSE 8080

# Command to run the executable
CMD ["./main", "-dir=/data"]
