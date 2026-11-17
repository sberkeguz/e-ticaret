# E-ticaret sistemini tek tıkla başlatır: Docker Desktop -> tüm konteynerler -> ngrok -> tarayıcı.
# Masaüstündeki "E-ticaret Başlat" kısayolu bu dosyayı çalıştırır. Tekrar çalıştırmak güvenlidir:
# zaten çalışan şeylere dokunmaz.

$ErrorActionPreference = "Stop"
$Project = $PSScriptRoot
$DockerExe = Join-Path $env:LOCALAPPDATA "Programs\DockerDesktop\Docker Desktop.exe"
$NgrokUrl = "https://rescuer-premises-program.ngrok-free.dev"
$Host.UI.RawUI.WindowTitle = "E-ticaret başlatılıyor"

function Step($text) { Write-Host ""; Write-Host "==> $text" -ForegroundColor Cyan }
function Ok($text) { Write-Host "    $text" -ForegroundColor Green }
function Fail($text) {
    Write-Host "    $text" -ForegroundColor Red
    Read-Host "Kapatmak için Enter'a basın"
    exit 1
}

function DockerReady {
    docker info --format "{{.ServerVersion}}" *> $null
    return $LASTEXITCODE -eq 0
}

# 1) Docker Desktop
Step "Docker Desktop"
if (DockerReady) {
    Ok "Zaten çalışıyor."
} else {
    if (-not (Test-Path $DockerExe)) { Fail "Docker Desktop bulunamadı: $DockerExe" }
    Start-Process $DockerExe
    Write-Host "    Başlatıldı, hazır olması bekleniyor (1-2 dk sürebilir)..."
    $deadline = (Get-Date).AddMinutes(4)
    while (-not (DockerReady)) {
        if ((Get-Date) -gt $deadline) { Fail "Docker 4 dakikada hazır olmadı." }
        Start-Sleep -Seconds 3
    }
    Ok "Hazır."
}

# 2) Konteynerler (postgres, rabbitmq, iyzico-service, backend, frontend, pgadmin, grafana)
Step "Konteynerler başlatılıyor"
Set-Location $Project
docker compose up -d
if ($LASTEXITCODE -ne 0) { Fail "docker compose up başarısız." }

Write-Host "    Backend'in hazır olması bekleniyor..."
$deadline = (Get-Date).AddMinutes(3)
while ($true) {
    $state = docker compose ps backend --format "{{.Status}}" 2>$null
    if ($state -match "healthy" -and $state -notmatch "unhealthy") { break }
    if ((Get-Date) -gt $deadline) { Fail "Backend 3 dakikada hazır olmadı. Durum: $state" }
    Start-Sleep -Seconds 3
}
Ok "Backend hazır."

# 3) ngrok (iyzico'nun 3D Secure dönüşü için dışarıdan erişim)
Step "ngrok"
if (Get-Process ngrok -ErrorAction SilentlyContinue) {
    Ok "Zaten çalışıyor."
} else {
    Start-Process powershell -ArgumentList '-NoExit', '-Command', "ngrok http --url=$NgrokUrl 8080"
    Ok "Ayrı pencerede başlatıldı: $NgrokUrl"
}

# 4) Durum ve tarayıcı
Step "Durum"
docker compose ps --format "table {{.Service}}`t{{.Status}}"

Write-Host ""
Write-Host "Mağaza / admin paneli : http://localhost:3000"
Write-Host "Backend (Swagger)     : http://localhost:8080/swagger-ui.html"
Write-Host "Grafana               : http://localhost:3001"
Write-Host "pgAdmin               : http://localhost:5050 (giriş gerekmez)"
Write-Host "RabbitMQ paneli       : http://localhost:15672 (otomatik giriş)"
Write-Host "ngrok                 : $NgrokUrl"

# Paneller giriş yapılmış açılır: Grafana ve pgAdmin girişsiz, RabbitMQ autologin sayfasıyla.
Start-Process "http://localhost:3000"
Start-Process "http://localhost:15672/autologin.html"
Start-Process "http://localhost:3001"
Start-Process "http://localhost:5050"

Write-Host ""
Ok "Her şey çalışıyor. Bu pencere 10 saniye sonra kapanacak."
Start-Sleep -Seconds 10
