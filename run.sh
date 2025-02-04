#!/usr/bin/env bash

cd "$(dirname "$0")"

export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED"

mvn clean package

echo "-------------------------------------"
echo "Starting up at http://localhost:9292/"
echo "-------------------------------------"

docker compose down
docker compose build
docker compose up

