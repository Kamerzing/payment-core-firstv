$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $projectDirectory '.env'

if (Test-Path -LiteralPath $environmentFile) {
	Write-Output 'Existing .env preserved.'
	exit 0
}

$randomBytes = New-Object byte[] 32
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
	$generator.GetBytes($randomBytes)
} finally {
	$generator.Dispose()
}
$databasePassword = [BitConverter]::ToString($randomBytes).Replace('-', '').ToLowerInvariant()
$template = [System.IO.File]::ReadAllText((Join-Path $projectDirectory '.env.example'))
$settings = $template.Replace('POSTGRES_PASSWORD=', "POSTGRES_PASSWORD=$databasePassword")
[System.IO.File]::WriteAllText($environmentFile, $settings, (New-Object System.Text.UTF8Encoding($false)))
Write-Output 'Created .env with a random local database password. Do not commit this file.'
