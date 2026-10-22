#!/usr/bin/env pwsh
# End-to-end demo against a locally running HookFlow.
#
#   1. docker compose up -d
#   2. ./mvnw spring-boot:run                                        (terminal 1)
#   3. java -cp target/classes dev.hookflow.tools.MockReceiver 9099  (terminal 2)
#   4. ./scripts/demo.ps1
#
# NOTE: this file is saved as UTF-8 **with BOM** on purpose. Windows PowerShell
# 5.1 reads BOM-less files as ANSI and mangles the Chinese output.

param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ReceiverUrl = 'http://127.0.0.1:9099/hook',
    [string]$BootstrapToken = $(if ($env:HOOKFLOW_BOOTSTRAP_TOKEN) { $env:HOOKFLOW_BOOTSTRAP_TOKEN } else { 'hookflow-dev-bootstrap-token' })
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Write-Step { param([string]$Text) Write-Host ''; Write-Host ('=== ' + $Text) -ForegroundColor Cyan }
function Write-Ok   { param([string]$Text) Write-Host ('    OK  ' + $Text) -ForegroundColor Green }
function Write-Info { param([string]$Text) Write-Host ('        ' + $Text) -ForegroundColor DarkGray }

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body,
        [hashtable]$Headers = @{}
    )
    $request = @{
        Method      = $Method
        Uri         = $BaseUrl + $Path
        Headers     = $Headers
        ContentType = 'application/json'
    }
    if ($null -ne $Body) {
        $request.Body = ($Body | ConvertTo-Json -Depth 10 -Compress)
    }
    return Invoke-RestMethod @request
}

Write-Step '0. Health check'
$health = Invoke-RestMethod -Uri ($BaseUrl + '/actuator/health') -TimeoutSec 10
Write-Ok ('app status = ' + $health.status)
if ($health.status -ne 'UP') {
    Write-Host 'Application is not healthy. Check "docker compose ps" and the app log.' -ForegroundColor Red
    exit 1
}

Write-Step '1. Provision a tenant (the API key is shown once)'
$tenantName = 'demo-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$tenant = Invoke-Api -Method POST -Path '/v1/admin/tenants' `
    -Headers @{ 'X-Bootstrap-Token' = $BootstrapToken } `
    -Body @{ name = $tenantName; rateLimit = 100 }
$tenantId = $tenant.tenantId
$token = $tenant.apiKey
Write-Ok ('tenantId=' + $tenantId)
Write-Info ('apiKey=' + $token.Substring(0, 14) + '... (truncated)')

$auth = @{ Authorization = ('Bearer ' + $token) }

Write-Step '2. Create an endpoint (the signing secret is shown once)'
$endpoint = Invoke-Api -Method POST -Path '/v1/endpoints' -Headers $auth `
    -Body @{ url = $ReceiverUrl; subscribedTypes = 'order.paid'; maxAttempts = 8 }
$endpointId = $endpoint.id
Write-Ok ('endpointId=' + $endpointId + ' url=' + $ReceiverUrl)
Write-Info ('signingSecret=' + $endpoint.signingSecret.Substring(0, 10) + '... (truncated)')

Write-Step '3. Submit an event'
$idemKey = 'demo-' + [guid]::NewGuid().ToString('N')
$submitHeaders = $auth.Clone()
$submitHeaders['Idempotency-Key'] = $idemKey
$eventBody = @{ eventType = 'order.paid'; payload = @{ orderId = 1001; amount = 99.00; currency = 'CNY' } }
$event = Invoke-Api -Method POST -Path '/v1/events' -Headers $submitHeaders -Body $eventBody
$eventId = $event.eventId
Write-Ok ('eventId=' + $eventId + ' deliveryCount=' + $event.deliveryCount + ' idempotentReplay=' + $event.idempotentReplay)

Write-Step '4. Idempotency check: submit again with the SAME Idempotency-Key'
$again = Invoke-Api -Method POST -Path '/v1/events' -Headers $submitHeaders -Body $eventBody
if ($again.eventId -eq $eventId -and $again.idempotentReplay) {
    Write-Ok ('same eventId=' + $again.eventId + ' returned; no new event created')
} else {
    Write-Host 'Idempotency broken: got a different eventId' -ForegroundColor Red
    exit 1
}

Write-Step '5. Wait for the delivery to settle'
$deliveryId = $null
$detail = $null
$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline) {
    $page = Invoke-Api -Method GET -Path ('/v1/deliveries?eventId=' + $eventId) -Headers $auth
    if ($page.items.Count -gt 0) {
        $deliveryId = $page.items[0].id
        $detail = Invoke-Api -Method GET -Path ('/v1/deliveries/' + $deliveryId) -Headers $auth
        if ($detail.delivery.status -eq 'SUCCEEDED' -or $detail.delivery.status -eq 'DEAD') { break }
    }
    Start-Sleep -Milliseconds 500
}

if ($null -eq $detail) {
    Write-Host 'Timed out: no delivery was created' -ForegroundColor Red
    exit 1
}

$final = $detail.delivery
Write-Host ''
Write-Host ('    delivery #' + $deliveryId + ' : ' + $final.status + '  attempts=' + $final.attemptCount + '/' + $final.maxAttempts) -ForegroundColor Yellow
foreach ($attempt in $detail.attempts) {
    $line = '      attempt ' + $attempt.attemptNo + ': ' + $attempt.status + ' status=' + $attempt.statusCode + ' error=' + $attempt.errorClass + ' ' + $attempt.durationMs + 'ms'
    Write-Host $line
}

Write-Step '6. Receiver side (including its own signature verdict)'
try {
    $received = Invoke-RestMethod -Uri ($ReceiverUrl -replace '/hook$', '/received') -TimeoutSec 5
    Write-Ok ('receiver saw ' + $received.count + ' request(s)')
    foreach ($item in ($received.items | Select-Object -First 3)) { Write-Info $item }
} catch {
    Write-Host ('    Receiver unreachable (is MockReceiver running?): ' + $_.Exception.Message) -ForegroundColor Yellow
}

Write-Host ''
Write-Host 'Demo finished. Things to try next:' -ForegroundColor Cyan
Write-Host '  Make the receiver fail, then submit again and watch the backoff ladder:'
Write-Host ('    ' + $ReceiverUrl + '?status=500')
Write-Host '  Inspect deliveries that are waiting to retry:'
Write-Host ('    curl -s "' + $BaseUrl + '/v1/deliveries?status=RETRY_WAIT" -H "Authorization: Bearer ' + $token + '"')
Write-Host '  After fixing the receiver, replay the dead delivery:'
Write-Host ('    curl -X POST ' + $BaseUrl + '/v1/deliveries/' + $deliveryId + '/replay -H "Authorization: Bearer ' + $token + '"')
