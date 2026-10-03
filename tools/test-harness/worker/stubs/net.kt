package android.net
object NetState { @Volatile var online = true; @Volatile var metered = true }
class Network
class NetworkCapabilities { companion object { const val NET_CAPABILITY_INTERNET = 12; const val NET_CAPABILITY_VALIDATED = 16; const val NET_CAPABILITY_NOT_METERED = 11 }
    fun hasCapability(c: Int) = if (c == NET_CAPABILITY_NOT_METERED) NetState.online && !NetState.metered else NetState.online }
class ConnectivityManager {
    val activeNetwork: Network? get() = if (NetState.online) Network() else null
    fun getNetworkCapabilities(n: Network?): NetworkCapabilities? = if (n != null) NetworkCapabilities() else null
}
