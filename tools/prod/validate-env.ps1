Param(
  [string]$CameraEnv = "Camera-Site",
  [string]$NodeEnv = "tpeapp-backend"
)

$ErrorActionPreference = "Stop"

function Get-EnvValue {
  Param([string]$Name)
  return [Environment]::GetEnvironmentVariable($Name)
}

$issues = @()
$warnings = @()

Write-Host "Validating production environment variables..." -ForegroundColor Cyan

# Camera-Site checks
$environment = Get-EnvValue "ENVIRONMENT"
$secretKey = Get-EnvValue "SECRET_KEY"
$droolSalt = Get-EnvValue "DROOL_SALT"
$mockAuth = Get-EnvValue "MOCK_AUTH"

if ([string]::IsNullOrWhiteSpace($environment)) {
  $issues += "[$CameraEnv] ENVIRONMENT is not set."
} elseif ($environment.ToLower() -notin @("production", "prod")) {
  $issues += "[$CameraEnv] ENVIRONMENT must be production/prod (current: $environment)."
}

if ([string]::IsNullOrWhiteSpace($secretKey)) {
  $issues += "[$CameraEnv] SECRET_KEY is not set."
} elseif ($secretKey -eq "changeme-replace-in-production!!") {
  $issues += "[$CameraEnv] SECRET_KEY is using default insecure value."
}

if ([string]::IsNullOrWhiteSpace($droolSalt)) {
  $issues += "[$CameraEnv] DROOL_SALT is not set."
}

if (-not [string]::IsNullOrWhiteSpace($mockAuth) -and $mockAuth.ToLower() -eq "true") {
  $issues += "[$CameraEnv] MOCK_AUTH must be disabled in production."
}

# Node backend checks
$controlToken = Get-EnvValue "CONTROL_API_TOKEN"
$pairingToken = Get-EnvValue "PAIRING_TOKEN"
$mqttUrl = Get-EnvValue "MQTT_BROKER_URL"
$mqttHost = Get-EnvValue "MQTT_BROKER_HOST"

if ([string]::IsNullOrWhiteSpace($controlToken)) {
  $issues += "[$NodeEnv] CONTROL_API_TOKEN is not set."
}

if ([string]::IsNullOrWhiteSpace($pairingToken)) {
  $issues += "[$NodeEnv] PAIRING_TOKEN is not set."
}

if ([string]::IsNullOrWhiteSpace($mqttUrl) -and [string]::IsNullOrWhiteSpace($mqttHost)) {
  $warnings += "[$NodeEnv] MQTT_BROKER_URL / MQTT_BROKER_HOST not set in environment (may rely on defaults)."
}

if ($warnings.Count -gt 0) {
  Write-Host "Warnings:" -ForegroundColor Yellow
  foreach ($w in $warnings) { Write-Host " - $w" -ForegroundColor Yellow }
}

if ($issues.Count -gt 0) {
  Write-Host "FAILED:" -ForegroundColor Red
  foreach ($i in $issues) { Write-Host " - $i" -ForegroundColor Red }
  exit 1
}

Write-Host "PASS: required production environment variables look good." -ForegroundColor Green
exit 0
