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
import top.niunaijun.blackbox.utils.DiagLog
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clones)
        savedInstanceState?.let { pendingUserId = it.getInt(KEY_PENDING_USER, -1) }

        registry = CloneRegistry(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.title = getString(R.string.app_name)
        toolbar.subtitle = POWERED_BY

        emptyView = findViewById(R.id.emptyView)
        progress = findViewById(R.id.progress)
        fab = findViewById(R.id.fab)

        adapter = ClonesAdapter(
            onRowClick = { record -> launchClone(record) },
            onRowMenu = { anchor, record -> showCloneMenu(anchor, record) }
        )
        val list = findViewById<RecyclerView>(R.id.clonesList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        fab.setOnClickListener {
            // Allocate first so ListActivity can badge against this user;
            // the id stays consumed even if the user backs out (never reused).
            pendingUserId = registry.allocateUserId()
            val intent = Intent(this, ListActivity::class.java)
            intent.putExtra("userID", pendingUserId)
            pickerLauncher.launch(intent)
        }

        showRecords(registry.loadRecords())
    }

    private var validated = false

    override fun onResume() {
        super.onResume()
        // Engine validation runs only AFTER the first frame is on screen and
        // off the main thread. Doing any of this inside onCreate (or inside
        // the service-available callback, which fires synchronously under
        // the core's callback lock) can stall the launch before the window
        // ever draws — the splash-hang fixed here.
        if (!validated) {
            validated = true
            window.decorView.post {
                val callback = Runnable { validateAndRefresh() }
                serviceCallback = callback
                BlackBoxCore.get().addServiceAvailableCallback(callback)
            }
        }
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
            R.id.menu_diag -> {
                startActivity(Intent(this, DiagLogActivity::class.java))
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
                // Round 8: Google services auto-on for every new clone.
                // Installs the host's own Google packages into this fresh
                // profile through the engine API (no switch, no download),
                // so Google-based sign-ins can work inside the clone.
                setBusy(true)
                val gmsReady = withContext(Dispatchers.IO) {
                    try {
                        val core = BlackBoxCore.get()
                        if (core.isSupportGms() && !core.isInstallGms(userId)) {
                            core.installGms(userId)
                        }
                        core.isInstallGms(userId)
                    } catch (e: Exception) {
                        false
                    }
                }
                setBusy(false)
                DiagLog.log("gms auto-on: userId=" + userId + " pkg=" + pkg + " ready=" + gmsReady)
                if (!gmsReady) {
                    Toast.makeText(this@ClonesActivity, R.string.gms_auto_on_unavailable, Toast.LENGTH_LONG).show()
                }
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

/** RecyclerView adapter for the unified clone list. */
class ClonesAdapter(
    private val onRowClick: (CloneRecord) -> Unit,
    private val onRowMenu: (View, CloneRecord) -> Unit
) : RecyclerView.Adapter<ClonesAdapter.CloneVH>() {

    private var rows: List<CloneRecord> = emptyList()

    fun submit(records: List<CloneRecord>) {
        rows = records
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CloneVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_clone, parent, false)
        return CloneVH(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: CloneVH, position: Int) {
        val record = rows[position]
        val activity = holder.itemView.context as? ClonesActivity
        holder.name.text = activity?.displayName(record) ?: record.packageName
        holder.pkg.text = record.packageName
        holder.icon.setImageDrawable(activity?.hostIcon(record.packageName))
        holder.itemView.setOnClickListener { onRowClick(record) }
        holder.itemView.setOnLongClickListener {
            onRowMenu(holder.itemView, record)
            true
        }
        holder.overflow.setOnClickListener { onRowMenu(it, record) }
    }

    class CloneVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.cloneIcon)
        val name: TextView = itemView.findViewById(R.id.cloneName)
        val pkg: TextView = itemView.findViewById(R.id.clonePkg)
        val overflow: ImageButton = itemView.findViewById(R.id.cloneOverflow)
    }
}
