#!/bin/bash
#
# Data Product Uploader - convenience wrapper
# Usage: ./bin/upload.sh <zip-file> [options]
#
# Required environment variables (or pass as flags):
#   ZEENEA_API_KEY   - API key secret
#   ZEENEA_TENANT    - Zeenea tenant name (default: product-demo)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR="$PROJECT_DIR/target/data-product-uploader-0.3.3.jar"

# Build if JAR doesn't exist
if [ ! -f "$JAR" ]; then
    echo "JAR not found, building..."
    mvn -f "$PROJECT_DIR/pom.xml" -q clean package
fi

exec java -jar "$JAR" "$@"
