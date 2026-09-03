package app.clipforge.update

internal data class ClipForgeRelease(
    val versionName: String,
    val buildNumber: Int,
    val assetName: String,
    val downloadUrl: String
)

internal object UpdateReleaseParser {
    private val assetPattern = Regex("^ClipForge-(.+)-b(\\d+)-debug\\.apk$")

    fun parseAsset(name: String, downloadUrl: String): ClipForgeRelease? {
        val match = assetPattern.matchEntire(name) ?: return null
        val buildNumber = match.groupValues[2].toIntOrNull() ?: return null
        return ClipForgeRelease(
            versionName = match.groupValues[1],
            buildNumber = buildNumber,
            assetName = name,
            downloadUrl = downloadUrl
        )
    }

    fun newest(
        assets: List<Pair<String, String>>,
        installedBuild: Int
    ): ClipForgeRelease? = assets
        .mapNotNull { (name, url) -> parseAsset(name, url) }
        .maxByOrNull { it.buildNumber }
        ?.takeIf { it.buildNumber > installedBuild }
}
