package android.content
class Intent(val from: Context?, val cls: Class<*>?) {
    var action: String? = null
    val categories = mutableSetOf<String>()
    fun hasCategory(c: String) = c in categories
    companion object {
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        /** As the launcher starts the app. */
        fun launcher() = Intent(null, null).apply { action = ACTION_MAIN; categories += CATEGORY_LAUNCHER }
    }
}
