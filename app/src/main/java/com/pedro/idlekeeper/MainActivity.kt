package com.pedro.idlekeeper

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** WebView que não se pausa quando o app vai para segundo plano. */
class KeepAliveWebView(context: android.content.Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE)
    }
}

class Account(val index: Int, val webView: KeepAliveWebView) {
    var lastReload = 0L
    val log = ArrayDeque<String>()
    var loaded = false
}

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val accounts = ArrayList<Account>()
    private lateinit var tabBar: LinearLayout
    private lateinit var container: FrameLayout
    private var current = 0
    private var multiProfile = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("pokeidle", MODE_PRIVATE)
        multiProfile = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        createChannel()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF2A1520.toInt())
        }
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        container = FrameLayout(this)
        root.addView(tabBar, LinearLayout.LayoutParams(-1, -2))
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = accounts.getOrNull(current)?.webView
                if (wv != null && wv.canGoBack()) wv.goBack() else moveTaskToBack(true)
            }
        })

        askNotificationPermission()
        ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        askIgnoreBatteryOptimizations()

        val saved = prefs.getInt("count", 0)
        current = prefs.getInt("current", 0)
        if (saved == 0) askCount(true) else applyCount(minOf(saved, if (multiProfile) 2 else 1))
    }

    // ---------- contas ----------

    private fun askCount(first: Boolean) {
        val max = if (multiProfile) 2 else 1
        if (max == 1) {
            Toast.makeText(
                this,
                "Seu Android System WebView não suporta várias contas. Atualize na Play Store.",
                Toast.LENGTH_LONG
            ).show()
            setCount(1, first)
            return
        }
        val options = (1..max).map { if (it == 1) "1 conta" else "$it contas" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Quantas contas?")
            .setItems(options) { _, which -> setCount(which + 1, first) }
            .setCancelable(!first)
            .show()
    }

    private fun setCount(n: Int, @Suppress("UNUSED_PARAMETER") first: Boolean) {
        prefs.edit().putInt("count", n).apply()
        applyCount(n)
    }

    private fun applyCount(n: Int) {
        while (accounts.size > n) {
            val a = accounts.removeAt(accounts.size - 1)
            container.removeView(a.webView)
            a.webView.destroy()
        }
        while (accounts.size < n) addAccount(accounts.size)
        if (current >= n || current < 0) current = 0
        rebuildTabs()
        select(current)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun addAccount(i: Int) {
        val wv = KeepAliveWebView(this)
        // Conta 1 usa o perfil padrão (mantém o login que você já tinha).
        if (multiProfile && i > 0) {
            try { WebViewCompat.setProfile(wv, "conta${i + 1}") } catch (_: Exception) {}
        }
        val acc = Account(i, wv)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = userAgentString.replace("; wv", "").replace(" Version/4.0", "")
        }
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && url.startsWith("https://pokeidle.io")) {
                    view.evaluateJavascript(WATCHER_JS, null)
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    handler.postDelayed({
                        if (accounts.any { it.webView === view }) view.reload()
                    }, 20000)
                }
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                wv, "PokeBridge", setOf("https://pokeidle.io")
            ) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) onPageMessage(acc, message.data ?: "")
            }
        } else {
            wv.addJavascriptInterface(Bridge(acc), "PokeBridge")
        }

        container.addView(wv, FrameLayout.LayoutParams(-1, -1))
        accounts.add(acc)
        // A conta 1 carrega já; a 2 espera 20s (ou até você tocar na aba), para não
        // sobrecarregar a verificação do Cloudflare com duas páginas abrindo juntas.
        if (i == 0) loadAccount(acc) else handler.postDelayed({ loadAccount(acc) }, 20_000)
    }

    private fun loadAccount(acc: Account) {
        if (acc.loaded || !accounts.contains(acc)) return
        acc.loaded = true
        acc.webView.loadUrl(GAME_URL)
    }

    inner class Bridge(private val acc: Account) {
        @JavascriptInterface
        fun postMessage(s: String) {
            handler.post { onPageMessage(acc, s) }
        }
    }

    // ---------- mensagens da página ----------

    private fun onPageMessage(acc: Account, raw: String) {
        val sep = raw.indexOf('|')
        if (sep < 0) return
        val kind = raw.substring(0, sep)
        val text = raw.substring(sep + 1)
        val now = System.currentTimeMillis()
        addLog(acc, "$kind: $text")
        when (kind) {
            "RELOAD" -> {
                if (now - acc.lastReload < 120_000) return
                acc.lastReload = now
                handler.postDelayed({
                    if (accounts.contains(acc)) acc.webView.reload()
                }, 3000)
            }
        }
    }

    private fun addLog(acc: Account, line: String) {
        acc.log.addLast(line)
        while (acc.log.size > 40) acc.log.removeFirst()
    }

    private fun createChannel() {
        // Remove canais de versões anteriores (avisos de captura/shiny).
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("shiny")
        nm.deleteNotificationChannel(CH_CAPTURA)
    }

    // ---------- abas ----------

    private fun label(i: Int): String = "Conta ${i + 1}"

    private fun rebuildTabs() {
        tabBar.removeAllViews()
        accounts.forEachIndexed { i, _ ->
            val b = Button(this).apply {
                text = label(i)
                isAllCaps = false
                textSize = 13f
                setOnClickListener { select(i) }
            }
            tabBar.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val gear = Button(this).apply {
            text = "⚙"
            setOnClickListener { showMenu() }
        }
        tabBar.addView(gear, LinearLayout.LayoutParams(-2, -2))
        updateTabs()
    }

    private fun updateTabs() {
        for (i in 0 until tabBar.childCount - 1) {
            tabBar.getChildAt(i).alpha = if (i == current) 1f else 0.5f
        }
    }

    private fun select(i: Int) {
        if (i !in accounts.indices) return
        current = i
        prefs.edit().putInt("current", i).apply()
        // Todas ficam visíveis (empilhadas); só traz a escolhida para a frente.
        accounts[i].webView.bringToFront()
        loadAccount(accounts[i])
        accounts[i].webView.requestFocus()
        updateTabs()
    }

    private fun showMenu() {
        val items = arrayOf(
            "Quantidade de contas",
            "Recarregar esta conta",
            "Copiar log de detecção",
            "Verificar atualização (v${currentVersion()})",
            "Parar e sair"
        )
        AlertDialog.Builder(this)
            .setTitle(label(current))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askCount(false)
                    1 -> accounts[current].webView.reload()
                    2 -> copyLog()
                    3 -> checkUpdate(true)
                    4 -> {
                        stopService(Intent(this, KeepAliveService::class.java))
                        finishAndRemoveTask()
                    }
                }
            }
            .show()
    }

    private fun copyLog() {
        val sb = StringBuilder()
        accounts.forEach { a ->
            sb.append("--- Conta ${a.index + 1} ---\n")
            a.log.forEach { sb.append(it).append('\n') }
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", sb.toString()))
        Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show()
    }

    // ---------- atualização via API Intermediária (Worker) ----------

    private class Release(val number: Long, val tag: String, val downloadUrl: String)

    private fun currentVersion(): Long =
        PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))

    private fun checkUpdate(manual: Boolean) {
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        Thread {
            try {
                val rel = fetchLatest()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val cur = currentVersion()
                    if (rel != null && rel.number > cur) {
                        if (manual || prefs.getString("dismissed", "") != rel.tag) offerUpdate(rel)
                    } else if (manual) {
                        Toast.makeText(this, "Você já está na versão mais recente (v$cur)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread {
                    Toast.makeText(this, "Não foi possível verificar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

private fun fetchLatest(): Release? {
    // URL do seu Cloudflare Worker que você testou
    val workerUrl = "https://pokeidle-update-checker.pedrolucasgc18.workers.dev"

    val c = URL(workerUrl).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 15000
    c.requestMethod = "GET"

    val code = c.responseCode
    if (code != 200) throw Exception("Servidor respondeu $code")

    val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    val tag = json.optString("version", "")
    val downloadUrl = json.optString("downloadUrl", "")

    val number = tag.filter { it.isDigit() }.toLongOrNull() ?: return null
    if (downloadUrl.isEmpty()) return null

    return Release(number, tag, downloadUrl)
}

    private fun offerUpdate(rel: Release) {
        AlertDialog.Builder(this)
            .setTitle("Nova versão disponível")
            .setMessage("Versão atual: v${currentVersion()}\nNova versão: ${rel.tag}\n\nAtualizar agora?")
            .setPositiveButton("Atualizar agora") { _, _ -> downloadAndInstall(rel) }
            .setNegativeButton("Manter versão atual") { _, _ ->
                prefs.edit().putString("dismissed", rel.tag).apply()
            }
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstall(rel: Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this,
                "Permita instalar apps desta fonte, volte e use ⚙ > Verificar atualização",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        Toast.makeText(this, "Baixando atualização...", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val file = downloadApk(rel)
                runOnUiThread { installApk(file) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Falha no download: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

   private fun downloadApk(rel: Release): File {
        val dir = File(cacheDir, "updates")
        dir.deleteRecursively()
        dir.mkdirs()
        val out = File(dir, "pokeidle.mobile.apk")
        var url = rel.downloadUrl

        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "PokeIdle-App")

            val code = c.responseCode
            if (code in 301..308) {
                url = c.getHeaderField("Location") ?: throw Exception("Redirecionamento inválido")
            } else if (code == 200) {
                c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                return out
            } else {
                throw Exception("Servidor respondeu $code")
            }
        }
        throw Exception("Redirecionamentos demais")
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun askToken() {
        val et = EditText(this).apply {
            setText(ghToken())
            hint = "Token do GitHub (somente leitura)"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Token do GitHub")
            .setMessage(
                "Necessário só se o repositório for privado. Use um token com permissão " +
                    "somente de leitura (Contents: Read-only) neste repositório."
            )
            .setView(et)
            .setPositiveButton("Salvar") { _, _ ->
                prefs.edit().putString("gh_token", et.text.toString().trim()).apply()
                Toast.makeText(this, "Token salvo", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- permissões e ciclo de vida ----------

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
    }

    @SuppressLint("BatteryLife")
    private fun askIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    // Não pausamos as WebViews ao sair do app, para o jogo continuar rodando.
    override fun onPause() { super.onPause(); accounts.firstOrNull()?.webView?.resumeTimers() }
    override fun onStop() { super.onStop(); accounts.firstOrNull()?.webView?.resumeTimers() }
    override fun onResume() {
        super.onResume()
        accounts.forEach { it.webView.onResume() }
        accounts.firstOrNull()?.webView?.resumeTimers()
        val last = prefs.getLong("last_check", 0L)
        if (accounts.isNotEmpty() && System.currentTimeMillis() - last > 6 * 3600_000L) {
            checkUpdate(false)
        }
    }

    override fun onDestroy() {
        if (isFinishing) accounts.forEach { it.webView.destroy() }
        super.onDestroy()
    }

    companion object {
        const val GAME_URL = "https://pokeidle.io/app"
        const val OWNER = "pedro-lgc"
        const val REPO = "pokeidle-4tela"
        const val CH_CAPTURA = "captura"

        // Observa o texto que aparece na página. Só LÊ; não clica em nada no jogo.
        const val WATCHER_JS = """
(function(){
  if (window.__pkWatch) return; window.__pkWatch = true;
  var send = function(k, t){ try { PokeBridge.postMessage(k + '|' + t); } catch(e) {} };
  var reloadRe = /(recarreg[a-z]*\s+(a\s+)?p[aá]gina|p[aá]gina[^.]{0,40}recarreg|atualize\s+a\s+p[aá]gina|(re)?load\s+(the\s+)?page|refresh\s+(the\s+)?page|recarg[a-z]*\s+la\s+p[aá]gina)/i;
  var scan = function(n, d){
    if (!n) return;
    var t = (n.nodeType === 3 ? n.nodeValue : n.textContent) || '';
    t = t.replace(/\s+/g, ' ').trim();
    if (!t) return;
    if (t.length > 220) {
      if (n.nodeType === 1 && d < 4) {
        var c = n.childNodes;
        for (var i = 0; i < c.length && i < 200; i++) scan(c[i], d + 1);
      }
      return;
    }
    if (reloadRe.test(t)) send('RELOAD', t);
  };
  new MutationObserver(function(ms){
    for (var i = 0; i < ms.length; i++) {
      var m = ms[i];
      if (m.type === 'characterData') { scan(m.target, 3); }
      else { for (var j = 0; j < m.addedNodes.length; j++) scan(m.addedNodes[j], 0); }
    }
  }).observe(document.documentElement, {childList: true, subtree: true, characterData: true});
})();
"""
    }
}
