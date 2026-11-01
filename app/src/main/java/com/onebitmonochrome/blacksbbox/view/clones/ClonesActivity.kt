package com.onebitmonochrome.blacksbbox.view.clones

import android.os.Bundle
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.view.base.BaseActivity

/**
 * STRIP diagnostic build: bare-minimum home screen.
 * No registry, no engine calls, no RecyclerView — just prove this activity
 * can open as the home after WelcomeActivity.
 */
class ClonesActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clones_strip)
    }
}
