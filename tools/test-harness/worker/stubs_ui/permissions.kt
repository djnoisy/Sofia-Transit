// Fakes for the first-run permissions screen as MainActivity sees it: the
// real Permissions object reads Android system state, which is not here.
package bg.sofia.transit.util

object Permissions {
    var shown = false
    var all = false
    fun reset() { shown = false; all = false }
    fun introShown(c: android.content.Context) = shown
    fun markIntroShown(c: android.content.Context) { shown = true }
    fun allGranted(c: android.content.Context) = all
}
