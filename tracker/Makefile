.PHONY: all build test clean help re

help:
	@echo "Tracker P2P - Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  all      - Build tracker (default)"
	@echo "  build    - Build tracker application"
	@echo "  test     - Run all tests (unit + integration)"
	@echo "  clean    - Remove all build artifacts"
	@echo "  re       - Clean and rebuild"

all: build

build:
	@echo "Building tracker application..."
	@cd src && make all

test:
	@echo "Running all tests..."
	@cd test && make test

clean:
	@echo "Cleaning build artifacts..."
	@cd src && make clean
	@cd test && make clean

re: clean build