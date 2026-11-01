package com.onebitmonochrome.blacksbbox.view.main

import android.content.Intent
import android.widget.Toast
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import top.niunaijun.blackbox.BlackBoxCore
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
        previewInstalledAppList()
        jump()
    }

    private fun jump() {
        Toast.makeText(applicationContext, "TRACE-1 jump to Clones", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, ClonesActivity::class.java))
        finish()
    }

    private fun previewInstalledAppList(){
        val viewModel = ViewModelProvider(this,InjectionUtil.getListFactory()).get(ListViewModel::class.java)
        viewModel.previewInstalledList()
    }
}