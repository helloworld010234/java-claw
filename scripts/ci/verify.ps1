# 本地一键 P3 质量门禁验证脚本。
# 顺序执行：mvn verify -> 覆盖率检查 -> P1 smoke -> git diff --check。
# 任一阶段失败均返回非 0，并打印 FAIL 摘要。

param(
    [switch]$SkipSmoke = $false
)

$ErrorActionPreference = "Stop"

$workspace = $PSScriptRoot | Split-Path -Parent | Split-Path -Parent
Set-Location $workspace

$stages = @(
    @{ Name = "Maven verify"; Key = "maven"; ScriptBlock = {
        & mvn -B -q clean verify
        if ($LASTEXITCODE -ne 0) { throw "mvn verify failed with exit code $LASTEXITCODE" }
    }},
    @{ Name = "Coverage gate"; Key = "coverage"; ScriptBlock = {
        & powershell -NoProfile -ExecutionPolicy Bypass -File scripts\ci\check-coverage.ps1
        if ($LASTEXITCODE -ne 0) { throw "Coverage gate failed" }
    }},
    @{ Name = "P1 smoke"; Key = "smoke"; ScriptBlock = {
        if ($SkipSmoke) {
            Write-Host "P1 smoke skipped by -SkipSmoke"
            return
        }
        & powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\p1-e2e-smoke.ps1
        if ($LASTEXITCODE -ne 0) { throw "P1 smoke failed with exit code $LASTEXITCODE" }
    }},
    @{ Name = "Git diff check"; Key = "gitdiff"; ScriptBlock = {
        & git diff --check
        if ($LASTEXITCODE -ne 0) { throw "git diff --check failed" }
        & git diff --cached --check
        if ($LASTEXITCODE -ne 0) { throw "git diff --cached --check failed" }
    }}
)

$results = @{}
foreach ($stage in $stages) {
    $results[$stage.Key] = "PENDING"
}

Write-Host "=== java-claw P3 Quality Gate ==="
Write-Host "Workspace: $workspace"
Write-Host ""

$failed = $false
foreach ($stage in $stages) {
    $name = $stage.Name
    $key = $stage.Key
    Write-Host "--> Running: $name"
    try {
        Invoke-Command -ScriptBlock $stage.ScriptBlock -NoNewScope
        $results[$key] = "PASS"
        Write-Host "    PASS: $name" -ForegroundColor Green
    } catch {
        $results[$key] = "FAIL"
        $failed = $true
        Write-Host "    FAIL: $name :: $($_.Exception.Message)" -ForegroundColor Red
    }
    Write-Host ""
}

Write-Host "=== Summary ==="
foreach ($stage in $stages) {
    $status = $results[$stage.Key]
    $color = if ($status -eq "PASS") { "Green" } elseif ($status -eq "FAIL") { "Red" } else { "Yellow" }
    Write-Host "$($status.PadRight(6))  $($stage.Name)" -ForegroundColor $color
}

if ($failed) {
    Write-Host ""
    Write-Host "OVERALL: FAIL" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "OVERALL: PASS" -ForegroundColor Green
exit 0
