import android.view.A11y
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.Dialogs
import androidx.appcompat.app.TestEnv
import androidx.work.*
import bg.sofia.transit.MainActivity
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.databinding.ActivityMainBinding
import java.io.File
import java.util.UUID

var ok = 0; val bad = mutableListOf<String>()
fun t(name: String, body: () -> Unit) {
    try { body(); ok++; println("PASS  $name") }
    catch (e: Throwable) { bad += "$name: ${e.message}"; println("FAIL  $name\n      ${e.message}\n      dialogs: ${Dialogs.all}") }
}
fun eq(w: String, e: Any?, a: Any?) { if (e != a) throw AssertionError("$w: expected <$e> but was <$a>") }
fun yes(w: String, c: Boolean) { if (!c) throw AssertionError(w) }

class Env(dbReady: Boolean = true) {
    val root = kotlin.io.path.createTempDirectory("ui").toFile()
    init { TestEnv.files = File(root, "f").apply { mkdirs() }; TestEnv.assets = File(root, "a").apply { mkdirs() }
        Dialogs.all.clear(); A11y.spoken.clear(); WorkManager.INSTANCE.enqueued.clear()
        WorkManager.INSTANCE.live.resetForTest()
        bg.sofia.transit.util.Permissions.reset()
        bg.sofia.transit.service.JourneyService._trackingState.value =
            bg.sofia.transit.service.JourneyService.TrackingState.Idle }
    val act = MainActivity()
    val repo = GtfsRepository(act)
    init { if (dbReady) repo.db = "bundled:x"; act.gtfsRepo = repo; act.onCreate(null) }
    fun post(vararg i: WorkInfo) = WorkManager.INSTANCE.live.post(i.toList())
    fun showing() = Dialogs.all.filter { it.isShowing }
    fun only(): AlertDialog { val s = showing(); eq("dialogs showing", 1, s.size); return s[0] }
    fun binding() = ActivityMainBinding.last!!
    fun permissionScreens() = act.started.count {
        it.cls == bg.sofia.transit.ui.permissions.PermissionsActivity::class.java }
}
val INST = setOf("gtfs_install")
fun info(id: UUID, st: WorkInfo.State, tags: Set<String> = emptySet(), phase: String? = null, vararg out: Pair<String, Any?>) =
    WorkInfo(id, st, tags, workDataOf(*out), if (phase != null) workDataOf("phase" to phase) else Data.EMPTY)

