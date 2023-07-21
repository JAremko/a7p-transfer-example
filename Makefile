.PHONY: build run


build:
	docker build -t go-server .

run:
	docker run -ti --net=host -v $(shell pwd)/data:/data go-server
