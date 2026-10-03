package android.util
object Log {
    @JvmStatic fun i(t: String, m: String) = 0
    @JvmStatic fun w(t: String, m: String) = 0
    @JvmStatic fun d(t: String, m: String) = 0
    @JvmStatic fun e(t: String, m: String, e: Throwable? = null) = 0
}
