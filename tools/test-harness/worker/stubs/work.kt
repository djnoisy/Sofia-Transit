package androidx.work
import android.content.Context
import java.util.UUID

class Data(val map: Map<String, Any?>) {
    fun getBoolean(k: String, d: Boolean) = (map[k] as? Boolean) ?: d
    fun getString(k: String): String? = map[k] as? String
    fun getLong(k: String, d: Long) = (map[k] as? Long) ?: d
    override fun toString() = map.toString()
    companion object { val EMPTY = Data(emptyMap()) }
}
fun workDataOf(vararg p: Pair<String, Any?>) = Data(mapOf(*p))

class WorkerParameters(val id: UUID = UUID.randomUUID(), val input: Data = Data.EMPTY, val tags: Set<String> = emptySet())

abstract class ListenableWorker(val appContext: Context, val params: WorkerParameters) {
    abstract class Result {
        class Success(val output: Data) : Result() { override fun toString() = "SUCCESS$output" }
        class Failure(val output: Data) : Result() { override fun toString() = "FAILURE$output" }
        class Retry : Result() { override fun toString() = "RETRY" }
        companion object {
            fun success(d: Data = Data.EMPTY): Result = Success(d)
            fun failure(d: Data = Data.EMPTY): Result = Failure(d)
            fun retry(): Result = Retry()
        }
    }
    val applicationContext: Context get() = appContext
    val inputData: Data get() = params.input
    val id: UUID get() = params.id
    @Volatile var stoppedFlag = false
    val isStopped: Boolean get() = stoppedFlag
    var runAttemptCount = 0
}

abstract class CoroutineWorker(c: Context, p: WorkerParameters) : ListenableWorker(c, p) {
    abstract suspend fun doWork(): Result
    val progressLog = mutableListOf<Data>()
    suspend fun setProgress(d: Data) { progressLog += d }
}

enum class ExistingWorkPolicy { KEEP, REPLACE, APPEND_OR_REPLACE }

class WorkInfo(val id: UUID, val state: State, val tags: Set<String>,
               val outputData: Data = Data.EMPTY, val progress: Data = Data.EMPTY) {
    enum class State(val isFinished: Boolean) {
        ENQUEUED(false), RUNNING(false), SUCCEEDED(true), FAILED(true), BLOCKED(false), CANCELLED(true)
    }
}

class OneTimeWorkRequest(val workerClass: Class<*>, val tags: Set<String>, val input: Data, val constraints: Constraints?) { val id: UUID = UUID.randomUUID() }
class OneTimeWorkRequestBuilderImpl(val cls: Class<*>) {
    val tags = mutableSetOf<String>(); var input = Data.EMPTY; var c: Constraints? = null
    fun addTag(t: String) = apply { tags += t }
    fun setInputData(d: Data) = apply { input = d }
    fun setConstraints(x: Constraints) = apply { c = x }
    fun build() = OneTimeWorkRequest(cls, tags, input, c)
}
enum class NetworkType { NOT_REQUIRED, CONNECTED, UNMETERED }
class Constraints(val network: NetworkType) {
    class Builder { var n = NetworkType.NOT_REQUIRED
        fun setRequiredNetworkType(t: NetworkType) = apply { n = t }
        fun build() = Constraints(n) }
}
inline fun <reified W : ListenableWorker> OneTimeWorkRequestBuilder() = OneTimeWorkRequestBuilderImpl(W::class.java)

class FakeFuture<T>(val v: T) { fun get(): T = v }

class WorkManager {
    data class Enq(val name: String, val policy: ExistingWorkPolicy, val req: OneTimeWorkRequest)
    val enqueued = mutableListOf<Enq>()
    var infos: List<WorkInfo> = emptyList()
    fun enqueueUniqueWork(n: String, p: ExistingWorkPolicy, r: OneTimeWorkRequest) { enqueued += Enq(n, p, r) }
    fun getWorkInfosForUniqueWork(n: String) = FakeFuture(infos)
    val live = androidx.lifecycle.MutableLiveData<List<WorkInfo>>()
    fun getWorkInfosForUniqueWorkLiveData(n: String): androidx.lifecycle.LiveData<List<WorkInfo>> = live
    companion object {
        val INSTANCE = WorkManager()
        fun getInstance(c: Context) = INSTANCE
    }
}
