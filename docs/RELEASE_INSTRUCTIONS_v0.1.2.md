# Инструкция по созданию релиза v0.1.2

## Способ 1: Через веб-интерфейс GitHub (Рекомендуется)

1. Перейдите на страницу создания релиза:
   https://github.com/JeanP00l/MeterSync/releases/new

2. Заполните форму:
   - **Tag version**: `v0.1.2` (выберите из существующих тегов)
   - **Release title**: `v0.1.2 - Оптимизация кода входа и интеграция WebView`
   - **Description**: Скопируйте содержимое из файла `release_notes_v0.1.2.txt` или `docs/RELEASE_v0.1.2.md`

3. Нажмите кнопку **"Publish release"**

## Способ 2: Через GitHub API (с токеном)

### Шаг 1: Создайте Personal Access Token

1. Перейдите в Settings → Developer settings → Personal access tokens → Tokens (classic)
2. Создайте новый токен с правами `repo`
3. Скопируйте токен

### Шаг 2: Создайте релиз

Выполните команду:

```bash
# Windows (Git Bash)
GITHUB_TOKEN=your_token_here
curl -X POST \
  -H "Authorization: token $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github.v3+json" \
  https://api.github.com/repos/JeanP00l/MeterSync/releases \
  -d @- << EOF
{
  "tag_name": "v0.1.2",
  "name": "v0.1.2 - Оптимизация кода входа и интеграция WebView",
  "body": "$(cat release_notes_v0.1.2.txt | sed 's/"/\\"/g' | sed ':a;N;$!ba;s/\n/\\n/g')",
  "draft": false,
  "prerelease": false
}
EOF
```

Или используйте готовый скрипт:

```bash
chmod +x create_release.sh
./create_release.sh YOUR_GITHUB_TOKEN
```

## Способ 3: Через GitHub CLI

Если установлен GitHub CLI:

```bash
gh release create v0.1.2 \
  --title "v0.1.2 - Оптимизация кода входа и интеграция WebView" \
  --notes-file release_notes_v0.1.2.txt
```

## Проверка

После создания релиза проверьте:
- https://github.com/JeanP00l/MeterSync/releases/tag/v0.1.2

Релиз должен быть виден в списке релизов репозитория.

