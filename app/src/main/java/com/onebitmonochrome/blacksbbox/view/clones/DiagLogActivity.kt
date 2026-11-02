package com.onebitmonochrome.blacksbbox.view.clones

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.onebitmonochrome.blacksbbox.view.base.BaseActivity
import java.io.File

/**
 * Round 5: in-app viewer for the clone diagnostic log.
 *
 * The PM/AM hooks inside clones append to devxyzclone-pmdiag.log in the
 * host's internal files dir via top.niunaijun.blackbox.utils.DiagLog.
 * This screen reads that exact file in the host process, so the log is
 * always reachable (no folder hunting, no scoped-storage dead ends) and
 * can be shared out in one tap.
 */
class DiagLogActivity : BaseActivity() {

    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Diagnostic Log"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val shareButton = Button(this).apply {
            text = "Share log"
            setOnClickListener { shareLog() }
        }
        root.addView(shareButton)

        val scroll = ScrollView(this)
        textView = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 11f
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(textView)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        setContentView(root)
        refresh()
    }

    private fun logFile(): File = File(filesDir, "devxyzclone-pmdiag.log")

    private fun readLog(): String {
        val f = logFile()
        if (!f.exists() || f.length() == 0L) {
            return ""
        }
        val text = f.readText()
        val max = 200_000
        return if (text.length > max) "…(showing the last part of the log)\n" + text.takeLast(max) else text
    }

    private fun refresh() {
        val text = readLog()
        textView.text =
            if (text.isEmpty()) "(log is empty — no diagnostic events recorded yet)"
            else text
    }

    private fun shareLog() {
        val text = readLog()
        if (text.isEmpty()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DevxyzClone diagnostic log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share diagnostic log"))
    }
}
