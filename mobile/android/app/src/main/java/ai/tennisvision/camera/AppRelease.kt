package ai.tennisvision.camera

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class StableVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<StableVersion> {
  override fun compareTo(other: StableVersion) = compareValuesBy(this, other,
    { it.major }, { it.minor }, { it.patch })
  override fun toString() = "$major.$minor.$patch"
  companion object {
    fun parse(value: String): StableVersion? {
      if (!Regex("""(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})\.(0|[1-9]\d{0,5})""").matches(value)) return null
      val parts = value.split('.').map { it.toInt() }
      return StableVersion(parts[0], parts[1], parts[2])
    }
  }
}

data class AppRelease(val version: StableVersion, val name: String, val url: String,
  val bytes: Long, val sha256: String) {
  fun toJson() = JSONObject().put("name", name).put("browser_download_url", url)
    .put("size", bytes).put("digest", "sha256:" + sha256).put("state", "uploaded")

  companion object {
    const val REPOSITORY = "Laikan2/tennis-vision-releases"
    const val API = "https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=100"
    const val MAX_BYTES = 64L * 1024 * 1024
    private const val PREFIX = "/Laikan2/tennis-vision-releases/releases/download/"
    private val filename = Regex("""Tennis-Vision-Camera-Android-v(\d+\.\d+\.\d+)\.apk""")

    fun fromAsset(asset: JSONObject): AppRelease? = runCatching {
      val name = asset.getString("name")
      val version = StableVersion.parse(filename.matchEntire(name)?.groupValues?.get(1) ?: return null)
        ?: return null
      val digest = asset.getString("digest")
      if (!Regex("""sha256:[a-fA-F0-9]{64}""").matches(digest)) return null
      val bytes = asset.getLong("size")
      if (bytes !in 1..MAX_BYTES || asset.optString("state") != "uploaded") return null
      val url = asset.getString("browser_download_url")
      val uri = URI(url)
      if (uri.scheme != "https" || uri.host != "github.com" || uri.port != -1 ||
        uri.userInfo != null || uri.rawQuery != null || uri.fragment != null ||
        !uri.rawPath.startsWith(PREFIX) || !uri.rawPath.endsWith("/" + name) ||
        uri.rawPath.removePrefix(PREFIX).split('/').size != 2) return null
      AppRelease(version, name, url, bytes, digest.removePrefix("sha256:").lowercase())
    }.getOrNull()

    fun newest(feed: String, installed: StableVersion): AppRelease? {
      val releases = JSONArray(feed)
      val candidates = mutableListOf<AppRelease>()
      for (index in 0 until releases.length()) {
        val release = releases.optJSONObject(index) ?: continue
        // Missing stability flags are not a stable release.
        if (!release.has("draft") || !release.has("prerelease") ||
          release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) continue
        val assets = release.optJSONArray("assets") ?: continue
        for (i in 0 until assets.length()) {
          val candidate = assets.optJSONObject(i)?.let(::fromAsset) ?: continue
          if (candidate.version > installed) candidates.add(candidate)
        }
      }
      return candidates.maxByOrNull { it.version }
    }
  }
}

class UpdateCancelled : IOException()

interface AppUpdateSource {
  fun check(installed: StableVersion, cancelled: () -> Boolean): AppRelease?
  fun download(release: AppRelease, target: File, cancelled: () -> Boolean, progress: (Int) -> Unit)
  fun cancel() = Unit
}

/** Public HTTPS only. No GitHub publishing token is stored in the APK. */
class GitHubAppUpdateSource : AppUpdateSource {
  @Volatile private var connection: HttpsURLConnection? = null

  private fun open(address: String, cancelled: () -> Boolean): HttpsURLConnection {
    var next = URL(address)
    repeat(6) {
      if (cancelled()) throw UpdateCancelled()
      val uri = next.toURI()
      val hosts = setOf("api.github.com", "github.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com", "github-releases.githubusercontent.com")
      require(uri.scheme == "https" && uri.host in hosts && uri.userInfo == null &&
        uri.port in setOf(-1, 443)) { "Endereço de atualização inválido." }
      val request = (next.openConnection() as HttpsURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 8_000
        readTimeout = 8_000
        setRequestProperty("User-Agent", "TennisVisionCamera/" + BuildConfig.VERSION_NAME)
        setRequestProperty("Accept", if (uri.host == "api.github.com") "application/vnd.github+json"
          else "application/octet-stream")
      }
      connection = request
      if (cancelled()) { request.disconnect(); throw UpdateCancelled() }
      try {
        val code = request.responseCode
        if (code in listOf(301, 302, 303, 307, 308)) {
          val location = request.getHeaderField("Location") ?: throw IOException("Redirecionamento inválido.")
          next = URL(next, location)
          request.disconnect()
          connection = null
        } else {
          if (code == 403 || code == 429)
            throw IOException("O GitHub limitou as consultas. Tente novamente mais tarde.")
          if (code != 200) throw IOException("Atualização indisponível. Tente novamente.")
          return request
        }
      } catch (error: Exception) {
        request.disconnect()
        connection = null
        throw error
      }
    }
    throw IOException("Não foi possível acessar a atualização.")
  }

