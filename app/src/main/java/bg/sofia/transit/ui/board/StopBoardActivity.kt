package bg.sofia.transit.ui.board

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import bg.sofia.transit.databinding.ActivityStopBoardBinding
import bg.sofia.transit.ui.nearby.StopArrivalsFragment
import bg.sofia.transit.util.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

/**
 * "Виртуално табло": the arrivals at one stop, in a window of its own (the
 * owner, 9 Nov 2026) — no navigation bar; in the top bar the back arrow,
 * the title, "Обнови" and the favourites (StopArrivalsFragment's menu).
 * Opened from anywhere in the app with [start]: today from Спирки (near us
 * and the favourites), more places to come.
 */
@AndroidEntryPoint
class StopBoardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStopBoardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeMode.applyTo(this)
        super.onCreate(savedInstanceState)
        binding = ActivityStopBoardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = TITLE

        // No navigation bar below: the board keeps clear of the system's
        // (edge to edge on Android 15).
        ViewCompat.setOnApplyWindowInsetsListener(binding.boardContainer) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }

        if (savedInstanceState == null) {
            val stopId = intent.getStringExtra(EXTRA_STOP_ID)
            if (stopId == null) { finish(); return }
            supportFragmentManager.beginTransaction()
                .replace(binding.boardContainer.id, StopArrivalsFragment.newInstance(
                    stopId,
                    intent.getStringExtra(EXTRA_STOP_NAME) ?: stopId,
                    intent.getStringExtra(EXTRA_STOP_CODE)))
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val TITLE = "Виртуално табло"
        private const val EXTRA_STOP_ID = "stopId"
        private const val EXTRA_STOP_NAME = "stopName"
        private const val EXTRA_STOP_CODE = "stopCode"

        /** The board of a stop: its id, its name, and its code (e.g. "0170"), if known. */
        fun intentFor(context: Context, stopId: String, stopName: String, stopCode: String?): Intent =
            Intent(context, StopBoardActivity::class.java)
                .putExtra(EXTRA_STOP_ID, stopId)
                .putExtra(EXTRA_STOP_NAME, stopName)
                .putExtra(EXTRA_STOP_CODE, stopCode)

        fun start(context: Context, stopId: String, stopName: String, stopCode: String?) {
            context.startActivity(intentFor(context, stopId, stopName, stopCode))
        }
    }
}