fun main() {
    val R = WorkInfo.State.RUNNING; val E = WorkInfo.State.ENQUEUED; val S = WorkInfo.State.SUCCEEDED
    val F = WorkInfo.State.FAILED; val C = WorkInfo.State.CANCELLED

    // ── first install ──
    t("A1 install enqueued → install dialog at once, no button, not cancelable") {
        val e = Env(dbReady = false); e.post(info(UUID.randomUUID(), E, INST))
        val d = e.only(); eq("title", "Инсталиране на данните", d.shownTitle); eq("msg", "Инсталиране на данните…", d.message)
        eq("button", null, d.positive); eq("cancelable", false, d.cancelable); d.pressBack(); yes("Back does nothing", d.isShowing)
    }
    t("A2 install phases (check/download/import) → same single dialog, same text, spoken once") {
        val e = Env(false); val id = UUID.randomUUID()
        e.post(info(id, R, INST)); e.post(info(id, R, INST, "download")); e.post(info(id, R, INST, "import"))
        eq("dialogs created", 1, Dialogs.all.size); eq("msg", "Инсталиране на данните…", e.only().message)
        eq("extra announcements", 0, A11y.spoken.count { it.startsWith("Инсталиране") })
    }
    t("A3 install done (new data) → 'Данните са инсталирани.' + Затвори; closed → never again") {
        val e = Env(false); val id = UUID.randomUUID()
        e.post(info(id, R, INST, "import")); e.repo._initialLoadDone.value = true
        e.post(info(id, S, INST, null, "result" to "installed_new", "visible" to true))
        val d = e.only(); eq("msg", "Данните са инсталирани.", d.message); eq("button", "Затвори", d.positive)
        d.clickPositive(); e.post(info(id, S, INST, null, "result" to "installed_new", "visible" to true))
        eq("no dialog after close", 0, e.showing().size)
    }
    t("A4 install done from bundled (current / fallback) → same short text") {
        for (r in listOf("installed_current", "installed_fallback")) {
            val e = Env(true); e.post(info(UUID.randomUUID(), S, INST, null, "result" to r, "visible" to true))
            eq("msg for $r", "Данните са инсталирани.", e.only().message)
        }
    }
    t("A5 install failed, no data → 'не можаха' + Опитай отново (not cancelable) → retry requested") {
        val e = Env(false); val id = UUID.randomUUID()
        e.post(info(id, F, INST, null, "result" to "failed", "visible" to true))
        val d = e.only(); eq("msg", "Данните не можаха да се инсталират.", d.message); eq("button", "Опитай отново", d.positive)
        eq("cancelable", false, d.cancelable); d.clickPositive(); eq("retry called", 1, e.repo.retried)
        e.post(info(id, F, INST, null, "result" to "failed", "visible" to true)); eq("not repeated", 0, e.showing().size)
    }
    t("A6 install crashed with empty output / cancelled, no data → retry dialog") {
        for (st in listOf(F, C)) { val e = Env(false); e.post(info(UUID.randomUUID(), st, INST))
            eq("btn ($st)", "Опитай отново", e.only().positive) }
    }
    t("A7 install skipped (DB already there) → nothing") {
        val e = Env(true); e.repo._initialLoadDone.value = true
        e.post(info(UUID.randomUUID(), S, INST, null, "result" to "skipped", "visible" to false)); eq("dialogs", 0, e.showing().size)
    }
    t("A8 finished successful install seen before data flag (restart order) → no false 'failed'") {
        val e = Env(false); e.post(info(UUID.randomUUID(), S, INST, null, "result" to "installed_new", "visible" to true))
        eq("msg", "Данните са инсталирани.", e.only().message)
    }
    t("A9 a retried install replaces a leftover failure dialog") {
        val e = Env(false); e.post(info(UUID.randomUUID(), F, INST, null, "result" to "failed", "visible" to true))
        e.post(info(UUID.randomUUID(), E, INST)); eq("msg", "Инсталиране на данните…", e.only().message)
    }

    // ── later updates ──
    t("A10 update checking/downloading → no dialog at all") {
        val e = Env(); val id = UUID.randomUUID()
        e.post(info(id, E)); e.post(info(id, R)); e.post(info(id, R, phase = "download")); eq("dialogs", 0, Dialogs.all.size)
    }
    t("A11 update install phase → blocking dialog 'Актуализиране на данните'") {
        val e = Env(); val id = UUID.randomUUID(); e.post(info(id, R, phase = "download")); e.post(info(id, R, phase = "import"))
        val d = e.only(); eq("title", "Актуализиране на данните", d.shownTitle); eq("msg", "Инсталиране на новите данни…", d.message)
        eq("button", null, d.positive); eq("cancelable", false, d.cancelable)
    }
    t("A12 update stopped by system (ENQUEUED with stale phase) → dialog removed") {
        val e = Env(); val id = UUID.randomUUID(); e.post(info(id, R, phase = "import")); e.post(info(id, E, phase = "import"))
        eq("showing", 0, e.showing().size)
    }
    t("A13 update done → 'Данните за градския транспорт са актуализирани.' + Затвори; Back also closes for good") {
        val e = Env(); val id = UUID.randomUUID(); e.post(info(id, R, phase = "import"))
        e.post(info(id, S, out = arrayOf("result" to "updated", "visible" to true)))
        val d = e.only(); eq("msg", "Данните за градския транспорт са актуализирани.", d.message); eq("btn", "Затвори", d.positive)
        d.pressBack(); e.post(info(id, S, out = arrayOf("result" to "updated", "visible" to true))); eq("not again", 0, e.showing().size)
    }
    t("A14 update failed after install began → failure text + Затвори") {
        val e = Env(); e.post(info(UUID.randomUUID(), F, out = arrayOf("result" to "failed", "visible" to true)))
        yes("failure text", e.only().message!!.startsWith("Актуализирането на данните не бе успешно"))
    }
    t("A15 silent outcomes (unchanged, deferred, failed before install, skipped) → no dialog") {
        for ((st, r) in listOf(S to "unchanged", S to "deferred", F to "failed", S to "skipped")) {
            val e = Env(); e.post(info(UUID.randomUUID(), st, out = arrayOf("result" to r, "visible" to false)))
            eq("dialogs for $r", 0, Dialogs.all.size)
        }
    }
    t("A16 rotation: result not yet closed is shown again by the new screen, once") {
        val e = Env(); val id = UUID.randomUUID(); val fin = info(id, S, out = arrayOf("result" to "updated", "visible" to true))
        e.post(fin); e.act.onDestroy(); eq("dismissed on destroy", 0, e.showing().size)
        val act2 = MainActivity(); act2.gtfsRepo = e.repo; act2.onCreate(null); e.post(fin)
        eq("shown again", 1, e.showing().size); e.showing()[0].clickPositive()
        e.post(fin); eq("closed for good", 0, e.showing().size)
    }
    t("A17 rotation during install → progress dialog rebuilt") {
        val e = Env(false); val id = UUID.randomUUID(); e.post(info(id, R, INST, "import")); e.act.onDestroy()
        val act2 = MainActivity(); act2.gtfsRepo = e.repo; act2.onCreate(null); e.post(info(id, R, INST, "import"))
        eq("msg", "Инсталиране на данните…", e.only().message)
    }
    t("A18 several records → the unfinished run wins") {
        val e = Env(); e.post(info(UUID.randomUUID(), S, out = arrayOf("result" to "updated", "visible" to true)),
                              info(UUID.randomUUID(), R, phase = "import"))
        eq("msg", "Инсталиране на новите данни…", e.only().message)
    }

    // ── overlay / announcements / trigger ──
    t("A19 normal launch: data ready → overlay hidden, 'готово' spoken, update check requested") {
        val e = Env(true); runBlockingReady(e)
        eq("overlay", View.GONE, e.binding().layoutLoading.visibility)
        yes("announced", A11y.spoken.any { it.startsWith("Данните са заредени") }); yes("check enqueued", WorkManager.INSTANCE.enqueued.isNotEmpty())
    }
    t("A20 first install: overlay text plain, no 'готово' while the install dialog is up") {
        val e = Env(false); eq("overlay", View.VISIBLE, e.binding().layoutLoading.visibility)
        eq("overlay text", "Инсталиране на данните…", e.binding().tvLoadingMsg.text.toString())
        val id = UUID.randomUUID(); e.post(info(id, R, INST, "import")); A11y.spoken.clear()
        e.repo._initialLoadDone.value = true
        eq("overlay hidden", View.GONE, e.binding().layoutLoading.visibility)
        eq("no 'готово'", false, A11y.spoken.any { it.startsWith("Данните са заредени") })
    }
    t("A21 reinstall (new app version): silent until import, then update dialog and 'актуализирани'") {
        val e = Env(); val id = UUID.randomUUID(); val RI = setOf("gtfs_reinstall")
        e.post(info(id, R, RI)); e.post(info(id, R, RI, "download")); eq("no dialog before import", 0, Dialogs.all.size)
        e.post(info(id, R, RI, "import")); eq("title", "Актуализиране на данните", e.only().shownTitle)
        e.post(info(id, S, RI, null, "result" to "installed_current", "visible" to true))
        eq("msg", "Данните за градския транспорт са актуализирани.", e.only().message)
    }
    t("A22 reinstall postponed (journey) → nothing shown") {
        val e = Env(); e.post(info(UUID.randomUUID(), S, setOf("gtfs_reinstall"), null, "result" to "skipped", "visible" to false))
        eq("dialogs", 0, Dialogs.all.size)
    }
    t("A23 reinstall failed after it began installing → 'не можаха' + Опитай отново") {
        val e = Env(); e.repo._initialLoadDone.value = true
        e.post(info(UUID.randomUUID(), F, setOf("gtfs_reinstall"), null, "result" to "failed", "visible" to true))
        val d = e.only(); eq("btn", "Опитай отново", d.positive); d.clickPositive(); eq("retry", 1, e.repo.retried)
    }
    t("A24 reinstall deferred (journey started) → nothing shown") {
        val e = Env(); e.post(info(UUID.randomUUID(), S, setOf("gtfs_reinstall"), null, "result" to "deferred", "visible" to false))
        eq("dialogs", 0, Dialogs.all.size)
    }
    fun pend(e: Env, id: UUID, kind: String, result: String) { val m = (e.act.getSharedPreferences("gtfs_update", 0) as android.content.MemPrefs).map
        m["pending_id"] = id.toString(); m["pending_kind"] = kind; m["pending_result"] = result }
    t("A25 finished update's record replaced by a new check (cold start) → kept result still shown, once") {
        val e = Env(); val old = UUID.randomUUID(); pend(e, old, "update", "updated")
        e.post(info(UUID.randomUUID(), E))                          // only the new check is visible
        val d = e.only(); eq("msg", "Данните за градския транспорт са актуализирани.", d.message)
        d.clickPositive(); e.post(info(UUID.randomUUID(), R)); eq("not again", 0, e.showing().size)
    }
    t("A26 no record at all (pruned) → kept result shown") {
        val e = Env(); pend(e, UUID.randomUUID(), "update", "updated"); e.post()
        eq("shown", 1, e.showing().size)
    }
    t("A27 same run seen via record and kept copy → one dialog only") {
        val e = Env(); val id = UUID.randomUUID(); pend(e, id, "update", "updated")
        e.post(info(id, S, out = arrayOf("result" to "updated", "visible" to true)))
        e.post(info(id, S, out = arrayOf("result" to "updated", "visible" to true)))
        eq("dialogs created", 1, Dialogs.all.size)
    }
    t("A28 kept install result after restart → 'Данните са инсталирани.'; kept failed reinstall → retry") {
        val e = Env(); pend(e, UUID.randomUUID(), "install", "installed_fallback"); e.post()
        eq("msg", "Данните са инсталирани.", e.only().message)
        val e2 = Env(); pend(e2, UUID.randomUUID(), "reinstall", "failed"); e2.post()
        eq("btn", "Опитай отново", e2.only().positive)
    }
    t("A29 unseen old result + a new install starts → old result discarded for good, only the new one shown") {
        val e = Env(); val old = UUID.randomUUID(); pend(e, old, "update", "updated")
        e.post(info(UUID.randomUUID(), R)); eq("old shown meanwhile", 1, e.showing().size)
        val nid = UUID.randomUUID(); e.post(info(nid, R, phase = "import"))
        eq("progress only", "Инсталиране на новите данни…", e.only().message)
        e.post(info(nid, E, phase = "import"))                 // new run stopped by the system
        eq("old does not come back", 0, e.showing().size)
        pend(e, nid, "update", "updated"); e.post(info(nid, S, out = arrayOf("result" to "updated", "visible" to true)))
        eq("only new result", 1, e.showing().size); e.only().clickPositive()
        e.post(info(UUID.randomUUID(), R)); eq("nothing after", 0, e.showing().size)
    }
    t("A29b old result dialog on screen when a new install starts → replaced, not shown again") {
        val e = Env(); val old = UUID.randomUUID()
        e.post(info(old, S, out = arrayOf("result" to "updated", "visible" to true))); eq("shown", 1, e.showing().size)
        val nid = UUID.randomUUID(); e.post(info(nid, R, phase = "import"))
        eq("progress replaces it", "Инсталиране на новите данни…", e.only().message)
        e.post(info(nid, S, out = arrayOf("result" to "unchanged", "visible" to false)))   // new run ends silently
        eq("old not re-shown", 0, e.showing().size)
    }
    t("A30 kept install failure, but data loaded since → dropped silently") {
        val e = Env(true); runBlockingReady(e); pend(e, UUID.randomUUID(), "install", "failed"); e.post()
        eq("dialogs", 0, Dialogs.all.size)
    }
    t("A31 install running with its follow-up already queued → install dialog stays") {
        val e = Env(false); val inst = UUID.randomUUID()
        e.post(info(UUID.randomUUID(), WorkInfo.State.BLOCKED), info(inst, R, INST, "import"))
        eq("msg", "Инсталиране на данните…", e.only().message)
    }
    t("A32 install done, follow-up waiting for internet → 'Данните са инсталирани.' shown") {
        val e = Env(true); val inst = UUID.randomUUID(); pend(e, inst, "install", "installed_fallback")
        e.post(info(inst, S, INST, null, "result" to "installed_fallback", "visible" to true), info(UUID.randomUUID(), E))
        eq("msg", "Данните са инсталирани.", e.only().message)
    }
    t("A33 update done entirely in the background → message shown when the app is opened") {
        val e = Env(); val id = UUID.randomUUID(); pend(e, id, "update", "updated")
        // app opened only now: the finished record is delivered once
        e.post(info(id, S, out = arrayOf("result" to "updated", "visible" to true)))
        val d = e.only(); eq("msg", "Данните за градския транспорт са актуализирани.", d.message); eq("btn", "Затвори", d.positive)
    }
    t("A34 late progress report of the install that just finished does not swallow its message") {
        val e = Env(false); val inst = UUID.randomUUID()
        e.post(info(inst, R, INST, "import"))
        pend(e, inst, "install", "installed_fallback"); e.repo._initialLoadDone.value = true   // worker finished, record not yet updated
        e.post(info(inst, R, INST, "import"), info(UUID.randomUUID(), WorkInfo.State.BLOCKED))  // late report incl. queued follow-up
        eq("message shown", "Данните са инсталирани.", e.only().message)
        e.post(info(inst, S, INST, null, "result" to "installed_fallback", "visible" to true), info(UUID.randomUUID(), E))
        eq("still one message", 1, e.showing().size); e.only().clickPositive(); eq("closed", 0, e.showing().size)
    }
    t("A35 closing 'installed' after a fallback install → newer data looked for at once") {
        val e = Env(true); val id = UUID.randomUUID(); pend(e, id, "install", "installed_fallback"); e.post()
        WorkManager.INSTANCE.enqueued.clear(); e.only().clickPositive()
        val q = WorkManager.INSTANCE.enqueued.single(); eq("network", NetworkType.CONNECTED, q.req.constraints?.network)
    }
    t("A36 closing other messages → the usual check (with its spacing rules)") {
        val e = Env(true); runBlockingReady(e); val id = UUID.randomUUID(); pend(e, id, "update", "updated")
        WorkManager.INSTANCE.enqueued.clear(); e.post(); e.only().clickPositive()
        eq("check queued", 1, WorkManager.INSTANCE.enqueued.size)
        val e2 = Env(true); runBlockingReady(e2); pend(e2, UUID.randomUUID(), "install", "installed_current")
        WorkManager.INSTANCE.enqueued.clear(); e2.post(); e2.only().pressBack()
        eq("check queued after Back", 1, WorkManager.INSTANCE.enqueued.size)
    }
    t("A37 app opened with an unclosed message → no check starts until it is closed") {
        val e = Env(true); pend(e, UUID.randomUUID(), "install", "installed_fallback"); WorkManager.INSTANCE.enqueued.clear()
        runBlockingReady(e)                                   // initialLoadDone → would normally check
        eq("no check yet", 0, WorkManager.INSTANCE.enqueued.size)
    }
    t("A38 failed install record but data loaded since → no stale 'Опитай отново'") {
        val e = Env(true); runBlockingReady(e)
        e.post(info(UUID.randomUUID(), F, INST, null, "result" to "failed", "visible" to true))
        eq("dialogs", 0, e.showing().size)
    }
    t("A39 closing a message also lets a due reinstall (new app version) start") {
        val e = Env(true); pend(e, UUID.randomUUID(), "update", "updated"); e.post()
        val before = e.repo.startCalls; e.only().clickPositive(); eq("reinstall check run", before + 1, e.repo.startCalls)
    }
    t("A40 update that left no usable data → 'не можаха' + Опитай отново") {
        val e = Env(true); runBlockingReady(e)
        e.post(info(UUID.randomUUID(), F, out = arrayOf("result" to "failed_no_data", "visible" to true)))
        val d = e.only(); eq("msg", "Данните не можаха да се инсталират.", d.message); eq("btn", "Опитай отново", d.positive)
        d.clickPositive(); eq("retry", 1, e.repo.retried)
    }
    t("A41 repaired install → neutral 'Данните са инсталирани.'; closing allows the next check") {
        val e = Env(true); runBlockingReady(e); val id = UUID.randomUUID()
        e.post(info(id, S, out = arrayOf("result" to "repaired", "visible" to true)))
        val d = e.only(); eq("msg", "Данните са инсталирани.", d.message)
        WorkManager.INSTANCE.enqueued.clear(); d.clickPositive(); eq("check queued", 1, WorkManager.INSTANCE.enqueued.size)
    }

    // ── first-run permissions screen ──
    val P = bg.sofia.transit.util.Permissions
    t("P1 first install → permissions screen only after 'Данните са инсталирани.' is closed") {
        val e = Env(false); val id = UUID.randomUUID()
        e.post(info(id, R, INST, "import")); e.repo._initialLoadDone.value = true
        eq("not over the install dialog", 0, e.permissionScreens())
        e.post(info(id, S, INST, null, "result" to "installed_new", "visible" to true))
        eq("not over the result", 0, e.permissionScreens())
        e.only().clickPositive()
        eq("shown after close", 1, e.permissionScreens()); yes("marked shown", P.shown)
    }
    t("P2 already shown once → never again") {
        val e = Env(true); P.shown = true; e.repo._initialLoadDone.value = true
        eq("screens", 0, e.permissionScreens())
    }
    t("P3 everything already granted → not shown, and not later either") {
        val e = Env(true); P.all = true; e.repo._initialLoadDone.value = true
        eq("screens", 0, e.permissionScreens()); yes("marked shown", P.shown)
    }
    t("P4 data already there (app update), no window → shown at start") {
        val e = Env(true); e.repo._initialLoadDone.value = true
        eq("screens", 1, e.permissionScreens())
    }
    t("P5 during a journey → not shown, kept for later") {
        val e = Env(true)
        bg.sofia.transit.service.JourneyService._trackingState.value =
            bg.sofia.transit.service.JourneyService.TrackingState.Tracking()
        e.repo._initialLoadDone.value = true
        eq("screens", 0, e.permissionScreens()); yes("not marked", !P.shown)
    }
    t("P6 install failed, no data → not shown") {
        val e = Env(false)
        e.post(info(UUID.randomUUID(), F, INST, null, "result" to "failed", "visible" to true))
        eq("screens", 0, e.permissionScreens())
    }
    println("\n$ok passed, ${bad.size} failed"); bad.forEach { println("  ✗ $it") }
    kotlin.system.exitProcess(if (bad.isEmpty()) 0 else 1)

}
fun runBlockingReady(e: Env) { kotlinx.coroutines.runBlocking { e.repo.isDatabaseReady() } }
