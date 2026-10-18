// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.web

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.webkit.WebViewAssetLoader
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.ui.chat.ChatSessionController
import io.agents.pokeclaw.ui.chat.ChatSessionUiState
import io.agents.pokeclaw.ui.chat.ConversationStore
import io.agents.pokeclaw.ui.chat.TaskFlowController
import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.Executors

/**
 * Claude UI chat surface, backed by the real task flow.
 *
 * Renders `assets/claude-ui/index.html` from the packaged APK and drives it
 * from [io.agents.pokeclaw.ui.chat.TaskFlowController] through a
 * [WebChatSink]. The orchestration is *not* reimplemented here — the same
 * controller backs the Compose chat screen; this is the second sink.
 *
 * Also a design-review surface, so the static sample turns (tool card, MCP
 * confirm card, memory chips) stay in `index.html` as markup samples. Those
 * have no live data source because `ChatMessage.toolSteps` is never populated
 * by the controller today.
 *
 * Not exported, so no other app can reach it.
 */
class ClaudeUiPreviewActivity : BaseActivity() {

    private lateinit var webView: WebView
    private var qaReceiver: BroadcastReceiver? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val conversationStore by lazy { ConversationStore(this) }

    private val sink by lazy {
        WebChatSink { messages -> publish(messages) }
    }

    private val taskFlowController by lazy {
        TaskFlowController(
            activity = this,
            executor = executor,
            appViewModel = appViewModel,
            chatSessionController = chatSessionController,
            currentConversationId = { conversationStore.currentConversationId },
            sink = sink,
            onPersistConversation = { saveChat() },
        )
    }

    private val chatSessionController by lazy {
        ChatSessionController(
            activity = this,
            executor = executor,
            sink = ChatSessionUiState(
                messages = mutableStateListOf(),
                modelStatusState = mutableStateOf("No model loaded"),
                awaitingReplyState = mutableStateOf(false),
                inputEnabledState = mutableStateOf(true),
                downloadingState = mutableStateOf(false),
                downloadProgressState = mutableStateOf(0),
                sessionTokensState = mutableStateOf(0),
                sessionCostState = mutableStateOf(0.0),
            ),
            onPersistConversation = { saveChat() },
            onRefreshSidebarHistory = {},
            isTaskRunning = { appViewModel.isTaskRunning() },
        )
    }

    private fun saveChat() {
        // No conversation picker on this surface, so there is nothing to write
        // back to. The controller requires the hook; keep it inert rather than
        // inventing a storage policy this page does not have a UI for.
    }

    /**
     * JS → Kotlin: the composer hands us the text and we run a real task.
     *
     * Called on the WebView's JavaScript thread, so everything the task flow
     * touches is marshalled to the main thread first.
     */
    private val chatBridge = object {
        @JavascriptInterface
        fun send(text: String?) {
            val payload = text?.trim().orEmpty()
            if (payload.isEmpty()) return
            XLog.e(TAG, "[chat] send(${payload.length} chars)")
            runOnUiThread { taskFlowController.sendTask(payload) }
        }
    }

    /**
     * Kotlin → JS: hand the page a full snapshot to draw.
     *
     * A snapshot rather than a diff. The message count per task is small, and a
     * missed delta desyncs the page with no way to recover — a full redraw can
     * always be re-derived from [WebChatSink]'s list.
     */
    private fun publish(messages: List<WebChatSink.WebMessage>) {
        if (!::webView.isInitialized) return
        val json = buildString {
            append('[')
            messages.forEachIndexed { i, m ->
                if (i > 0) append(',')
                appendJsonObject(m)
            }
            append(']')
        }
        // The snapshot is spliced in as a literal rather than handed over as a
        // string: evaluateJavascript takes source text, so a quoted string
        // would have to be unquoted and re-parsed on the far side anyway.
        // No result callback — renderLiveRaw returns undefined; if the page
        // throws, the error surfaces via the console bridge instead.
        webView.post {
            webView.evaluateJavascript("window.__cds && window.__cds.renderLiveRaw($json);", null)
        }
    }

