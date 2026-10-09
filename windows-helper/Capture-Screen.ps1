param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^COM[0-9]+$')]
    [string]$PortName
)

$ErrorActionPreference = 'Stop'

function Read-ExactBytes([System.IO.Ports.SerialPort]$Port, [int]$Count) {
    [byte[]]$buffer = New-Object byte[] $Count
    $offset = 0
    while($offset -lt $Count) {
        $read = $Port.Read($buffer, $offset, $Count - $offset)
        if($read -le 0) { throw 'Serial stream ended before the complete message arrived.' }
        $offset += $read
    }
    return ,$buffer
}

function Get-Crc32([byte[]]$Bytes) {
    [long]$crc = 0xFFFFFFFFL
    foreach($byte in $Bytes) {
        $crc = $crc -bxor [long]$byte
        for($bit = 0; $bit -lt 8; $bit++) {
            if(($crc -band 1) -ne 0) {
                $crc = ($crc -shr 1) -bxor 0xEDB88320L
            } else {
                $crc = $crc -shr 1
            }
        }
    }
    return [uint32](($crc -bxor 0xFFFFFFFFL) -band 0xFFFFFFFFL)
}

$port = [System.IO.Ports.SerialPort]::new($PortName, 115200, [System.IO.Ports.Parity]::None, 8, [System.IO.Ports.StopBits]::One)
$port.ReadTimeout = 120000
$port.WriteTimeout = 10000
$bitmap = $null
$graphics = $null
$stream = $null

try {
    $port.Open()
    Write-Host "Waiting for Flipper request on $PortName. Capture will include the currently visible desktop."
    $ready = [System.Text.Encoding]::ASCII.GetString((Read-ExactBytes $port 4))
    if($ready -ne 'FBA1') { throw "Unexpected Flipper marker '$ready'. Stop and inspect the bridge status." }

    Add-Type -AssemblyName System.Drawing
    Add-Type -AssemblyName System.Windows.Forms
    $bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
    if($bounds.Width -le 0 -or $bounds.Height -le 0) { throw 'Windows reported no visible desktop bounds.' }

    $bitmap = [System.Drawing.Bitmap]::new($bounds.Width, $bounds.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.CopyFromScreen($bounds.Left, $bounds.Top, 0, 0, $bitmap.Size)
    $stream = [System.IO.MemoryStream]::new()
    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    [byte[]]$png = $stream.ToArray()
    if($png.Length -lt 8 -or $png.Length -gt 2097152) {
        throw "PNG size $($png.Length) is outside the bridge limit of 1..2097152 bytes. No image was sent."
    }

    [byte[]]$header = New-Object byte[] 12
    [System.Text.Encoding]::ASCII.GetBytes('FBR1').CopyTo($header, 0)
    [BitConverter]::GetBytes([uint32]$png.Length).CopyTo($header, 4)
    [BitConverter]::GetBytes((Get-Crc32 $png)).CopyTo($header, 8)
    $port.Write($header, 0, $header.Length)

    $offset = 0
    while($offset -lt $png.Length) {
        $credit = [System.Text.Encoding]::ASCII.GetString((Read-ExactBytes $port 4))
        if($credit -eq 'FBE1') { throw 'Flipper rejected or cancelled the screenshot transfer.' }
        if($credit -ne 'FBC1') { throw "Unexpected Flipper flow marker '$credit'." }
        $count = [Math]::Min(200, $png.Length - $offset)
        $port.Write($png, $offset, $count)
        $offset += $count
        Write-Progress -Activity 'Sending visible desktop PNG' -Status "$offset / $($png.Length) bytes" -PercentComplete (($offset * 100) / $png.Length)
    }

    $complete = [System.Text.Encoding]::ASCII.GetString((Read-ExactBytes $port 4))
    if($complete -ne 'FBA2') { throw "Flipper did not confirm completion (marker '$complete')." }
    Write-Progress -Activity 'Sending visible desktop PNG' -Completed
    Write-Host "Screenshot sent ($($png.Length) bytes). The temporary image stayed in memory."
} finally {
    if($stream) { $stream.Dispose() }
    if($graphics) { $graphics.Dispose() }
    if($bitmap) { $bitmap.Dispose() }
    if($port.IsOpen) { $port.Close() }
    $port.Dispose()
}
