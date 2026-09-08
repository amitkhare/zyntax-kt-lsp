gradle := if os() == "windows" { "gradlew.bat" } else { "./gradlew" }

# Build, test and package the language server
build:
    {{gradle}} build

# Clean build artifacts
clean:
    {{gradle}} clean

# Install the language server into server/build/install
install:
    {{gradle}} :server:installDist

# Run the standalone language server
run:
    {{gradle}} :server:run

# Run the standalone language server in debug mode (port 8000)
debug:
    {{gradle}} :server:debugRun

# Run all unit tests
test:
    {{gradle}} test

# Run a specific test class (e.g. just test-class CompletionsTest)
test-class name:
    {{gradle}} :server:test --tests "*{{name}}*"

# Run a specific test class with info logging (e.g. just test-class-debug CompletionsTest)
test-class-debug name:
    {{gradle}} :server:test --tests "*{{name}}*" --info

# Run linter (detekt)
lint:
    {{gradle}} detekt

# Update linter baseline
baseline-update:
    {{gradle}} createDetektBaseline

# Package the language server for distribution (ZIP)
dist-zip:
    {{gradle}} :server:distZip

# Package the language server with a debug launch configuration
package-debug:
    {{gradle}} :server:installDebugDist

# Generate a license report for dependencies
license-report:
    {{gradle}} :server:licenseReport

# Increment version, update changelog and create git tag
release:
    python3 scripts/release_version.py
