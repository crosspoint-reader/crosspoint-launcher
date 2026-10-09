#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT
javac -d "$build_dir" app/src/main/java/com/crosspoint/launcher/reader/EpubBook.java app/src/test/java/com/crosspoint/launcher/reader/EpubBookCheck.java
java -ea -cp "$build_dir" com.crosspoint.launcher.reader.EpubBookCheck
