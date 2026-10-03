package androidx.lifecycle
interface LifecycleOwner
object Registry { val all = mutableListOf<LiveData<*>>(); fun destroyed(o: LifecycleOwner) = all.forEach { it.remove(o) } }
open class LiveData<T> {
    var value: T? = null; protected set
    val observers = mutableListOf<Pair<LifecycleOwner, (T) -> Unit>>()
    init { Registry.all += this }
    fun observe(owner: LifecycleOwner, o: (T) -> Unit) { observers += owner to o; value?.let(o) }
    fun remove(o: LifecycleOwner) { observers.removeAll { it.first === o } }
    fun resetForTest() { value = null; observers.clear() }
}
class MutableLiveData<T> : LiveData<T>() { fun post(v: T) { value = v; observers.toList().forEach { it.second(v) } } }
