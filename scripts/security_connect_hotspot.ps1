# Connects the QA phone to a PC-hosted hotspot, then brings up the
# ConfigServer tunnel the red-team probe needs.
#
#   pwsh -File scripts/security_connect_hotspot.ps1 -Ssid "MY-HOTSPOT" -Password "12345678"
#
# Requires: USB debugging attached (device id is auto-detected).
param(
    [Parameter(Mandatory = $true)][string]$Ssid,
    [string]$Password = "",
    [string]$DeviceId = "",
    [int]$Port = 9527
)

$ErrorActionPreference = 'Stop'

if (-not $DeviceId) {
    $DeviceId = (adb devices | Select-String '\sdevice$' | Select-Object -First 1) -replace '\s.*', ''
}
if (-not $DeviceId) {
    throw "No device in 'device' state. Attach USB debugging first."
}
Write-Host "Device: $DeviceId"

# Prefer the open-network path; fall back to a saved profile when a password is given.
if ($Password) {
    Write-Host "Connecting to '$Ssid' with password..."
    # cmd -w wifi is not present on every vivo build; add-network is the reliable route.
    $netId = (adb -s $DeviceId shell cmd -w wifi list-networks | Select-String ([regex]::Escape($Ssid)) | Select-Object -First 1)
    if ($netId -and $netId -match '^(\d+)') {
        adb -s $DeviceId shell cmd -w wifi connect-network $Matches[1] open | Out-Null
    }
    else {
        adb -s $DeviceId shell cmd -w wifi connect-network $Ssid wpa2 "$Password" | Out-Null
    }
}
else {
    Write-Host "Connecting to open network '$Ssid'..."
    $id = adb -s $DeviceId shell cmd -w wifi list-networks |
        Select-String ([regex]::Escape($Ssid)) | Select-Object -First 1
    if ($id -and $id -match '^(\d+)') {
        adb -s $DeviceId shell cmd -w wifi connect-network $Matches[1] open | Out-Null
    }
    else {
        throw "Network '$Ssid' not in the device's scan results. Check the SSID spelling."
    }
}

Start-Sleep -Seconds 8

$state = adb -s $DeviceId shell dumpsys wifi | Select-String 'Wi-Fi is' | Select-Object -First 1
Write-Host "WiFi state: $state"

$ip = adb -s $DeviceId shell ip -4 addr show | Select-String 'inet ' |
    Where-Object { $_ -notmatch '127\.0\.0\.1' } | Select-Object -First 1
if (-not $ip) {
    throw "Phone still has no non-loopback IPv4 address. Hotspot not associated yet — wait and re-run."
}
Write-Host "Phone IPv4: $ip"

# The ConfigServer only starts from the Settings toggle and requires Wi-Fi,
# so the operator flips it in the UI after this script reports the IP.
Write-Host ""
Write-Host "Next: on the phone, Settings -> LAN Config -> enable."
Write-Host "Then run:  adb -s $DeviceId forward tcp:$Port tcp:$Port"
Write-Host "Then run:  python scripts/security_probe_configserver.py http://127.0.0.1:$Port"
