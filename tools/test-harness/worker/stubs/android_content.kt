package android.content
import java.io.File
import java.io.InputStream

interface SharedPreferences {
    fun getLong(k: String, d: Long): Long
    fun getString(k: String, d: String?): String?
    fun getBoolean(k: String, d: Boolean): Boolean
    fun getInt(k: String, d: Int): Int
    fun edit(): Editor
    interface Editor {
        fun putLong(k: String, v: Long): Editor
        fun putString(k: String, v: String?): Editor
        fun putBoolean(k: String, v: Boolean): Editor
        fun putInt(k: String, v: Int): Editor
        fun remove(k: String): Editor
        fun apply()
        fun commit(): Boolean
    }
}

class MemPrefs : SharedPreferences {
    val map = java.util.concurrent.ConcurrentHashMap<String, Any>()
    override fun getLong(k: String, d: Long) = (map[k] as? Long) ?: d
    override fun getString(k: String, d: String?) = (map[k] as? String) ?: d
    override fun getBoolean(k: String, d: Boolean) = (map[k] as? Boolean) ?: d
    override fun getInt(k: String, d: Int) = (map[k] as? Int) ?: d
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val ops = mutableListOf<() -> Unit>()
        override fun putLong(k: String, v: Long) = apply { ops += { map[k] = v } }
        override fun putString(k: String, v: String?) = apply { ops += { if (v == null) map.remove(k) else map[k] = v } }
        override fun putBoolean(k: String, v: Boolean) = apply { ops += { map[k] = v } }
        override fun putInt(k: String, v: Int) = apply { ops += { map[k] = v } }
        override fun remove(k: String) = apply { ops += { map.remove(k) } }
        override fun apply() { ops.forEach { it() } }
        override fun commit(): Boolean { apply(); return true }
    }
}

class AssetManager(val root: File) { fun open(p: String): InputStream = File(root, p).inputStream() }

open class Context(val filesDir: File, val assetsRoot: File) {
    companion object { const val MODE_PRIVATE = 0 }
    val prefs = HashMap<String, MemPrefs>()
    val assets get() = AssetManager(assetsRoot)
    fun getSharedPreferences(n: String, m: Int): SharedPreferences = prefs.getOrPut(n) { MemPrefs() }
    val applicationContext: Context get() = this
    @Suppress("UNCHECKED_CAST")
    fun <T> getSystemService(c: Class<T>): T? =
        if (c == android.net.ConnectivityManager::class.java) android.net.ConnectivityManager() as T else null
}
