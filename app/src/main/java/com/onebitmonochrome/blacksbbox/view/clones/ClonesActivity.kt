package com.onebitmonochrome.blacksbbox.view.clones

import android.os.Bundle
import com.onebitmonochrome.blacksbbox.R
import com.onebitmonochrome.blacksbbox.view.base.BaseActivity

/**
 * LAYOUT-TEST diagnostic build: bare activity, but inflates the FULL
 * Phase B layout (activity_clones.xml). No registry, no engine calls,
 * no view wiring. If this hangs, the layout is the culprit; if it opens,
 * the hang is in the full activity's code.
 */
class ClonesActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clones)
    }
}
