# Быстрое создание релиза v0.1.2

## ✅ Что уже сделано

1. ✅ Создан тег `v0.1.2` и отправлен на GitHub
2. ✅ Подготовлено описание релиза в файле `release_notes_v0.1.2.txt`
3. ✅ Создан CHANGELOG.md с полной историей изменений
4. ✅ Все изменения закоммичены и отправлены на GitHub

## 🚀 Создание релиза на GitHub

### Вариант 1: Через веб-интерфейс (Самый простой)

1. Откройте в браузере: https://github.com/JeanP00l/MeterSync/releases/new
2. В поле **"Choose a tag"** выберите `v0.1.2` (или введите `v0.1.2`)
3. В поле **"Release title"** введите: `v0.1.2 - Оптимизация кода входа и интеграция WebView`
4. В поле **"Describe this release"** скопируйте содержимое из файла `release_notes_v0.1.2.txt`
5. Нажмите кнопку **"Publish release"**

### Вариант 2: Через PowerShell скрипт

```powershell
# Сначала создайте Personal Access Token на GitHub:
# Settings → Developer settings → Personal access tokens → Tokens (classic)
# Права: repo

.\create_release.ps1 -Token YOUR_GITHUB_TOKEN
```

### Вариант 3: Через Bash скрипт (Git Bash)

```bash
chmod +x create_release.sh
./create_release.sh YOUR_GITHUB_TOKEN
```

### Вариант 4: Через GitHub CLI (если установлен)

```bash
gh release create v0.1.2 \
  --title "v0.1.2 - Оптимизация кода входа и интеграция WebView" \
  --notes-file release_notes_v0.1.2.txt
```

## 📋 Проверка

После создания релиза проверьте:
- https://github.com/JeanP00l/MeterSync/releases/tag/v0.1.2

Релиз должен появиться в списке релизов репозитория.

## 📝 Описание релиза

Полное описание изменений версии 0.1.2:
- **Файл**: `release_notes_v0.1.2.txt` - для копирования в GitHub
- **Документ**: `docs/RELEASE_v0.1.2.md` - расширенное описание
- **Changelog**: `docs/CHANGELOG.md` - полная история изменений

## 🔗 Ссылки

- Репозиторий: https://github.com/JeanP00l/MeterSync
- Теги: https://github.com/JeanP00l/MeterSync/tags
- Релизы: https://github.com/JeanP00l/MeterSync/releases

