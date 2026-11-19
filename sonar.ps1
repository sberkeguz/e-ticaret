# SonarQube ile kod kalitesi taraması: sunucuyu açar, ilk kurulumu yapar, üç projeyi tarar.
#   .\sonar.ps1            -> backend + arayüz + iyzico-service
#   .\sonar.ps1 backend    -> yalnızca seçilenler (backend, frontend, iyzico)
# Sonuçlar: http://localhost:9000 (kullanıcı: admin, şifre: .env'deki SONAR_ADMIN_PASSWORD).
# Sunucuyu kapatmak için: docker compose --profile sonar stop sonarqube sonar-db
param([string[]]$Targets = @("backend", "frontend", "iyzico"))

$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot
$Url = "http://localhost:9000"
$EnvFile = Join-Path $PSScriptRoot ".env"

function Step($text) { Write-Host ""; Write-Host "==> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "    $text" -ForegroundColor Red; exit 1 }

function EnvValue($name) {
    if (Test-Path $EnvFile) {
        $line = Get-Content $EnvFile | Where-Object { $_ -match "^\s*$name\s*=" } | Select-Object -Last 1
        if ($line) { return ($line -split "=", 2)[1].Trim() }
    }
    return $null
}

function Auth($user, $password) {
    $bytes = [Text.Encoding]::UTF8.GetBytes("${user}:${password}")
    return @{ Authorization = "Basic " + [Convert]::ToBase64String($bytes) }
}

# 1) Sunucu
Step "SonarQube sunucusu"
docker compose --profile sonar up -d sonarqube sonar-db
if ($LASTEXITCODE -ne 0) { Fail "Konteynerler başlatılamadı (Docker açık mı?)." }
$deadline = (Get-Date).AddMinutes(5)
do {
    Start-Sleep -Seconds 3
    try { $status = (Invoke-RestMethod "$Url/api/system/status" -TimeoutSec 5).status } catch { $status = "..." }
    Write-Host "    durum: $status"
} while ($status -ne "UP" -and (Get-Date) -lt $deadline)
if ($status -ne "UP") { Fail "SonarQube 5 dakikada açılmadı: docker compose logs sonarqube" }

# 2) İlk kurulum: varsayılan admin/admin şifresi değiştirilir (SonarQube ilk girişte zaten zorunlu tutar).
$adminPassword = EnvValue "SONAR_ADMIN_PASSWORD"
if (-not $adminPassword) {
    $adminPassword = "Sonar_dev_password1!"
    Add-Content $EnvFile "`nSONAR_ADMIN_PASSWORD=$adminPassword"
}
try {
    $valid = (Invoke-RestMethod "$Url/api/authentication/validate" -Headers (Auth "admin" "admin")).valid
    if ($valid) {
        Step "İlk kurulum: admin şifresi .env'deki SONAR_ADMIN_PASSWORD yapılıyor"
        Invoke-RestMethod "$Url/api/users/change_password" -Method Post -Headers (Auth "admin" "admin") `
            -Body @{ login = "admin"; previousPassword = "admin"; password = $adminPassword } | Out-Null
    }
} catch { }
$admin = Auth "admin" $adminPassword

# 3) Tarama anahtarı (token): .env'de yoksa ya da geçersizse yenisi üretilip .env'ye yazılır.
$token = EnvValue "SONAR_TOKEN"
$tokenOk = $false
if ($token) {
    try { $tokenOk = (Invoke-RestMethod "$Url/api/authentication/validate" -Headers (Auth $token "")).valid } catch { }
}
if (-not $tokenOk) {
    Step "Tarama anahtarı üretiliyor"
    try {
        $name = "e-ticaret-" + (Get-Date -Format "yyyyMMdd-HHmmss")
        $token = (Invoke-RestMethod "$Url/api/user_tokens/generate" -Method Post -Headers $admin `
            -Body @{ name = $name; type = "GLOBAL_ANALYSIS_TOKEN" }).token
    } catch { Fail "Anahtar üretilemedi. admin şifresi .env'deki SONAR_ADMIN_PASSWORD ile aynı mı?" }
    $lines = @(Get-Content $EnvFile | Where-Object { $_ -notmatch "^\s*SONAR_TOKEN\s*=" })
    $lines += "SONAR_TOKEN=$token"
    # BOM'suz yazılır: docker compose .env'nin ilk satırını BOM'la okuyamaz.
    [IO.File]::WriteAllLines($EnvFile, [string[]]$lines, (New-Object Text.UTF8Encoding $false))
}

# 4) Taramalar (her biri kendi konteynerinde; iş bitince silinir)
$services = @{ backend = "sonar-scan-backend"; frontend = "sonar-scan-frontend"; iyzico = "sonar-scan-iyzico" }
$failed = @()
foreach ($target in $Targets) {
    $service = $services[$target]
    if (-not $service) { Fail "Bilinmeyen hedef: $target (backend, frontend, iyzico)" }
    Step "Taranıyor: $target"
    docker compose --profile sonar --profile sonar-scan run --rm -e SONAR_TOKEN=$token $service
    if ($LASTEXITCODE -ne 0) { $failed += $target }
}

Write-Host ""
if ($failed.Count -gt 0) { Fail "Başarısız tarama: $($failed -join ', ')" }
Write-Host "Bitti. Sonuçlar: $Url/projects" -ForegroundColor Green
Start-Process "$Url/projects"
