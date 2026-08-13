package pro.getline.vpn.getline.servers

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Offline inventory of proxy-groups written next to `config.yaml` after a
 * successful Mihomo validate/parse. Product UI reads this when the core is
 * not loaded. Do not parse subscription YAML here.
 */
data class ServerCatalog(
    val version: Int,
    val mode: String,
    val groups: List<Group>,
) {
    data class Group(
        val name: String,
        val type: String,
        val now: String,
        val hidden: Boolean,
        val proxies: List<Proxy>,
    )

    data class Proxy(
        val name: String,
        val type: String,
        val group: Boolean,
    )

    companion object {
        const val FILE_NAME = "server-catalog.json"
        const val VERSION = 1
        const val SELECTOR_TYPE = "Selector"
        const val GLOBAL_GROUP = "GLOBAL"
        const val MODE_RULE = "rule"
        const val MODE_GLOBAL = "global"
        const val MODE_DIRECT = "direct"

        fun fileIn(profileDir: File): File = profileDir.resolve(FILE_NAME)

        /**
         * A catalog written by a newer core is not readable by this projection,
         * so it is treated as absent — offline shows Empty instead of guessing.
         */
        fun read(profileDir: File): ServerCatalog? {
            val file = fileIn(profileDir)
            if (!file.isFile || file.length() <= 0L) return null
            return runCatching { parse(file.readText()) }.getOrNull()
                ?.takeIf { it.version == VERSION }
        }

        internal fun parse(json: String): ServerCatalog {
            val root = JSONObject(json)
            val groups = root.optJSONArray("groups") ?: JSONArray()
            return ServerCatalog(
                version = root.optInt("version", 0),
                mode = root.optString("mode"),
                groups = (0 until groups.length()).map { index ->
                    parseGroup(groups.getJSONObject(index))
                },
            )
        }

        private fun parseGroup(obj: JSONObject): Group {
            val proxies = obj.optJSONArray("proxies") ?: JSONArray()
            return Group(
                name = obj.optString("name"),
                type = obj.optString("type"),
                now = obj.optString("now"),
                hidden = obj.optBoolean("hidden"),
                proxies = (0 until proxies.length()).map { index ->
                    val proxy = proxies.getJSONObject(index)
                    Proxy(
                        name = proxy.optString("name"),
                        type = proxy.optString("type"),
                        group = proxy.optBoolean("group"),
                    )
                },
            )
        }
    }
}
