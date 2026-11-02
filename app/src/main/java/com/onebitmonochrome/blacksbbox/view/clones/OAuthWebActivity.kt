package com.onebitmonochrome.blacksbbox.view.clones

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.onebitmonochrome.blacksbbox.view.base.BaseActivity
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.utils.DiagLog

/**
 * Round 6: in-container OAuth web login.
 *
 * When a cloned app starts an ACTION_VIEW to a Facebook/Meta login URL,
 * ActivityManagerCommonProxy rewrites the launch to this host activity
 * instead of letting it escape to the real browser (whose OAuth redirect
 * back can never reach the clone). The login runs in this WebView, inside
 * DevxyzClone. When the flow navigates to its redirect target (parsed
 * from the start URL's redirect_uri parameter, or any non-http scheme),
 * the result is delivered back: as the activity result when the clone
 * asked for one, otherwise by firing the redirect intent into the
 * caller's clone through the virtual ActivityManager.
 */
class OAuthWebActivity : BaseActivity() {

    private var webView: WebView? = null
    private var startUrl: String = ""
    private var redirectBase: String? = null
    private var userId: Int = 0
    private var forResult: Boolean = false
    private var delivered: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Sign in"
        startUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        userId = intent.getIntExtra(EXTRA_USER_ID, 0)
        forResult = intent.getBooleanExtra(EXTRA_FOR_RESULT, false)
        redirectBase = computeRedirectBase(startUrl)
        DiagLog.log(
            "OAuthWeb start userId=" + userId + " forResult=" + forResult +
                " url=" + startUrl + " redirectBase=" + redirectBase
        )

        val wv = WebView(this)
        webView = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.javaScriptCanOpenWindowsAutomatically = true
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url?.toString() ?: return false
                if (isCompletion(url)) {
                    deliver(url)
                    return true
                }
                return false
            }

            @Deprecated("Deprecated in API 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (isCompletion(url)) {
                    deliver(url)
                    return true
                }
                return false
            }
        }
        setContentView(wv)
        if (startUrl.isNotEmpty()) {
            wv.loadUrl(startUrl)
        }
    }

    private fun computeRedirectBase(url: String): String? {
        return try {
            val r = Uri.parse(url).getQueryParameter("redirect_uri") ?: return null
            val ru = Uri.parse(r)
            val scheme = ru.scheme ?: return null
            val host = ru.host
            if (host != null) "$scheme://$host" else "$scheme:"
        } catch (t: Throwable) {
            null
        }
    }

    private fun isCompletion(url: String): Boolean {
        val base = redirectBase
        if (base != null && url.startsWith(base)) {
            return true
        }
        val scheme = try {
            Uri.parse(url).scheme
        } catch (t: Throwable) {
            null
        }
        return scheme != null && scheme != "http" && scheme != "https" &&
            scheme != "about" && scheme != "data" && scheme != "javascript"
    }

    private fun deliver(url: String) {
        if (delivered) {
            return
        }
        delivered = true
        DiagLog.log("OAuthWeb completion forResult=$forResult url=$url")
        if (forResult) {
            val data = Intent().apply { this.data = Uri.parse(url) }
            setResult(RESULT_OK, data)
            finish()
        } else {
            try {
                val back = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                BlackBoxCore.getBActivityManager().startActivity(back, userId)
            } catch (t: Throwable) {
                DiagLog.log("OAuthWeb deliver-into-clone failed: $t")
            }
            finish()
        }
    }

    @Deprecated("Deprecated in API 33")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        DiagLog.log("OAuthWeb cancelled by user")
        setResult(RESULT_CANCELED)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_URL = "oauth_url"
        const val EXTRA_USER_ID = "oauth_user_id"
        const val EXTRA_FOR_RESULT = "oauth_for_result"
    }
}
