# P1 端到端 smoke 自动化脚本
# 运行方式（PowerShell 5.1+ / PowerShell 7）:
#   cd D:\go-tiny-claw\java-claw
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\p1-e2e-smoke.ps1
#
# 该脚本执行后会在 .smoke/logs 保留日志，在 notes/session-logs 写入报告。
# 不修改业务逻辑；审批 ID 通过 RunStatusResponse.approvalId 公开字段获取。

param(
    [int]$WebPort = 18080,
    [int]$StubPort = 18081,
    [string]$ReportPath = "notes/session-logs/$(Get-Date -Format 'yyyy-MM-dd')-p1-e2e-smoke.md"
)

$ErrorActionPreference = "Stop"

$Env:no_proxy = "127.0.0.1,localhost"

$workspace = $PSScriptRoot | Split-Path -Parent | Split-Path -Parent
Set-Location $workspace

$smokeDir = Join-Path $workspace ".smoke"
$logsDir = Join-Path $smokeDir "logs"
$pidDir = Join-Path $smokeDir "pids"
$workspaceDir = Join-Path $smokeDir "workspace"
$chatopsWorkspaceDir = Join-Path $smokeDir "chatops-workspace"
$stubLog = Join-Path $logsDir "openai-stub.log"
$webLog = Join-Path $logsDir "web-server.log"
$webPidFile = Join-Path $pidDir "web.pid"
$stubPidFile = Join-Path $pidDir "stub.pid"
$fixturesDir = Join-Path $workspace "scripts/smoke/fixtures"

New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
New-Item -ItemType Directory -Force -Path $pidDir | Out-Null
New-Item -ItemType Directory -Force -Path $workspaceDir | Out-Null
New-Item -ItemType Directory -Force -Path $chatopsWorkspaceDir | Out-Null

function Write-Log($message) {
    $ts = Get-Date -Format "yyyy-MM-ddTHH:mm:ss.fff+08:00"
    $line = "[$ts] $message"
    Write-Host $line
    $line | Out-File -FilePath (Join-Path $logsDir "smoke.log") -Encoding utf8 -Append
}

function Test-Port($port) {
    $conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Where-Object { $_.State -eq "Listen" }
    return $conn -ne $null
}

function Get-PortOwner($port) {
    $conn = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | Where-Object { $_.State -eq "Listen" } | Select-Object -First 1
    if ($conn) {
        return $conn.OwningProcess
    }
    return $null
}

function Assert-PortFree($port) {
    $owner = Get-PortOwner $port
    if ($owner -ne $null) {
        throw "Port $port is already in use by process $owner; refusing to stop unknown process."
    }
}

function Stop-PidFile($pidFile) {
    # Only accept pid files inside our own .smoke/pids directory.
    $expectedDir = (Resolve-Path $pidDir).Path
    $resolved = if (Test-Path $pidFile) { (Resolve-Path $pidFile).Path } else { $pidFile }
    if (-not $resolved.StartsWith($expectedDir)) {
        throw "Refusing to stop pid from outside smoke pid directory: $pidFile"
    }
    if (Test-Path $pidFile) {
        $pidValue = Get-Content $pidFile -Raw
        if ($pidValue -match '^\d+$') {
            Stop-Process -Id ([int]$pidValue) -Force -ErrorAction SilentlyContinue
            Write-Log "Stopped process $pidValue from $pidFile"
        }
        Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    }
}

