package com.onebitmonochrome.blacksbbox.view.clones

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.data.CloneRecord
import com.onebitmonochrome.blacksbbox.view.base.BaseActivity

/**
 * CODE-TEST 1 diagnostic: full layout + toolbar/adapter/FAB wiring,
 * but NO CloneRegistry, NO engine calls, NO service callback.
 * Adapter and record types are compiled in; the list stays empty.
 */
class ClonesActivity : BaseActivity() {

    private lateinit var adapter: ClonesAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clones)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.title = getString(R.string.app_name)
        toolbar.subtitle = "Powered by Jepong Devxyz"

        emptyView = findViewById(R.id.emptyView)
        emptyView.visibility = android.view.View.VISIBLE

        adapter = ClonesAdapter(
            onRowClick = { },
            onRowMenu = { _, _ -> }
        )
        val list = findViewById<RecyclerView>(R.id.clonesList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { }
        adapter.submit(emptyList<CloneRecord>())
    }
}
