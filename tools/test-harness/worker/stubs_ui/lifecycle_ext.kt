package androidx.lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
class Lifecycle { enum class State { STARTED } }
val LifecycleOwner.lifecycleScope: CoroutineScope get() = CoroutineScope(Dispatchers.Unconfined)
suspend fun LifecycleOwner.repeatOnLifecycle(s: Lifecycle.State, block: suspend CoroutineScope.() -> Unit) {
    kotlinx.coroutines.coroutineScope { block() }
}