function Wait-Health($url, $timeoutSec = 60) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        try {
            $Env:no_proxy = "127.0.0.1,localhost"
            $raw = curl.exe -s -m 5 $url
            $resp = $raw | ConvertFrom-Json -ErrorAction Stop
            if ($resp.status -eq "UP" -or $resp.status -eq "ok") { return $true }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Invoke-CurlJson($method, $url, $headers, $body) {
    $h = @{}
    if ($headers) {
        foreach ($kv in $headers.GetEnumerator()) { $h[$kv.Key] = $kv.Value }
    }
    try {
        $resp = Invoke-WebRequest -Uri $url -Method $method -Headers $h -Body $body -ContentType "application/json" -UseBasicParsing
        return @{ StatusCode = $resp.StatusCode; Body = $resp.Content }
    } catch [System.Net.WebException] {
        $statusCode = [int]$_.Exception.Response.StatusCode
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $respBody = $reader.ReadToEnd()
        $reader.Close()
        return @{ StatusCode = $statusCode; Body = $respBody }
    }
}

function Get-Safe($value, $fallback = "<missing>") {
    if ($value -eq $null) { return $fallback }
    $s = "$value"
    if ($s.Trim().Length -eq 0) { return $fallback }
    return $s
}

function Mask-Secret($text) {
    $masked = "$text"
    $masked = $masked -replace 'smoke-key', '***'
    $masked = $masked -replace 'smoke-admin', '***'
    $masked = $masked -replace 'smoke-user', '***'
    $masked = $masked -replace 'tk-smoke', '***'
    return $masked
}

$results = @{
    cliExit = $null
    cliSuccess = $false
    webRunId = $null
    webCreateStatus = $null
    webFinalStatus = $null
    webSuccess = $false
    approvalRunId = $null
    approvalId = $null
    approvalWaitingStatus = $null
    approvalActionStatus = $null
    approvalReapproveStatus = $null
    approvalFileOk = $false
    approvalSuccess = $false
    chatopsUrlVerStatus = $null
    chatopsUrlVerBody = $null
    chatopsEventStatus = $null
    chatopsEventBody = $null
    chatopsBadStatus = $null
    chatopsBadBody = $null
    chatopsLeaks = $false
    chatopsOutbound = $false
    chatopsSuccess = $false
    errors = New-Object System.Collections.Generic.List[string]
}

$webProc = $null
$stubProc = $null

Write-Log "=== P1 E2E Smoke Start ==="

try {
    # 1. Pre-cleanup: stop only leftover smoke processes recorded by pid files.
    # If a port is occupied by an unknown process, fail immediately rather than killing it.
    Stop-PidFile $webPidFile
    Stop-PidFile $stubPidFile
    Assert-PortFree $WebPort
    Assert-PortFree $StubPort
    if (Test-Path $workspaceDir) {
        Remove-Item -Path (Join-Path $workspaceDir "*") -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path $chatopsWorkspaceDir) {
        Remove-Item -Path (Join-Path $chatopsWorkspaceDir "*") -Recurse -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 1

    # 2. Build jar
    Write-Log "Building jar..."
    & mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

    # 3. Start OpenAI stub
    Write-Log "Starting OpenAI stub on port $StubPort..."
    $stubErrLog = Join-Path $logsDir "openai-stub.err.log"
    $stubOutLog = Join-Path $logsDir "openai-stub.stdout.log"
    $stubProc = Start-Process -FilePath "powershell" -ArgumentList @(
        "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
        (Join-Path $workspace "scripts/smoke/openai-stub.ps1"),
        "-Port", $StubPort,
        "-WorkspaceRoot", $workspace
    ) -RedirectStandardOutput $stubOutLog -RedirectStandardError $stubErrLog -PassThru -WindowStyle Hidden
    $stubProc.Id | Out-File $stubPidFile -Encoding utf8 -NoNewline
    Write-Log "Stub PID: $($stubProc.Id)"

    if (-not (Wait-Health "http://127.0.0.1:$StubPort/" -timeoutSec 15)) {
        throw "Stub did not start"
    }
    # Do NOT use Get-PortOwner for the stub: HttpListener on Windows uses HTTP.sys,
    # so the port appears to be owned by PID 4 (System). The pid file keeps $stubProc.Id.

    # 4. Start Web server
    Write-Log "Starting Web server on port $WebPort..."
    $webErrLog = Join-Path $logsDir "web-server.err.log"
    $webProc = Start-Process -FilePath "java" -ArgumentList @(
        "-jar", "target/java-claw-0.0.1-SNAPSHOT.jar",
        "--spring.profiles.active=test",
        "--server.port=$WebPort",
        "--tiny-claw.model.enabled=true",
        "--tiny-claw.model.api-key=smoke-key",
        "--tiny-claw.model.base-url=http://127.0.0.1:$StubPort",
        "--tiny-claw.model.max-retry-attempts=0",
        "--tiny-claw.security.api-key.user-key=smoke-user",
        "--tiny-claw.security.api-key.admin-key=smoke-admin",
        "--tiny-claw.chatops.enabled=true",
        "--tiny-claw.chatops.verify-token=tk-smoke",
        "--tiny-claw.chatops.workspace=.smoke/chatops-workspace",
        "--tiny-claw.workspace.allowed-ids=default,.smoke"
    ) -RedirectStandardOutput $webLog -RedirectStandardError $webErrLog -PassThru -WindowStyle Hidden
    $webProc.Id | Out-File $webPidFile -Encoding utf8 -NoNewline
    Write-Log "Web server PID: $($webProc.Id)"

    if (-not (Wait-Health "http://127.0.0.1:$WebPort/actuator/health" -timeoutSec 60)) {
        throw "Web server did not start"
    }
    # Spring Boot executable jar may spawn a separate JVM listener process.
    # Record the actual listener PID so cleanup stops the real server, not just a launcher.
    # Reject PID 0/4 because those are kernel/system PIDs, not our JVM.
    $webListenerPid = Get-PortOwner $WebPort
    if ($webListenerPid -ne $null -and $webListenerPid -gt 4) {
        $webListenerPid | Out-File $webPidFile -Encoding utf8 -NoNewline
    }

    # 5. CLI fake run
    Write-Log "Running CLI fake run..."
    $cliLog = Join-Path $logsDir "cli-fake-run.log"
    $cliErrLog = Join-Path $logsDir "cli-fake-run.err.log"
    $cliProc = Start-Process -FilePath "java" -ArgumentList @(
        "-jar", "target/java-claw-0.0.1-SNAPSHOT.jar", "run",
        "--engine", "fake",
        "--prompt", '"hello cli smoke"',
        "--dir", ".",
        "--session", "smoke-cli",
        "--spring.profiles.active=test",
        "--spring.main.web-application-type=none"
    ) -RedirectStandardOutput $cliLog -RedirectStandardError $cliErrLog -PassThru -Wait
    $results.cliExit = $cliProc.ExitCode
    $cliOutput = (Get-Content $cliLog -Raw -ErrorAction SilentlyContinue) + "`n" + (Get-Content $cliErrLog -Raw -ErrorAction SilentlyContinue)
    $results.cliSuccess = ($results.cliExit -eq 0) -and ($cliOutput -match "status:\s*success")
    Write-Log "CLI exit code: $($results.cliExit); success: $($results.cliSuccess)"
    if (-not $results.cliSuccess) {
        $results.errors.Add("CLI fake run failed")
    }

    # 6. Web run
    Write-Log "Running Web smoke run..."
    $webCreate = Invoke-CurlJson -method POST -url "http://127.0.0.1:$WebPort/api/v1/runs" `
        -headers @{ "X-API-Key" = "smoke-user" } `
        -body '{"sessionId":"smoke-web","prompt":"hello web smoke","workDir":".smoke/workspace","maxTurns":3}'
    $results.webCreateStatus = $webCreate.StatusCode
    $webCreateJson = $webCreate.Body | ConvertFrom-Json
    $results.webRunId = $webCreateJson.runId
    Write-Log "Web create status: $($results.webCreateStatus); runId: $($results.webRunId)"

    $webFinal = $null
    for ($i = 0; $i -lt 30; $i++) {
        $r = Invoke-CurlJson -method GET -url "http://127.0.0.1:$WebPort/api/v1/runs/$($results.webRunId)" `
            -headers @{ "X-API-Key" = "smoke-user" } -body $null
        $webFinal = $r.Body | ConvertFrom-Json
        Write-Log "Web run poll $i : $($webFinal.status)"
        if ($webFinal.status -eq "COMPLETED") { break }
        Start-Sleep -Seconds 1
    }
    $results.webFinalStatus = $webFinal.status
    $results.webSuccess = ($results.webCreateStatus -eq 202) -and ($results.webFinalStatus -eq "COMPLETED")
    if (-not $results.webSuccess) {
        $results.errors.Add("Web run failed")
    }

    # 7. Approval run
    Write-Log "Running approval smoke run..."
    $approvalCreate = Invoke-CurlJson -method POST -url "http://127.0.0.1:$WebPort/api/v1/runs" `
        -headers @{ "X-API-Key" = "smoke-user" } `
        -body '{"sessionId":"smoke-approval","prompt":"approval smoke create file","workDir":".smoke/workspace","maxTurns":3}'
    $approvalCreateJson = $approvalCreate.Body | ConvertFrom-Json
    $results.approvalRunId = $approvalCreateJson.runId
    Write-Log "Approval create status: $($approvalCreate.StatusCode); runId: $($results.approvalRunId)"

    $approvalWaiting = $null
    for ($i = 0; $i -lt 30; $i++) {
        $r = Invoke-CurlJson -method GET -url "http://127.0.0.1:$WebPort/api/v1/runs/$($results.approvalRunId)" `
            -headers @{ "X-API-Key" = "smoke-user" } -body $null
        $approvalWaiting = $r.Body | ConvertFrom-Json
        Write-Log "Approval run poll $i : $($approvalWaiting.status)"
        if ($approvalWaiting.status -eq "WAITING_APPROVAL") { break }
        Start-Sleep -Seconds 1
    }
    $results.approvalWaitingStatus = $approvalWaiting.status
    $results.approvalId = $approvalWaiting.approvalId
    Write-Log "Approval waiting status: $($results.approvalWaitingStatus); approvalId: $($results.approvalId)"

    if (-not $results.approvalId) {
        $results.errors.Add("Approval ID not returned by RunStatusResponse")
    }

    $approvalAction = Invoke-CurlJson -method POST -url "http://127.0.0.1:$WebPort/api/v1/approvals/$($results.approvalId)/action" `
        -headers @{ "X-API-Key" = "smoke-admin" } `
        -body '{"action":"approve","reason":"P1 smoke approval"}'
    $results.approvalActionStatus = $approvalAction.StatusCode
    Write-Log "Approval action status: $($results.approvalActionStatus)"

    $approvalFinal = $null
    for ($i = 0; $i -lt 30; $i++) {
        $r = Invoke-CurlJson -method GET -url "http://127.0.0.1:$WebPort/api/v1/runs/$($results.approvalRunId)" `
            -headers @{ "X-API-Key" = "smoke-user" } -body $null
        $approvalFinal = $r.Body | ConvertFrom-Json
        Write-Log "Approval final poll $i : $($approvalFinal.status)"
        if ($approvalFinal.status -eq "COMPLETED" -or $approvalFinal.status -eq "FAILED") { break }
        Start-Sleep -Seconds 1
    }

    $approvalReapprove = Invoke-CurlJson -method POST -url "http://127.0.0.1:$WebPort/api/v1/approvals/$($results.approvalId)/action" `
        -headers @{ "X-API-Key" = "smoke-admin" } `
        -body '{"action":"approve","reason":"P1 smoke approval again"}'
    $results.approvalReapproveStatus = $approvalReapprove.StatusCode
    Write-Log "Re-approval status: $($results.approvalReapproveStatus)"

    $approvedFile = Join-Path $workspaceDir "approved-smoke.txt"
    $approvedContent = if (Test-Path $approvedFile) { (Get-Content $approvedFile -Raw).Trim() } else { "<missing>" }
    $results.approvalFileOk = ($approvedContent -eq "approved by smoke")
    Write-Log "Approved file content: $approvedContent"

    $results.approvalSuccess = ($results.approvalWaitingStatus -eq "WAITING_APPROVAL") `
        -and ($results.approvalActionStatus -eq 200) `
        -and ($results.approvalReapproveStatus -eq 409) `
        -and ($results.approvalFileOk) `
        -and ($approvalFinal.status -eq "COMPLETED")
    if (-not $results.approvalSuccess) {
        $results.errors.Add("Approval flow failed")
    }

    # 8. ChatOps webhook
    Write-Log "Running ChatOps webhook smoke..."
    $chatopsUrlVer = Invoke-CurlJson -method POST -url "http://127.0.0.1:$WebPort/webhook/feishu/event" `
        -headers @{} `
        -body '{"token":"tk-smoke","type":"url_verification","challenge":{"challenge":"ch-smoke-123","token":"tk-smoke"}}'
    $results.chatopsUrlVerStatus = $chatopsUrlVer.StatusCode
    $results.chatopsUrlVerBody = $chatopsUrlVer.Body
    Write-Log "URL verification status: $($results.chatopsUrlVerStatus)"

    $chatopsEvent = Invoke-CurlJson -method POST -url "http://127.0.0.1:$WebPort/webhook/feishu/event" `
        -headers @{} `
        -body (Get-Content (Join-Path $fixturesDir "chatops-event.json") -Raw)
    $results.chatopsEventStatus = $chatopsEvent.StatusCode
    $results.chatopsEventBody = $chatopsEvent.Body
    Write-Log "ChatOps event status: $($results.chatopsEventStatus)"

    $chatopsBad = Invoke-CurlJson -method POST -url "http://127.0.0.1:$WebPort/webhook/feishu/event" `
        -headers @{} `
        -body (Get-Content (Join-Path $fixturesDir "chatops-event-bad-token.json") -Raw)
    $results.chatopsBadStatus = $chatopsBad.StatusCode
    $results.chatopsBadBody = $chatopsBad.Body
    Write-Log "Bad token status: $($results.chatopsBadStatus)"

    $leakPatterns = @("tk-smoke", "smoke-key", "smoke-admin", "smoke-user", "tenant_access_token", "Authorization:")
    $logLeaks = Select-String -Path @($webLog, $webErrLog) -Pattern ($leakPatterns -join "|") | Where-Object {
        $_.Line -notmatch "AuthorizationFilter"
    }
    $results.chatopsLeaks = $logLeaks -ne $null
    $outboundFailure = Select-String -Path @($webLog, $webErrLog) -Pattern "open\.feishu\.cn|open\.larksuite\.com|9999166|Feishu API error" -ErrorAction SilentlyContinue
    $results.chatopsOutbound = $outboundFailure -ne $null

    $results.chatopsSuccess = ($results.chatopsUrlVerStatus -eq 200) `
        -and ($results.chatopsEventStatus -eq 200) `
        -and ($results.chatopsBadStatus -eq 401) `
        -and (-not $results.chatopsLeaks) `
        -and (-not $results.chatopsOutbound)
    if (-not $results.chatopsSuccess) {
        $results.errors.Add("ChatOps webhook smoke failed")
    }

} catch {
    $msg = $_.Exception.Message
    Write-Log "ERROR: $msg"
    $results.errors.Add($msg)
} finally {
    # 9. Cleanup
    Write-Log "Stopping background processes..."
    if ($webProc -ne $null) {
        Stop-Process -Id $webProc.Id -Force -ErrorAction SilentlyContinue
    }
    if ($stubProc -ne $null) {
        Stop-Process -Id $stubProc.Id -Force -ErrorAction SilentlyContinue
    }
    Stop-PidFile $webPidFile
    Stop-PidFile $stubPidFile
}

# 10. Generate report using structured lines to avoid here-string encoding issues
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# P1 端到端 Smoke 报告")
$lines.Add("")
$lines.Add("- 执行时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')")
$lines.Add("- 工作目录: $workspace")
$lines.Add("- Java: $(cmd /c 'java -version 2>&1' | Select-Object -First 1)")
$lines.Add("- Maven: $(cmd /c 'mvn -version 2>&1' | Select-Object -First 1)")
$lines.Add("- Git branch: $(git branch --show-current 2>$null)")
$lines.Add("- Git commit: $(git describe --always --dirty 2>$null)")
$lines.Add("")
$lines.Add("## 汇总结论")
$lines.Add("")
$lines.Add("| 路径 | 结论 | 关键证据 |")
$lines.Add("|---|---|---|")

$cliResult = if ($results.cliSuccess) { "通过" } else { "失败" }
$webResult = if ($results.webSuccess) { "通过" } else { "失败" }
$approvalResult = if ($results.approvalSuccess) { "通过" } else { "失败" }
$chatopsResult = if ($results.chatopsSuccess) { "通过" } else { "失败" }

$lines.Add("| CLI fake run | $cliResult | 退出码 $($results.cliExit)，输出包含 status: success |")
$lines.Add("| Web run | $webResult | HTTP $($results.webCreateStatus)，runId $(Get-Safe $results.webRunId), 最终 $(Get-Safe $results.webFinalStatus) |")
$lines.Add("| 审批 pause/approve/resume | $approvalResult | WAITING_APPROVAL -> approve HTTP $($results.approvalActionStatus) -> 重复 approve HTTP $($results.approvalReapproveStatus)，文件校验 $($results.approvalFileOk) |")
$lines.Add("| ChatOps webhook | $chatopsResult | URL verification HTTP $($results.chatopsUrlVerStatus)，事件 HTTP $($results.chatopsEventStatus)，错误 token HTTP $($results.chatopsBadStatus)，日志泄露 $($results.chatopsLeaks)，真实出网 $($results.chatopsOutbound) |")
$lines.Add("")

$lines.Add("## 1. CLI fake run")
$lines.Add("")
$lines.Add("- 退出码: $($results.cliExit)")
$lines.Add("- 结论: $cliResult")
$lines.Add("")

$lines.Add("## 2. Web run")
$lines.Add("")
$lines.Add("- runId: $(Get-Safe $results.webRunId)")
$lines.Add("- 创建响应 HTTP 状态码: $($results.webCreateStatus)")
$lines.Add("- 最终状态: $(Get-Safe $results.webFinalStatus)")
$lines.Add("- 结论: $webResult")
$lines.Add("")

$lines.Add("## 3. 审批 pause / approve / resume")
$lines.Add("")
$lines.Add("- runId: $(Get-Safe $results.approvalRunId)")
$lines.Add("- approvalId: $(Get-Safe $results.approvalId) (从 RunStatusResponse.approvalId 获取)")
$lines.Add("- 等待审批状态: $(Get-Safe $results.approvalWaitingStatus)")
$lines.Add("- approve HTTP 状态码: $($results.approvalActionStatus)")
$lines.Add("- 重复 approve HTTP 状态码: $($results.approvalReapproveStatus)")
$lines.Add("- 生成文件: $approvedFile")
$lines.Add("- 文件内容: $approvedContent")
$lines.Add("- 结论: $approvalResult")
$lines.Add("")

$lines.Add("## 4. ChatOps webhook")
$lines.Add("")
$lines.Add("- URL verification 状态码: $($results.chatopsUrlVerStatus); 响应: $(Mask-Secret (Get-Safe $results.chatopsUrlVerBody))")
$lines.Add("- 合法事件状态码: $($results.chatopsEventStatus); 响应: $(Mask-Secret (Get-Safe $results.chatopsEventBody))")
$lines.Add("- 错误 token 状态码: $($results.chatopsBadStatus); 响应: $(Mask-Secret (Get-Safe $results.chatopsBadBody))")
$lines.Add("- 日志泄露敏感值: $(if ($results.chatopsLeaks) { '发现' } else { '未发现' })")
$lines.Add("- 真实飞书出网失败: $(if ($results.chatopsOutbound) { '发现' } else { '未发现' })")
$lines.Add("- 结论: $chatopsResult")
$lines.Add("")

$lines.Add("## 5. 失败项与后续建议")
$lines.Add("")
if ($results.errors.Count -eq 0) {
    $lines.Add("- 无")
} else {
    foreach ($err in $results.errors) {
        $lines.Add("- $err")
    }
}
$lines.Add("")

$lines.Add("## 6. 运行方式")
$lines.Add("")
$lines.Add("``````powershell")
$lines.Add("powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke\p1-e2e-smoke.ps1")
$lines.Add("``````")
$lines.Add("")
$lines.Add("脚本会自动构建 jar、启动 stub 与 Web server、执行四条路径、写入报告并清理进程。")

$reportDir = Split-Path -Parent $ReportPath
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
if (Test-Path $ReportPath) {
    Remove-Item $ReportPath -Force
}
foreach ($line in $lines) {
    Add-Content -Path $ReportPath -Value $line -Encoding utf8
}

Write-Log "Report written to $ReportPath"
Write-Log "=== P1 E2E Smoke Done ==="

if ($results.cliSuccess -and $results.webSuccess -and $results.approvalSuccess -and $results.chatopsSuccess) {
    exit 0
} else {
    exit 1
}
