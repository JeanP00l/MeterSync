# PowerShell скрипт для создания релиза на GitHub
# Использование: .\create_release.ps1 -Token YOUR_GITHUB_TOKEN

param(
    [Parameter(Mandatory=$true)]
    [string]$Token
)

$repo = "JeanP00l/MeterSync"
$tag = "v0.1.2"
$releaseName = "v0.1.2 - Оптимизация кода входа и интеграция WebView"

# Читаем описание релиза
$releaseBody = Get-Content -Path "release_notes_v0.1.2.txt" -Raw

# Экранируем специальные символы для JSON
$releaseBody = $releaseBody -replace '"', '\"'
$releaseBody = $releaseBody -replace "`n", "\n"
$releaseBody = $releaseBody -replace "`r", ""

# Формируем JSON
$jsonBody = @{
    tag_name = $tag
    name = $releaseName
    body = $releaseBody
    draft = $false
    prerelease = $false
} | ConvertTo-Json -Depth 10

# Создаем релиз
$headers = @{
    "Authorization" = "token $Token"
    "Accept" = "application/vnd.github.v3+json"
}

try {
    $response = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" `
        -Method Post `
        -Headers $headers `
        -Body $jsonBody `
        -ContentType "application/json"
    
    Write-Host "Релиз успешно создан!" -ForegroundColor Green
    Write-Host "URL: $($response.html_url)" -ForegroundColor Cyan
} catch {
    Write-Host "Ошибка при создании релиза:" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host $_.ErrorDetails.Message -ForegroundColor Red
}

