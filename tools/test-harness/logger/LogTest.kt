import bg.sofia.transit.util.FileLogger
fun main() {
    val dir = kotlin.io.path.createTempDirectory("lg").toFile()
    FileLogger.init(android.content.Context(dir, dir))
    val errors = java.util.concurrent.atomic.AtomicInteger()
    val threads = (1..16).map { t -> Thread { repeat(20000) { i ->
        try { FileLogger.i("T$t", "line $i") } catch (e: Throwable) { errors.incrementAndGet() } } } }
    threads.forEach { it.start() }; threads.forEach { it.join() }
    Thread.sleep(1500)
    val lines = java.io.File(dir, "sofia_transit.log").readLines().filter { it.contains("/T") }
    val bad = lines.count { !Regex("""^\d\d:\d\d:\d\d\.\d{3} I/T\d+: line \d+$""").matches(it) }
    println("exceptions thrown to callers: ${errors.get()}, lines: ${lines.size}, malformed timestamps: $bad")
    kotlin.system.exitProcess(if (errors.get() == 0 && bad == 0) 0 else 1)
}