  override fun check(installed: StableVersion, cancelled: () -> Boolean): AppRelease? {
    val request = open(AppRelease.API, cancelled)
    try {
      val result = request.inputStream.use { input ->
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
          if (cancelled()) throw UpdateCancelled()
          val count = input.read(buffer)
          if (count < 0) break
          if (result.size() + count > 2 * 1024 * 1024) throw IOException("Resposta de atualização inválida.")
          result.write(buffer, 0, count)
        }
        result.toString(Charsets.UTF_8.name())
      }
      return AppRelease.newest(result, installed)
    } finally { request.disconnect(); connection = null }
  }

  override fun download(release: AppRelease, target: File, cancelled: () -> Boolean, progress: (Int) -> Unit) {
    val request = open(release.url, cancelled)
    try {
      request.inputStream.use { input -> DownloadIntegrity.write(input, target, release, cancelled, progress) }
    } finally { request.disconnect(); connection = null }
  }

  override fun cancel() {
    val request = synchronized(this) { connection.also { connection = null } } ?: return
    // TLS/socket teardown must not block the camera's main thread.
    kotlin.concurrent.thread(name = "camera-update-cancel", isDaemon = true) {
      runCatching { request.disconnect() }
    }
  }
}

object DownloadIntegrity {
  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
  fun matches(file: File, release: AppRelease): Boolean {
    if (!file.isFile || file.length() != release.bytes) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(32 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return hex(digest.digest()) == release.sha256
  }

  fun write(input: InputStream, target: File, release: AppRelease, cancelled: () -> Boolean,
    progress: (Int) -> Unit) {
    val digest = MessageDigest.getInstance("SHA-256")
    var received = 0L
    var percent = -1
    try {
      target.outputStream().use { output ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
          if (cancelled()) throw UpdateCancelled()
          val count = input.read(buffer)
          if (count < 0) break
          received += count
          if (received > release.bytes || received > AppRelease.MAX_BYTES)
            throw IOException("O arquivo de atualização tem tamanho inválido.")
          output.write(buffer, 0, count)
          digest.update(buffer, 0, count)
          val next = (received * 100 / release.bytes).toInt()
          if (next != percent) { percent = next; progress(next) }
        }
      }
      if (cancelled()) throw UpdateCancelled()
      if (received != release.bytes || hex(digest.digest()) != release.sha256)
        throw IOException("A atualização não passou na verificação. Tente baixar novamente.")
    } catch (error: Exception) {
      target.delete()
      throw error
    }
  }
}

data class ApkIdentity(val packageName: String?, val versionName: String?, val versionCode: Long,
  val minSdk: Int, val signers: Set<String>) {
  fun validate(installed: ApkIdentity, release: AppRelease, sdk: Int) {
    require(packageName == installed.packageName && packageName == "ai.tennisvision.camera") {
      "O arquivo não é do Tennis Vision Camera."
    }
    require(versionName == release.version.toString() && versionCode > installed.versionCode) {
      "A versão do arquivo não corresponde à atualização."
    }
    require(minSdk <= sdk) { "A atualização requer uma versão mais recente do Android." }
    require(signers.isNotEmpty() && signers == installed.signers) {
      "A assinatura da atualização é incompatível. A instalação foi bloqueada."
    }
  }
}

class SignedApkVerifier(private val context: Context) {
  @Suppress("DEPRECATION")
  private fun identity(info: PackageInfo): ApkIdentity {
    val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    val signers = signatures?.map { signature ->
      MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    }?.toSet() ?: emptySet()
    return ApkIdentity(info.packageName, info.versionName,
      if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
      info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE, signers)
  }

  @Suppress("DEPRECATION")
  fun verify(file: File, release: AppRelease) {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val installed = context.packageManager.getPackageInfo(context.packageName, flags)
    val incoming = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
      ?: throw IOException("O APK de atualização não pôde ser lido.")
    identity(incoming).validate(identity(installed), release, Build.VERSION.SDK_INT)
  }
}
