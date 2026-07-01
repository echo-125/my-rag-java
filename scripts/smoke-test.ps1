# scripts/smoke-test.ps1
# 用法：.\scripts\smoke-test.ps1 -Mode both
#       .\scripts\smoke-test.ps1 -Mode new

param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("legacy", "both", "new")]
    [string]$Mode,

    [string]$BaseUrl = "http://localhost:8080"
)

$errors = @()

function Test-Url {
    param([string]$Url, [int]$ExpectedStatus, [string]$Description)
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
        $actualStatus = $response.StatusCode
    } catch {
        $actualStatus = $_.Exception.Response.StatusCode.value__
    }
    if ($actualStatus -eq $ExpectedStatus) {
        Write-Host "[PASS] $Description -> $actualStatus" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] $Description -> $actualStatus (期望 $ExpectedStatus)" -ForegroundColor Red
        $errors += $Description
    }
}

Write-Host "=== Smoke Test (mode=$Mode) ===" -ForegroundColor Cyan

switch ($Mode) {
    "legacy" {
        Test-Url "$BaseUrl/" 200 "GET / -> 旧前端"
    }
    "both" {
        Test-Url "$BaseUrl/" 200 "GET / -> 旧前端"
        Test-Url "$BaseUrl/app/" 200 "GET /app/ -> 新前端入口"
        Test-Url "$BaseUrl/api/dashboard/stats" 200 "API 可达"
    }
    "new" {
        Test-Url "$BaseUrl/" 200 "GET / -> 新前端入口"
        Test-Url "$BaseUrl/api/dashboard/stats" 200 "API 可达"
        # 从 index.html 提取实际 JS 资源并校验（可靠检查）
        try {
            $html = (Invoke-WebRequest -Uri "$BaseUrl/" -UseBasicParsing).Content
            $jsMatch = [regex]::Match($html, 'src="([^"]*\.js)"')
            if ($jsMatch.Success) {
                $jsPath = $jsMatch.Groups[1].Value
                if ($jsPath.StartsWith("./")) {
                    $jsUrl = "$BaseUrl/$($jsPath.Substring(2))"
                } else {
                    $jsUrl = "$BaseUrl$jsPath"
                }
                Test-Url $jsUrl 200 "JS 资源 $jsPath 可访问"
            } else {
                Write-Host "[WARN] 未找到 JS 资源引用" -ForegroundColor Yellow
            }
        } catch {
            Write-Host "[ERROR] 无法获取 index.html: $_" -ForegroundColor Red
            $errors += "index.html 获取失败"
        }
    }
}

Write-Host ""
if ($errors.Count -eq 0) {
    Write-Host "=== All smoke tests passed ===" -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== $($errors.Count) test(s) failed ===" -ForegroundColor Red
    exit 1
}
