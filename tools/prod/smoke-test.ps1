Param(
  [string]$CameraBase = "http://127.0.0.1:8011",
  [string]$NodeBase = "http://127.0.0.1:3000",
  [switch]$SkipNode
)

$ErrorActionPreference = "Stop"

$failures = @()

function Invoke-TestRequest {
  Param(
    [Parameter(Mandatory=$true)][string]$Name,
    [Parameter(Mandatory=$true)][ValidateSet("GET", "POST")][string]$Method,
    [Parameter(Mandatory=$true)][string]$Url,
    [int[]]$ExpectedStatus = @(200),
    [hashtable]$Headers,
    $Body,
    [string]$ContentType
  )

  try {
    $params = @{
      Method = $Method
      Uri = $Url
      UseBasicParsing = $true
      TimeoutSec = 20
    }
    if ($Headers) { $params.Headers = $Headers }
    if ($null -ne $Body) { $params.Body = $Body }
    if ($ContentType) { $params.ContentType = $ContentType }

    $response = Invoke-WebRequest @params
    $status = [int]$response.StatusCode
    if ($ExpectedStatus -notcontains $status) {
      $script:failures += "$Name -> expected [$($ExpectedStatus -join ',')] got $status"
      Write-Host "FAIL  $Name ($status)" -ForegroundColor Red
      return $null
    }

    Write-Host "PASS  $Name ($status)" -ForegroundColor Green
    return $response
  } catch {
    $status = $null
    if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
      $status = [int]$_.Exception.Response.StatusCode.value__
    }

    if ($null -ne $status -and $ExpectedStatus -contains $status) {
      Write-Host "PASS  $Name ($status)" -ForegroundColor Green
      return $null
    }

    $msg = if ($null -ne $status) {
      "$Name -> expected [$($ExpectedStatus -join ',')] got $status"
    } else {
      "$Name -> request error: $($_.Exception.Message)"
    }
    $script:failures += $msg
    Write-Host "FAIL  $Name" -ForegroundColor Red
    return $null
  }
}

Write-Host "Running smoke tests..." -ForegroundColor Cyan

# Camera-Site checks
Invoke-TestRequest -Name "Camera docs" -Method GET -Url "$CameraBase/docs" -ExpectedStatus @(200)

$watchResp = Invoke-TestRequest -Name "Create public watch session" -Method POST -Url "$CameraBase/api/public/watch/session" -ExpectedStatus @(200) -Body @{ phone = "smoke" }
if ($watchResp -and $watchResp.Content) {
  try {
    $json = $watchResp.Content | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($json.ws_url)) {
      $failures += "Create public watch session -> ws_url missing in response"
      Write-Host "FAIL  Watch session response missing ws_url" -ForegroundColor Red
    } else {
      Write-Host "PASS  Watch session returned ws_url" -ForegroundColor Green
    }
  } catch {
    $failures += "Create public watch session -> invalid JSON response"
    Write-Host "FAIL  Watch session response parse" -ForegroundColor Red
  }
}

Invoke-TestRequest -Name "Handler devices requires auth" -Method GET -Url "$CameraBase/api/handler/devices" -ExpectedStatus @(401,403)

if (-not $SkipNode) {
  # Node checks
  $openUrlBody = '{"url":"https://example.com"}'
  Invoke-TestRequest -Name "Node command route unauthorized" -Method POST -Url "$NodeBase/api/command/open-url" -ExpectedStatus @(401,503) -Body $openUrlBody -ContentType "application/json"

  $webhookBody = '{"event":"smoke"}'
  Invoke-TestRequest -Name "Node webhook route unauthorized" -Method POST -Url "$NodeBase/api/tpe/webhook" -ExpectedStatus @(401,503) -Body $webhookBody -ContentType "application/json"

  $vitalsBody = '{"vitals":[{"type":"heart_rate","value":72,"unit":"bpm"}]}'
  Invoke-TestRequest -Name "Node vitals route unauthorized" -Method POST -Url "$NodeBase/api/vitals/sync" -ExpectedStatus @(401,503) -Body $vitalsBody -ContentType "application/json"

  $statusBody = '{"device_id":"smoke-device","battery_pct":85}'
  Invoke-TestRequest -Name "Node device-status route unauthorized" -Method POST -Url "$NodeBase/api/handler/device-status" -ExpectedStatus @(401,503) -Body $statusBody -ContentType "application/json"

  Invoke-TestRequest -Name "Node handler status unauthorized" -Method GET -Url "$NodeBase/api/handler/status" -ExpectedStatus @(401,503)
  Invoke-TestRequest -Name "Node handler devices unauthorized" -Method GET -Url "$NodeBase/api/handler/devices" -ExpectedStatus @(401,503)

  $pairBody = '{"mqtt_client_id":"smoke-client","pairing_token":"invalid-token"}'
  Invoke-TestRequest -Name "Node pair rejects invalid token" -Method POST -Url "$NodeBase/api/pair" -ExpectedStatus @(403) -Body $pairBody -ContentType "application/json"
} else {
  Write-Host "Skipping Node checks (--SkipNode)." -ForegroundColor Yellow
}

if ($failures.Count -gt 0) {
  Write-Host "" 
  Write-Host "Smoke test FAILED:" -ForegroundColor Red
  foreach ($f in $failures) { Write-Host " - $f" -ForegroundColor Red }
  exit 1
}

Write-Host "" 
Write-Host "Smoke test PASS: all checks passed." -ForegroundColor Green
exit 0
