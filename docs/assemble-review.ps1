$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$header = Get-Content -LiteralPath (Join-Path $root 'docs\ai-review-header.md') -Raw -Encoding UTF8

$files = @(
  'src/main/java/com/pokeemc/client/ExchangeScreen.java',
  'src/main/java/com/pokeemc/client/ExchangeUiModel.java',
  'src/main/java/com/pokeemc/client/StorageViewModel.java',
  'src/main/java/com/pokeemc/client/StorageBrowserScreen.java',
  'src/main/java/com/pokeemc/client/PeStyle.java',
  'src/main/java/com/pokeemc/client/BrowserHost.java',
  'src/main/java/com/pokeemc/client/ExchangeCatalogHost.java',
  'src/main/java/com/pokeemc/menu/ExchangeMenu.java',
  'src/main/java/com/pokeemc/menu/StorageBrowserMenu.java',
  'src/main/java/com/pokeemc/storage/StorageServices.java',
  'src/main/java/com/pokeemc/storage/StorageAccessService.java',
  'src/main/java/com/pokeemc/storage/StorageSavedData.java',
  'src/main/java/com/pokeemc/storage/StorageRecord.java',
  'src/main/java/com/pokeemc/storage/StorageKey.java',
  'src/main/java/com/pokeemc/storage/StoragePermission.java',
  'src/main/java/com/pokeemc/storage/StorageTransactionService.java',
  'src/main/java/com/pokeemc/storage/StorageCommands.java',
  'src/main/java/com/pokeemc/storage/adapter/AbstractContainerAdapter.java',
  'src/main/java/com/pokeemc/storage/adapter/VanillaChestAdapter.java',
  'src/main/java/com/pokeemc/storage/adapter/VanillaDoubleChestAdapter.java',
  'src/main/java/com/pokeemc/storage/adapter/VanillaTrappedChestAdapter.java',
  'src/main/java/com/pokeemc/storage/adapter/VanillaBarrelAdapter.java',
  'src/main/java/com/pokeemc/storage/adapter/VanillaEnderChestAdapter.java',
  'src/main/java/com/pokeemc/storage/adapter/StorageHandleImpl.java',
  'src/main/java/com/pokeemc/storage/adapter/StorageHandleExt.java',
  'src/main/java/com/pokeemc/storage/adapter/StorageAdapterRegistryImpl.java',
  'src/main/java/com/pokeemc/storage/discovery/StorageDiscoveryService.java',
  'src/main/java/com/pokeemc/storage/discovery/StorageConfig.java',
  'src/main/java/com/pokeemc/network/ExchangeBuyPacket.java',
  'src/main/java/com/pokeemc/network/ExchangeSellPacket.java',
  'src/main/java/com/pokeemc/network/ExchangeCatalogPacket.java',
  'src/main/java/com/pokeemc/network/QueryStoragesPacket.java',
  'src/main/java/com/pokeemc/network/StorageDepositPacket.java',
  'src/main/java/com/pokeemc/network/StorageDepositCarriedPacket.java',
  'src/main/java/com/pokeemc/network/StorageWithdrawCarriedPacket.java',
  'src/main/java/com/pokeemc/network/StorageMovePacket.java',
  'src/main/java/com/pokeemc/network/StorageTransferPacket.java',
  'src/main/java/com/pokeemc/network/StorageSellPacket.java',
  'src/main/java/com/pokeemc/network/StorageSnapshotPacket.java',
  'src/main/java/com/pokeemc/network/StorageManagePacket.java',
  'src/main/java/com/pokeemc/network/ModNetwork.java',
  'poketrade-api/src/main/java/com/poketrade/api/storage/StorageAdapter.java',
  'poketrade-api/src/main/java/com/poketrade/api/storage/StorageAdapterContext.java',
  'poketrade-api/src/main/java/com/poketrade/api/storage/StorageHandle.java',
  'poketrade-api/src/main/java/com/poketrade/api/storage/StorageId.java',
  'poketrade-api/src/main/java/com/poketrade/api/storage/StorageSnapshot.java',
  'poketrade-api/src/main/java/com/poketrade/api/storage/StorageItemSlot.java',
  'poketrade-api/src/main/java/com/poketrade/api/storage/StorageDescriptor.java',
  'poketrade-api/src/main/java/com/poketrade/api/storage/StorageQuery.java',
  'poketrade-api/src/main/java/com/poketrade/api/storage/StorageEndpoint.java',
  'poketrade-api/src/main/java/com/poketrade/api/storage/StorageCapability.java',
  'src/test/java/com/pokeemc/client/ExchangeUiModelTest.java',
  'src/main/java/com/pokeemc/storage/discovery/StorageDiscoveryGameTests.java',
  'src/main/java/com/pokeemc/network/StoragePacketGameTests.java',
  'src/main/resources/assets/poketrade/lang/zh_cn.json',
  'src/main/resources/assets/poketrade/lang/en_us.json'
)

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine($header)
$missing = New-Object System.Collections.Generic.List[string]
foreach ($rel in $files) {
  $path = Join-Path $root $rel
  if (-not (Test-Path -LiteralPath $path)) {
    $missing.Add($rel)
    continue
  }
  $ext = [System.IO.Path]::GetExtension($rel).TrimStart('.')
  $content = Get-Content -LiteralPath $path -Raw -Encoding UTF8
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine("## FILE: $rel")
  [void]$sb.AppendLine('')
  [void]$sb.AppendLine('```' + $ext)
  [void]$sb.AppendLine($content.TrimEnd("`r", "`n"))
  [void]$sb.AppendLine('```')
}
$out = Join-Path $root 'docs\ai-review.md'
[System.IO.File]::WriteAllText($out, $sb.ToString(), [System.Text.UTF8Encoding]::new($false))
Write-Output ("sections=" + ($files.Count - $missing.Count) + " missing=" + $missing.Count)
if ($missing.Count -gt 0) {
  $missing | ForEach-Object { Write-Output ("MISSING: " + $_) }
}
