.PHONY: build run


build:
	docker build -t go-server .

run:
	docker run -ti --rm -p 8080:8080 -v $(shell pwd)/data:/data go-server
