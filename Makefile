.PHONY: build run


build:
	docker build -t go-server .

run:
	docker run -ti --rm --net=host -v $(shell pwd)/data:/data go-server
