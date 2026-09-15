param(
    [string]$VmHost = "192.168.88.131",
    [string]$VmUser = "root"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    Write-Error "Windows SSH client is unavailable. Install OpenSSH Client first."
    exit 1
}

$remoteCommand = "cd /root/AIMeeting && docker compose up -d && (docker start ai-meeting-frontend 2>/dev/null || true)"

Write-Host "Starting AI Meeting on $VmUser@$VmHost ..."
& ssh "$VmUser@$VmHost" $remoteCommand

if ($LASTEXITCODE -ne 0) {
    Write-Error "Remote startup failed. Check the VMware virtual machine and the SSH password."
    exit $LASTEXITCODE
}

Start-Process "http://localhost:8080"
