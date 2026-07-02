param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$TraceId = "",
    [string]$MainAppBaseUrl = ""
)

if ([string]::IsNullOrWhiteSpace($TraceId)) {
    $TraceId = "pr2c-smoke-$(Get-Date -Format 'yyyyMMddHHmmss')"
}

$base = $BaseUrl.TrimEnd("/")
$headers = @{ "X-Trace-Id" = $TraceId }

function Invoke-SmokeGet {
    param(
        [string]$Scenario,
        [string]$Url,
        [bool]$Optional = $false
    )

    try {
        $response = Invoke-WebRequest -Uri $Url -Headers $headers -Method Get -UseBasicParsing -ErrorAction Stop
        $status = [int]$response.StatusCode
        $responseTraceId = Get-HeaderValue -Headers $response.Headers -Name "X-Trace-Id"
        $body = Convert-ContentToString -Content $response.Content
        $summary = Get-BodySummary -Body $body
        Write-Host ("{0,-24} status={1,-3} traceId={2} {3}" -f $Scenario, $status, $responseTraceId, $summary)
    } catch {
        $httpResponse = $_.Exception.Response
        if ($httpResponse -ne $null) {
            $status = [int]$httpResponse.StatusCode
            $responseTraceId = Get-HeaderValue -Headers $httpResponse.Headers -Name "X-Trace-Id"
            $body = Read-ResponseBody -Response $httpResponse
            $summary = Get-BodySummary -Body $body
            Write-Host ("{0,-24} status={1,-3} traceId={2} {3}" -f $Scenario, $status, $responseTraceId, $summary)
        } else {
            $label = if ($Optional) { "optional_error" } else { "error" }
            Write-Host ("{0,-24} status={1} traceId={2} errorType={3}" -f $Scenario, $label, $TraceId, $_.Exception.GetType().Name)
        }
    }
}

function Get-HeaderValue {
    param(
        [object]$Headers,
        [string]$Name
    )
    if ($Headers -eq $null) {
        return ""
    }
    try {
        $value = $Headers[$Name]
        if ($value -eq $null) {
            return ""
        }
        return [string]$value
    } catch {
        return ""
    }
}

function Convert-ContentToString {
    param([object]$Content)
    if ($Content -eq $null) {
        return ""
    }
    if ($Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Content)
    }
    return [string]$Content
}

function Read-ResponseBody {
    param([object]$Response)
    try {
        $stream = $Response.GetResponseStream()
        if ($stream -eq $null) {
            return ""
        }
        $reader = New-Object System.IO.StreamReader($stream)
        return $reader.ReadToEnd()
    } catch {
        return ""
    }
}

function Get-BodySummary {
    param([string]$Body)
    if ([string]::IsNullOrWhiteSpace($Body)) {
        return "body=empty"
    }
    try {
        $json = $Body | ConvertFrom-Json -ErrorAction Stop
        if ($json.code) {
            return "code=$($json.code)"
        }
        if ($json.orderNo) {
            return "order=parse_ok"
        }
        if ($json.status) {
            return "status=$($json.status)"
        }
        return "json=parse_ok"
    } catch {
        return "body=parse_failed"
    }
}

Write-Host "PR-2C supplier-gateway smoke"
Write-Host ("baseUrl={0} traceId={1}" -f $base, $TraceId)

Invoke-SmokeGet -Scenario "gateway_health" -Url "$base/actuator/health"
Invoke-SmokeGet -Scenario "success" -Url "$base/supplier/orders/ORD-1001?userId=1001&scenario=success"
Invoke-SmokeGet -Scenario "not_found" -Url "$base/supplier/orders/ORD-1001?userId=1001&scenario=not_found"
Invoke-SmokeGet -Scenario "timeout" -Url "$base/supplier/orders/ORD-1001?userId=1001&scenario=timeout"
Invoke-SmokeGet -Scenario "server_error" -Url "$base/supplier/orders/ORD-1001?userId=1001&scenario=server_error"
Invoke-SmokeGet -Scenario "malformed" -Url "$base/supplier/orders/ORD-1001?userId=1001&scenario=malformed"
Invoke-SmokeGet -Scenario "missing_field" -Url "$base/supplier/orders/ORD-1001?userId=1001&scenario=missing_field"

if (-not [string]::IsNullOrWhiteSpace($MainAppBaseUrl)) {
    $main = $MainAppBaseUrl.TrimEnd("/")
    Write-Host ""
    Write-Host ("Optional main-app checks baseUrl={0}" -f $main)
    Invoke-SmokeGet -Scenario "main_health" -Url "$main/actuator/health" -Optional $true
    Invoke-SmokeGet -Scenario "main_supplier_requests" -Url "$main/actuator/metrics/travelcare.supplier.requests.total" -Optional $true
    Invoke-SmokeGet -Scenario "main_supplier_failures" -Url "$main/actuator/metrics/travelcare.supplier.failures.total" -Optional $true
}
