# 解析 JaCoCo CSV 并校验 instruction/branch 覆盖率门禁。
# 失败时返回非 0，便于 CI 与本地验证脚本链式调用。
param(
    [string]$JacocoCsv = "target/site/jacoco/jacoco.csv",
    [decimal]$InstructionThreshold = 0.90,
    [decimal]$BranchThreshold = 0.75
)

$ErrorActionPreference = "Stop"

function Write-Result($label, $actual, $threshold, $ok) {
    $status = if ($ok) { "PASS" } else { "FAIL" }
    $pct = "{0:P2}" -f $actual
    $req = "{0:P2}" -f $threshold
    Write-Host "[$status] $label`: $pct (required $req)"
}

if (-not (Test-Path $JacocoCsv)) {
    Write-Error "JaCoCo CSV not found: $JacocoCsv (run 'mvn verify' first)"
    exit 1
}

$rows = Import-Csv $JacocoCsv
if (-not $rows) {
    Write-Error "No coverage rows found in $JacocoCsv"
    exit 1
}

$instructionMissed = ($rows | Measure-Object -Property INSTRUCTION_MISSED -Sum).Sum
$instructionCovered = ($rows | Measure-Object -Property INSTRUCTION_COVERED -Sum).Sum
$branchMissed = ($rows | Measure-Object -Property BRANCH_MISSED -Sum).Sum
$branchCovered = ($rows | Measure-Object -Property BRANCH_COVERED -Sum).Sum

$instructionTotal = $instructionMissed + $instructionCovered
$branchTotal = $branchMissed + $branchCovered

$instructionRatio = if ($instructionTotal -eq 0) { 1 } else { $instructionCovered / $instructionTotal }
$branchRatio = if ($branchTotal -eq 0) { 1 } else { $branchCovered / $branchTotal }

$instructionOk = $instructionRatio -ge $InstructionThreshold
$branchOk = $branchRatio -ge $BranchThreshold

Write-Result -label "INSTRUCTION coverage" -actual $instructionRatio -threshold $InstructionThreshold -ok $instructionOk
Write-Result -label "BRANCH coverage" -actual $branchRatio -threshold $BranchThreshold -ok $branchOk

Write-Host "Raw counters: INSTRUCTION $instructionCovered/$instructionTotal, BRANCH $branchCovered/$branchTotal"

if (-not ($instructionOk -and $branchOk)) {
    exit 1
}

exit 0
