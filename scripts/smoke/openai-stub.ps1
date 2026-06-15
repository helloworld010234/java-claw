# OpenAI-compatible HTTP stub for java-claw P1 e2e smoke.
# Listens on $Port (default 18081).
# Returns responses based on the content of the request messages, not a global call counter,
# so health checks or extra ChatOps calls do not break the smoke sequence.
param(
    [int]$Port = 18081,
    [string]$WorkspaceRoot = ""
)

$ErrorActionPreference = "Stop"

if ($WorkspaceRoot -eq "") {
    # scripts/smoke/openai-stub.ps1 -> scripts/smoke -> scripts -> repo root
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $WorkspaceRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)
}
$smokeDir = Join-Path $WorkspaceRoot ".smoke"
$logsDir = Join-Path $smokeDir "logs"
$logPath = Join-Path $logsDir "openai-stub.log"

New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

$listener = New-Object System.Net.HttpListener
$prefix = "http://127.0.0.1:$Port/"
$listener.Prefixes.Add($prefix)
$listener.Start()

function Write-StubLog($message) {
    $ts = Get-Date -Format "yyyy-MM-ddTHH:mm:ss.fff+08:00"
    "[$ts] $message" | Out-File -FilePath $logPath -Encoding utf8 -Append
}

Write-StubLog "Stub listening on $prefix"

function Send-Response($context, $statusCode, $body) {
    $buffer = [System.Text.Encoding]::UTF8.GetBytes($body)
    $context.Response.StatusCode = $statusCode
    $context.Response.ContentType = "application/json"
    $context.Response.OutputStream.Write($buffer, 0, $buffer.Length)
    $context.Response.Close()
}

function Build-ContentResponse($content) {
    $id = "chatcmpl-stub-$([Guid]::NewGuid().ToString().Substring(0, 8))"
    return @"
{
  "id": "$id",
  "object": "chat.completion",
  "created": 1700000000,
  "model": "stub-model",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "$content"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}
}
"@
}

function Build-ToolCallResponse($toolCallId, $toolName, $argumentsJson) {
    $id = "chatcmpl-stub-$([Guid]::NewGuid().ToString().Substring(0, 8))"
    $argumentsString = $argumentsJson | ConvertTo-Json -Compress
    return @"
{
  "id": "$id",
  "object": "chat.completion",
  "created": 1700000000,
  "model": "stub-model",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "$toolCallId",
            "type": "function",
            "function": {
              "name": "$toolName",
              "arguments": $argumentsString
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30}
}
"@
}

try {
    while ($listener.IsListening) {
        $ctx = $listener.GetContext()
        $req = $ctx.Request
        $method = $req.HttpMethod
        $path = $req.Url.PathAndQuery

        $reader = New-Object System.IO.StreamReader($req.InputStream)
        $body = $reader.ReadToEnd()
        $reader.Close()

        Write-StubLog "$method $path"

        if ($method -eq "GET" -and $path -eq "/") {
            Send-Response $ctx 200 '{"status":"ok"}'
            continue
        }

        if ($path -notmatch "chat/completions") {
            Send-Response $ctx 404 '{"error":"not found"}'
            continue
        }

        $messagesText = ""
        $hasToolObservation = $false
        try {
            $parsed = $body | ConvertFrom-Json
            if ($parsed.messages) {
                $messageParts = @()
                foreach ($msg in $parsed.messages) {
                    $role = $msg.role
                    $content = $msg.content
                    if ($role -eq "tool" -or $content -match "Wrote file|Tool result|tool_observation") {
                        $hasToolObservation = $true
                    }
                    $text = if ($content -is [string]) { $content } else { $content | ConvertTo-Json -Compress }
                    $messageParts += "$role`: $text"
                }
                $messagesText = $messageParts -join " | "
            }
        } catch {
            Write-StubLog "Failed to parse request body: $($_.Exception.Message)"
        }

        Write-StubLog "messages text: $messagesText"
        Write-StubLog "has tool observation: $hasToolObservation"

        $promptLower = $messagesText.ToLower()

        if ($promptLower -match "approval smoke create file" -and -not $hasToolObservation) {
            $json = Build-ToolCallResponse "call_smoke_001" "write_file" '{"path":"approved-smoke.txt","content":"approved by smoke"}'
        } elseif ($promptLower -match "hello web smoke") {
            $json = Build-ContentResponse "web smoke completed"
        } elseif ($promptLower -match "hello chatops smoke") {
            $json = Build-ContentResponse "chatops smoke completed"
        } elseif ($hasToolObservation -or $promptLower -match "approval smoke") {
            $json = Build-ContentResponse "approval smoke completed"
        } else {
            $json = Build-ContentResponse "smoke completed"
        }

        Send-Response $ctx 200 $json
    }
} finally {
    $listener.Stop()
    $listener.Close()
    Write-StubLog "Stub stopped"
}
