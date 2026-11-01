package com.onebitmonochrome.blacksbbox.view.clones

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.data.CloneRecord
import com.onebitmonochrome.blacksbbox.data.CloneRegistry
import com.onebitmonochrome.blacksbbox.view.base.BaseActivity
import com.onebitmonochrome.blacksbbox.view.list.ListActivity
import com.onebitmonochrome.blacksbbox.view.main.MainActivity
import com.onebitmonochrome.blacksbbox.view.setting.SettingActivity
import java.io.File

/**
 * Phase B home: one unified list of clones.
 *
 * Every clone is a dedicated BlackBox userId (see CloneRegistry), so each
 * entry — "Muse 2", "Muse 3", a renamed "Muse fake" — has fully
 * isolated data. The upstream per-profile pager (MainActivity) is kept
 * and reachable from the menu; nothing is removed.
 */
class ClonesActivity : BaseActivity() {

    private lateinit var registry: CloneRegistry
    private lateinit var adapter: ClonesAdapter
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var fab: FloatingActionButton

    private var pendingUserId: Int = -1
    private var serviceCallback: Runnable? = null

    private val labelCache = HashMap<String, String>()
    private val iconCache = HashMap<String, Drawable>()

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uid = pendingUserId
        pendingUserId = -1
        if (result.resultCode != Activity.RESULT_OK || uid < 0) return@registerForActivityResult
        val source = result.data?.getStringExtra("source")?.takeIf { it.isNotEmpty() }
            ?: return@registerForActivityResult
        installClone(source, uid)
    }

    private var startupRecords: List<CloneRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clones)
        savedInstanceState?.let { pendingUserId = it.getInt(KEY_PENDING_USER, -1) }

        emptyView = findViewById(R.id.emptyView)
        progress = findViewById(R.id.progress)
        fab = findViewById(R.id.fab)
        emptyView.visibility = View.VISIBLE
        emptyView.text = "STEP 0: layout inflated"

        val steps = listOf<Pair<String, () -> Unit>>(
            "toolbar" to {
                val toolbar = findViewById<Toolbar>(R.id.toolbar)
                setSupportActionBar(toolbar)
                toolbar.title = getString(R.string.app_name)
                toolbar.subtitle = POWERED_BY
            },
            "registry" to {
                registry = CloneRegistry(this)
                startupRecords = registry.loadRecords()
            },
            "adapter" to {
                adapter = ClonesAdapter(
                    onRowClick = { record -> launchClone(record) },
                    onRowMenu = { anchor, record -> showCloneMenu(anchor, record) }
                )
                val list = findViewById<RecyclerView>(R.id.clonesList)
                list.layoutManager = LinearLayoutManager(this)
                list.adapter = adapter
                showRecords(startupRecords)
            },
            "fab" to {
                fab.setOnClickListener {
                    pendingUserId = registry.allocateUserId()
                    val intent = Intent(this, ListActivity::class.java)
                    intent.putExtra("userID", pendingUserId)
                    pickerLauncher.launch(intent)
                }
            },
            "callback" to {
                val callback = Runnable { validateAndRefresh() }
                serviceCallback = callback
                BlackBoxCore.get().addServiceAvailableCallback(callback)
            }
        )
        runStartupStep(steps, 0)
    }

    private fun runStartupStep(steps: List<Pair<String, () -> Unit>>, index: Int) {
        if (index >= steps.size) {
            emptyView.text = getString(R.string.clones_empty)
            emptyView.visibility = if (startupRecords.isEmpty()) View.VISIBLE else View.GONE
            return
        }
        val (label, action) = steps[index]
        emptyView.visibility = View.VISIBLE
        emptyView.text = "STEP ${index + 1}: $label"
        window.decorView.postDelayed({
            try {
                action()
                runStartupStep(steps, index + 1)
            } catch (t: Throwable) {
                emptyView.text = "FAILED at $label: ${t.javaClass.name}: ${t.message}"
                emptyView.visibility = View.VISIBLE
            }
        }, 400)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PENDING_USER, pendingUserId)
    }

    override fun onDestroy() {
        serviceCallback?.let { BlackBoxCore.get().removeServiceAvailableCallback(it) }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.clones_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_profiles -> {
                MainActivity.start(this)
                true
            }
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ------------------------------------------------------------------
    // List state
    // ------------------------------------------------------------------

    private fun showRecords(records: List<CloneRecord>) {
        adapter.submit(records)
        emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        showRecords(registry.loadRecords())
    }

    private fun validateAndRefresh() {
        val records = registry.loadRecords()
        if (records.isEmpty()) {
            showRecords(records)
            return
        }
        lifecycleScope.launch {
            val kept = withContext(Dispatchers.IO) {
                records.filter { record ->
                    try {
                        BlackBoxCore.get().isInstalled(record.packageName, record.userId)
                    } catch (e: Exception) {
                        true // never prune on uncertainty
                    }
                }
            }
            if (kept.size != records.size) registry.replaceAll(kept)
            showRecords(kept)
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        fab.isEnabled = !busy
    }

    // ------------------------------------------------------------------
    // Clone operations
    // ------------------------------------------------------------------

    private fun installClone(source: String, userId: Int) {
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val core = BlackBoxCore.get()
                    when {
                        source.startsWith("content://") || source.startsWith("file://") ->
                            core.installPackageAsUser(Uri.parse(source), userId)
                        File(source).isFile ->
                            core.installPackageAsUser(File(source), userId)
                        else ->
                            core.installPackageAsUser(source, userId)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            setBusy(false)
            if (result != null && result.success) {
                val pkg = result.packageName ?: source
                val number = registry.issueNumber(pkg)
                registry.addRecord(CloneRecord(pkg, userId, number, null))
                refresh()
                toast(R.string.install_success)
            } else {
                // Clean up the empty dedicated profile a failed install leaves.
                withContext(Dispatchers.IO) {
                    try {
                        BlackBoxCore.get().deleteUser(userId)
                    } catch (e: Exception) {
                        // no-op: deleting a missing user is harmless
                    }
                }
                val msg = result?.msg
                if (msg.isNullOrEmpty()) toast(R.string.install_fail_no_msg)
                else Toast.makeText(this@ClonesActivity, getString(R.string.install_fail, msg), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchClone(record: CloneRecord) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    BlackBoxCore.get().launchApk(record.packageName, record.userId)
                } catch (e: Exception) {
                    false
                }
            }
            if (!ok) toast(R.string.start_fail)
        }
    }

    private fun deleteClone(record: CloneRecord) {
        setBusy(true)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val core = BlackBoxCore.get()
                    core.uninstallPackageAsUser(record.packageName, record.userId)
                    core.deleteUser(record.userId)
                } catch (e: Exception) {
                    // Even if the engine call fails, dropping the record keeps
                    // the list consistent; the profile wipe is retried by the
                    // engine's own empty-profile cleanup.
                }
            }
            registry.removeRecord(record.userId)
            setBusy(false)
            refresh()
            toast(R.string.remove_success)
        }
    }

    private fun clearCloneData(record: CloneRecord) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    BlackBoxCore.get().clearPackage(record.packageName, record.userId)
                } catch (e: Exception) {
                    // ignore: toast below still reflects the user intent path
                }
            }
            toast(R.string.clear_success)
        }
    }

    // ------------------------------------------------------------------
    // Per-clone menu: rename / reset name / clear data / delete
    // ------------------------------------------------------------------

    private fun showCloneMenu(anchor: View, record: CloneRecord) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.clone_item_menu, popup.menu)
        popup.menu.findItem(R.id.clone_reset_name).isVisible = record.customName != null
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.clone_open -> {
                    launchClone(record)
                    true
                }
                R.id.clone_rename -> {
                    showRenameDialog(record)
                    true
                }
                R.id.clone_reset_name -> {
                    registry.setCustomName(record.userId, null)
                    refresh()
                    true
                }
                R.id.clone_clear -> {
                    MaterialDialog(this).show {
                        title(res = R.string.app_clear)
                        message(text = getString(R.string.app_clear_hint, displayName(record)))
                        positiveButton(res = R.string.done) { clearCloneData(record) }
                        negativeButton(res = R.string.cancel)
                    }
                    true
                }
                R.id.clone_delete -> {
                    MaterialDialog(this).show {
                        title(res = R.string.clone_delete)
                        message(text = getString(R.string.clone_delete_hint, displayName(record)))
                        positiveButton(res = R.string.done) { deleteClone(record) }
                        negativeButton(res = R.string.cancel)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameDialog(record: CloneRecord) {
        MaterialDialog(this).show {
            title(res = R.string.clone_rename)
            input(
                hintRes = R.string.clone_rename,
                prefill = displayName(record)
            ) { _, input ->
                val name = input.toString().trim()
                if (name.isEmpty()) {
                    toast(R.string.clone_name_empty)
                } else {
                    registry.setCustomName(record.userId, name)
                    refresh()
                }
            }
            positiveButton(res = R.string.done)
            negativeButton(res = R.string.cancel)
        }
    }

    // ------------------------------------------------------------------
    // Labels and icons (from the host's own copy of the app)
    // ------------------------------------------------------------------

    fun displayName(record: CloneRecord): String {
        record.customName?.let { return it }
        return "${hostLabel(record.packageName)} ${record.number}"
    }

    private fun hostLabel(pkg: String): String {
        labelCache[pkg]?.let { return it }
        val label = try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        labelCache[pkg] = label
        return label
    }

    fun hostIcon(pkg: String): Drawable {
        iconCache[pkg]?.let { return it }
        val icon = try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            ContextCompat.getDrawable(this, android.R.drawable.sym_def_app_icon)
                ?: packageManager.defaultActivityIcon
        }
        iconCache[pkg] = icon
        return icon
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_PENDING_USER = "pendingUserId"
        private const val POWERED_BY = "Powered by Jepong Devxyz"
    }
}