    /** Minimal JSON writer — messages are plain strings, never user-supplied HTML. */
    private fun StringBuilder.appendJsonObject(m: WebChatSink.WebMessage) {
        append("{\"role\":"); appendJsonString(m.role)
        append(",\"content\":"); appendJsonString(m.content)
        append(",\"isTypingIndicator\":").append(m.isTypingIndicator)
        append(",\"modelName\":"); if (m.modelName == null) append("null") else appendJsonString(m.modelName)
        append(",\"toolSteps\":[")
        m.toolSteps.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"name\":"); appendJsonString(s.name)
            append(",\"summary\":"); appendJsonString(s.summary)
            append(",\"success\":").append(s.success).append('}')
        }
        append("]}")
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    append("\\u%04x".format(c.code))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    /**
     * JS 桥，唯一目的是给 localStorage 配一个**已知为真**的对照组。
     *
     * SharedPreferences 一定持久，localStorage 不一定。当两者在同一张截图里
     * 并排显示时，「上次选了什么」就不再依赖被测系统自己的记忆：
     *
     *   原生记住=sage  而  localStorage=clay  → WebView 存储被清，写入丢失
     *   原生记住=clay  而  localStorage=sage  → 写进去了，读回来却用错值
     *   两者都=clay                            → 写入路径根本没走到
     *
     * 没有对照组就分不清「没写」和「写丢了」——两者在页面外观上完全一样。
     */
    private val qaBridge = object {
        @JavascriptInterface
        fun report(json: String?, accent: String?, mode: String?) {
            XLog.e(TAG, "[cdsQA] $json")
            getSharedPreferences(QA_PREFS, MODE_PRIVATE).edit()
                .putString(QA_PREFS_JSON, json)
                .putString(QA_PREFS_ACCENT, accent)
                .putString(QA_PREFS_MODE, mode)
                .putLong(QA_PREFS_AT, System.currentTimeMillis())
                .apply()
        }

        /** 上次离开时页面实际生效的配色，`accent|mode`。读在写之前，见 cds.js 的注释。 */
        @JavascriptInterface
        fun lastState(): String? {
            val p = getSharedPreferences(QA_PREFS, MODE_PRIVATE)
            if (!p.contains(QA_PREFS_ACCENT)) return null
            return "${p.getString(QA_PREFS_ACCENT, "?")}|${p.getString(QA_PREFS_MODE, "?")}"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // adjustResize is declared in the manifest; re-assert it here because
        // BaseActivity may set its own soft-input mode.
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        )

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            // Without this the whole mockup is inert: cds.js drives theme
            // persistence, scroll pinning, rate-limit backoff and the model
            // switcher. The HTML shell still rendered, so this looked fine.
            settings.javaScriptEnabled = true
            // localStorage-backed theme persistence needs DOM storage enabled
            settings.domStorageEnabled = true
            addJavascriptInterface(qaBridge, "PokeClawQA")
            addJavascriptInterface(chatBridge, "PokeClawChat")
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            // The mockup is entirely local; never let it navigate off-device.
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ) = assetLoader.shouldInterceptRequest(request.url)

                @Suppress("OverridingDeprecatedMember")
                override fun shouldInterceptRequest(
                    view: WebView,
                    url: String
                ) = assetLoader.shouldInterceptRequest(Uri.parse(url))

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = true
            }
            // Without a WebChromeClient the page's console output never leaves
            // the renderer — `adb logcat` shows nothing, which is NOT evidence
            // that localStorage worked. cds.js signals storage failures with
            // console.warn, so forward it and the question becomes answerable.
            //
            // XLog.e, not XLog.i/w: ClawApplication.onCreate calls
            // XLog.setDEBUG(BuildConfig.DEBUG), so in a release build every
            // level except e/wtf is stripped from logcat. QA runs release
            // packages (a debug build cannot overwrite-install, see
            // CLAUDE.local.md §13.2), so anything else here is invisible.
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    XLog.e(TAG, "[web] ${msg.messageLevel()} ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                    return true
                }
            }
            // A desktop-width layout in a narrow WebView: without this the page
            // auto-zooms and the composer drifts off the bottom.
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            loadUrl(MOCKUP_URL)
        }
        setContentView(webView)
        // Scheduled AFTER setContentView, not inside the apply{} above: a
        // View that is not yet attached has no handler to drain its message
        // queue, so postDelayed there silently never runs. That produced a
        // probe which fired on the first launch and never again — the exact
        // absence-of-evidence failure mode this diagnostic exists to avoid.
        webView.postDelayed({ dumpStorage() }, PROBE_DELAY_MS)
        XLog.i(TAG, "onCreate: loaded $MOCKUP_URL")

        // Registered at runtime rather than declared in the manifest: a
        // manifest receiver here would have to be exported to be reachable by
        // adb, which would hand any app on the device a way to drive this page.
        qaReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_SET_ACCENT -> setAccentForQa(intent.getStringExtra("key") ?: return)
                    ACTION_DUMP -> dumpDom()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SET_ACCENT)
            addAction(ACTION_DUMP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(qaReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(qaReceiver, filter)
        }
    }

    /**
     * QA hook: `adb shell am broadcast -a <PKG>.SET_ACCENT --es key sage`.
     *
     * Tapping the swatch by coordinate proved unreliable (uiautomator cannot
     * see inside the WebView, and a missed tap silently leaves the picker
     * closed — indistinguishable from "the theme did not change"). Driving
     * cds.js's own `__cds.setAccent` exercises the identical write path the UI
     * does, so a failure here is a storage failure, not a tap failure.
     */
    @Suppress("unused")
    fun setAccentForQa(key: String) {
        val js = "window.__cds && window.__cds.setAccent('$key');" +
            "'dispatched';"
        webView.evaluateJavascript(js) { value ->
            XLog.e(TAG, "setAccent($key) -> $value")
            dumpStorage()
        }
    }

    /**
     * One-shot DOM probe: `adb shell am broadcast -a <PKG>.DUMP_DOM`.
     *
     * Screenshots showed cds.js source text painted at the top of the page while
     * cds.js demonstrably executed (its console output reached logcat). Those two
     * facts are contradictory, and guessing between them wastes a build each
     * time — so read the document itself: script count, each script's src, and
     * whether the raw source appears as a text node.
     */
    private fun dumpDom() {
        val js = (
            "var o={};" +
                "o.url=String(location.href);" +
                "o.scripts=document.scripts.length;" +
                "var srcs=[];for(var i=0;i<document.scripts.length;i++){" +
                "srcs.push(String(document.scripts[i].src||'(inline)'))+}o.srcs=srcs.join(',');" +
                "var panel=document.getElementById('cdsQaPanel');" +
                "o.panelExists=!!panel;" +
                "o.panelChildNodes=panel?panel.childNodes.length:-1;" +
                "o.panelSpans=panel?panel.querySelectorAll('span').length:-1;" +
                "o.panelText=panel?String(panel.textContent).slice(0,120):'';" +
                "o.bodyStart=String(document.body.innerHTML).slice(0,160);" +
                "o.asideExists=!!document.querySelector('aside');" +
                "o.feedExists=!!document.getElementById('feed');" +
                "o.cdsExists=!!window.__cds;" +
                "JSON.stringify(o);"
            )
        webView.evaluateJavascript(js) { value ->
            XLog.e(TAG, "domDump: $value")
        }
    }

    override fun onDestroy() {
        qaReceiver?.let {
            runCatching { unregisterReceiver(it) }
            qaReceiver = null
        }
        // Mirrors WebActivity: a WebView that is never destroyed leaks the whole
        // Activity context along with it.
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dumpStorage() {
        // One-shot diagnostic. Reads the three keys cds.js owns AND the
        // attributes it actually applied to <html>. Comparing the two is what
        // separates the two candidate causes: if localStorage holds the new
        // accent but data-theme is still the old one, the read-back is broken;
        // if localStorage still holds the default, the write never happened.
        // Both look identical from the outside — the theme just resets.
        val js = (
            "var o={};" +
                "['cds-mockup-theme','cds-mockup-accent','cds-mockup-base'].forEach(function(k){" +
                "try{o[k]=String(localStorage.getItem(k));}catch(e){o[k]='THREW:'+e;}});" +
                "try{o['applied-mode']=String(document.documentElement.getAttribute('data-mode'));" +
                "o['applied-theme']=String(document.documentElement.getAttribute('data-theme'));" +
                "o['applied-base']=String(document.documentElement.getAttribute('data-base'));" +
                "o['href']=String(location.href);o['origin']=String(location.origin);" +
                "o['keys']=String(localStorage.length);}catch(e){o['attr']='THREW:'+e;}" +
                "JSON.stringify(o);"
            )
        webView.evaluateJavascript(js) { value ->
            // e, not i — see the XLog.setDEBUG note on the chrome client above.
            XLog.e(TAG, "storageProbe: $value")
        }
    }

    companion object {
        private const val TAG = "ClaudeUiPreview"
        private const val ASSET_DOMAIN = "appassets.androidplatform.net"
        private const val PROBE_DELAY_MS = 2500L
        private const val ACTION_SET_ACCENT = "io.agents.pokeclaw.SET_ACCENT"
        private const val ACTION_DUMP = "io.agents.pokeclaw.DUMP_DOM"
        private const val QA_PREFS = "claude_ui_qa"
        private const val QA_PREFS_JSON = "last_report"
        private const val QA_PREFS_ACCENT = "last_accent"
        private const val QA_PREFS_MODE = "last_mode"
        private const val QA_PREFS_AT = "last_seen_at"

        /**
         * Served over HTTPS, NOT `file:///android_asset/...`.
         *
         * `file://` is an **opaque origin** in Chromium, so `localStorage` is
         * unavailable there. `cds.js` wraps every access in try/catch and falls
         * back to the default theme, so the symptom was a mockup that switches
         * theme perfectly but silently forgets it on every reopen — and because
         * the fallback is silent, it looks like a rendering bug rather than a
         * storage one.
         *
         * WebViewAssetLoader serves the same packaged assets over
         * `https://appassets.androidplatform.net/`, which is a real, persistent
         * origin, so DOM storage works as the page expects. It also never touches
         * the network — the loader intercepts before any socket is opened.
         */
        private const val MOCKUP_URL =
            "https://appassets.androidplatform.net/assets/claude-ui/index.html"
    }

    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }
}
