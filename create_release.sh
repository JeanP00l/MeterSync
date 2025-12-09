#!/bin/bash

# Скрипт для создания релиза на GitHub
# Использование: ./create_release.sh YOUR_GITHUB_TOKEN

GITHUB_TOKEN=$1
REPO="JeanP00l/MeterSync"
TAG="v0.1.2"
RELEASE_NAME="v0.1.2 - Оптимизация кода входа и интеграция WebView"

# Читаем описание релиза из файла
RELEASE_BODY=$(cat release_notes_v0.1.2.txt)

# Создаем релиз через GitHub API
curl -X POST \
  -H "Authorization: token $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github.v3+json" \
  https://api.github.com/repos/$REPO/releases \
  -d "{
    \"tag_name\": \"$TAG\",
    \"name\": \"$RELEASE_NAME\",
    \"body\": $(echo "$RELEASE_BODY" | jq -Rs .),
    \"draft\": false,
    \"prerelease\": false
  }"

echo ""
echo "Релиз создан успешно!"

