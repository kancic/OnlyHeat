package ancic.karim.onlyheat.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Releases are built by .github/workflows/release.yml when a v* tag is pushed.
private const val LATEST = "https://api.github.com/repos/kancic/OnlyHeat/releases/latest"

/** A GitHub release newer than the running app; [urls] are its files (OnlyHeat.apk, OnlyHeat-x.y.z.dmg). */
data class Update(val version: String, val urls: List<String>)

/** The latest release if it's newer than [current] (e.g. "1.2.0"), else null. Throws when GitHub can't be reached. */
suspend fun checkUpdate(current: String): Update? = HttpClient().use { http ->
    val release = Json.parseToJsonElement(http.get(LATEST).bodyAsText()).jsonObject
    val version = release.getValue("tag_name").jsonPrimitive.content.removePrefix("v")
    if (!isNewer(version, current)) return null
    Update(version, release.getValue("assets").jsonArray.map { it.jsonObject.getValue("browser_download_url").jsonPrimitive.content })
}

internal fun isNewer(version: String, than: String): Boolean {
    val a = version.split('.').map { it.toIntOrNull() ?: 0 }
    val b = than.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}
