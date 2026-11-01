package com.onebitmonochrome.blacksbbox.view.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import top.niunaijun.blackbox.BlackBoxCore
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.util.InjectionUtil
import com.onebitmonochrome.blacksbbox.view.clones.ClonesActivity
import com.onebitmonochrome.blacksbbox.view.list.ListViewModel

class WelcomeActivity : AppCompatActivity() {

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        jump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // STRIP diagnostic: draw a real first frame so we can see whether
        // this window draws at all before handing over to the home activity.
        setContentView(R.layout.activity_welcome_status)
        previewInstalledAppList()
        jump()
    }

    private fun jump() {
        // Phase B: the unified Clones screen is the home now.
        // MainActivity (per-profile pager) stays reachable from its menu.
        startActivity(Intent(this, ClonesActivity::class.java))
        finish()
    }

    private fun previewInstalledAppList(){
        val viewModel = ViewModelProvider(this,InjectionUtil.getListFactory()).get(ListViewModel::class.java)
        viewModel.previewInstalledList()
    }
}