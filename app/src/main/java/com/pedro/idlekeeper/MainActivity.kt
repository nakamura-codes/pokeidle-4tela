package com.pedro.idlekeeper

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Html
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
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
class KeepAliveWebView(context: Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE)
    }
}

/** Ícone de Poké Ball desenhado em código, usado nas abas. */
class PokeBallDrawable(private val size: Int) : Drawable() {
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF212121.toInt() }

    override fun draw(c: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f
        c.drawCircle(cx, cy, r, dark)
        val inner = r * 0.84f
        val rect = RectF(cx - inner, cy - inner, cx + inner, cy + inner)
        c.drawArc(rect, 180f, 180f, true, red)
        c.drawArc(rect, 0f, 180f, true, white)
        c.drawRect(cx - inner, cy - r * 0.09f, cx + inner, cy + r * 0.09f, dark)
        c.drawCircle(cx, cy, r * 0.30f, dark)
        c.drawCircle(cx, cy, r * 0.16f, white)
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Engrenagem branca desenhada em código (não depende de fonte/emoji do aparelho). */
class GearDrawable(private val size: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    override fun draw(c: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f
        val gear = Path().apply { addCircle(cx, cy, r * 0.66f, Path.Direction.CW) }
        for (k in 0 until 8) {
            val tooth = Path().apply {
                addRect(cx - r * 0.17f, cy - r, cx + r * 0.17f, cy - r * 0.55f, Path.Direction.CW)
            }
            val m = Matrix()
            m.postRotate(k * 45f, cx, cy)
            tooth.transform(m)
            gear.op(tooth, Path.Op.UNION)
        }
        val hole = Path().apply { addCircle(cx, cy, r * 0.30f, Path.Direction.CW) }
        gear.op(hole, Path.Op.DIFFERENCE)
        c.drawPath(gear, paint)
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
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
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFE53935.toInt(), 0xFFC62828.toInt())
            )
        }
        container = FrameLayout(this)
        root.addView(tabBar, LinearLayout.LayoutParams(-1, -2))
        // Faixa vermelha, como a divisória de uma Poké Ball
        root.addView(View(this).apply { setBackgroundColor(0xFF7F0000.toInt()) },
            LinearLayout.LayoutParams(-1, dp(3)))
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        window.statusBarColor = 0xFFE53935.toInt()
        window.navigationBarColor = 0xFF7F0000.toInt()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = accounts.getOrNull(current)?.webView
                if (wv != null && wv.canGoBack()) wv.goBack() else moveTaskToBack(true)
            }
        })

        // 1. Pede permissão de notificação e já chama a exibição da notificação limpa
        askNotificationPermission()

        // 2. Inicia o serviço em segundo plano
        ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        askIgnoreBatteryOptimizations()

        val saved = prefs.getInt("count", 0)
        current = prefs.getInt("current", 0)
        if (saved == 0) askCount(true) else applyCount(minOf(saved, if (multiProfile) 4 else 1))
    }

    // ---------- contas ----------

  private fun askCount(first: Boolean) {
    val max = if (multiProfile) 4 else 1
    if (max == 1) {
        Toast.makeText(
            this,
            "Seu Android System WebView não suporta várias contas. Atualize na Play Store.",
            Toast.LENGTH_LONG
        ).show()
        setCount(1, first)
        return
    }

    var dlg: AlertDialog? = null
    val h = minOf(dp(480), (resources.displayMetrics.heightPixels * 0.85).toInt())

    val dialogView = FrameLayout(this).apply {
        minimumHeight = h

        // 1. Imagem de Fundo (carrega do res/drawable/snorlax_halloween_bg)
        val bgImage = ImageView(context).apply {
            val imageRes = resources.getIdentifier("snorlax_halloween_bg", "drawable", packageName)
            if (imageRes != 0) {
                setImageResource(imageRes)
            } else {
                setBackgroundColor(0xFF1E1119.toInt()) // Cor de contingência caso a imagem não exista
            }
            // Preenche todo o card sem distorcer
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        addView(bgImage, FrameLayout.LayoutParams(-1, h))

        // 2. Conteúdo com opacidade e cartões de botões por cima
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }

        content.addView(TextView(context).apply {
            text = "Quantas contas\ndeseja utilizar?"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, 0xFF000000.toInt())
            setPadding(0, 0, 0, dp(28))
        })

        val subs = listOf(
            "Ideal para focar no seu progresso principal",
            "Gerencie duas contas simultaneamente",
            "Mais contas usam mais memória e bateria",
            "Máximo: pede um celular com bastante RAM"
        )
        val emojis = listOf("👤", "👥", "👥", "👥")
        for (n in 1..max) {
            val lp = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
            content.addView(
                optionCard(if (n == 1) "1 Conta" else "$n Contas", subs[n - 1], emojis[n - 1]) {
                    setCount(n, first); dlg?.dismiss()
                }, lp
            )
        }
        addView(content, FrameLayout.LayoutParams(-1, h))
    }

    dlg = AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(!first)
        .create()
    dlg.window?.setBackgroundDrawable(GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(0xFF1E1119.toInt())
    })
    dlg.show()
}

    private fun optionCard(titleText: String, subText: String, emoji: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xCC1A0E15.toInt())
                setStroke(dp(1), 0xFF9E4B8F.toInt())
            }
            setOnClickListener { onClick() }

            addView(TextView(context).apply {
                text = emoji
                textSize = 28f
                setPadding(0, 0, dp(12), 0)
            })
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                text = titleText
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            texts.addView(TextView(context).apply {
                text = subText
                textSize = 11f
                setTextColor(0xFFD4C9D0.toInt())
                setPadding(0, dp(2), 0, 0)
            })
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        }
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
    private fun addAccount(i: Int, immediate: Boolean = false) {
        val wv = KeepAliveWebView(this)
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

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                handler.post { if (accounts.any { it === acc }) recreateAccount(acc.index) }
                return true
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
        if (i < accounts.size) accounts[i] = acc else accounts.add(acc)
        if (immediate || i == 0) loadAccount(acc)
        else handler.postDelayed({ loadAccount(acc) }, 20_000L * i)
    }

    private fun recreateAccount(i: Int) {
        val old = accounts.getOrNull(i) ?: return
        container.removeView(old.webView)
        old.webView.destroy()
        addAccount(i, true)
        select(current)
        Toast.makeText(this, "Conta ${i + 1} reiniciada por falta de memória", Toast.LENGTH_LONG).show()
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("shiny")
        nm.deleteNotificationChannel(CH_CAPTURA)

        // Criar o canal próprio para o Farm Ativo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CH_FARM,
                "PokeIdle Farm Ativo",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    // ---------- abas ----------

    private fun label(i: Int): String = "Conta ${i + 1}"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rebuildTabs() {
        tabBar.removeAllViews()
        val compact = accounts.size >= 3
        accounts.forEachIndexed { i, _ ->
            val tab = TextView(this).apply {
                text = if (compact) "${i + 1}" else label(i)
                textSize = if (compact) 16f else 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(if (compact) dp(6) else dp(12), dp(9), if (compact) dp(8) else dp(14), dp(9))
                compoundDrawablePadding = if (compact) dp(5) else dp(8)
                setCompoundDrawablesRelativeWithIntrinsicBounds(PokeBallDrawable(dp(20)), null, null, null)
                setOnClickListener { select(i) }
            }
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.setMargins(dp(4), 0, dp(4), 0)
            tabBar.addView(tab, lp)
        }
        val gear = ImageView(this).apply {
            setImageDrawable(GearDrawable(dp(22)))
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33000000)
                setStroke(dp(1), 0x66FFFFFF)
            }
            setOnClickListener { showMenu() }
        }
        val glp = LinearLayout.LayoutParams(dp(40), dp(40))
        glp.setMargins(dp(4), 0, dp(2), 0)
        tabBar.addView(gear, glp)
        updateTabs()
    }

    private fun updateTabs() {
        for (i in 0 until tabBar.childCount - 1) {
            val tab = tabBar.getChildAt(i) as? TextView ?: continue
            val selected = i == current
            tab.background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                if (selected) {
                    colors = intArrayOf(0xFFFF6F60.toInt(), 0xFFE53935.toInt())
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    setStroke(dp(2), 0xFFFFFFFF.toInt())
                } else {
                    setColor(0x40000000)
                    setStroke(dp(1), 0x55FFFFFF)
                }
            }
            tab.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFFFD6D6.toInt())
            tab.alpha = if (selected) 1f else 0.85f
        }
    }

    private fun select(i: Int) {
        if (i !in accounts.indices) return
        current = i
        prefs.edit().putInt("current", i).apply()
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

    // ---------- atualização via Worker ----------

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
        val c = URL(UPDATE_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "PokeIdle-App")
        val code = c.responseCode
        if (code != 200) throw Exception("servidor respondeu $code")

        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("version", "")
        val downloadUrl = json.optString("downloadUrl", "")
        val number = tag.filter { it.isDigit() }.toLongOrNull() ?: return null
        if (!downloadUrl.startsWith("https://")) return null
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
            if (!url.startsWith("https://")) throw Exception("endereço de download inseguro")
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "PokeIdle-App")

            val code = c.responseCode
            if (code in 301..308) {
                url = c.getHeaderField("Location") ?: throw Exception("redirecionamento inválido")
            } else if (code == 200) {
                c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                return out
            } else {
                throw Exception("servidor respondeu $code")
            }
        }
        throw Exception("redirecionamentos demais")
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ---------- permissões e ciclo de vida ----------

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            } else {
                showFarmNotification(this)
            }
        } else {
            showFarmNotification(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showFarmNotification(this)
        }
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
        const val UPDATE_URL = "https://pokeidle-update-checker.pedrolucasgc18.workers.dev?json=true"
        const val CH_CAPTURA = "captura"
        const val CH_FARM = "pokeidle_farm_channel"

        /** Exibe a notificação com o layout formatado em HTML e o botão de Encerrar */
        fun showFarmNotification(context: Context) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Intent para abrir o app ao clicar
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
            )

            // Intent acionada pelo botão Encerrar
            val stopIntent = Intent(context, ActionReceiver::class.java).apply {
                action = "ACTION_STOP_APP"
            }
            val stopPendingIntent = PendingIntent.getBroadcast(
                context, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE
            )

            val titleFormatted = Html.fromHtml(
                "<b><font color='#FF4500'>PokeIdle</font></b> • <font color='#00E676'><b>Farm Ativo ⚡</b></font>",
                Html.FROM_HTML_MODE_LEGACY
            )

            val textFormatted = Html.fromHtml(
                "Status: <font color='#FFD700'><b>Em Execução</b></font>",
                Html.FROM_HTML_MODE_LEGACY
            )

            val notification = NotificationCompat.Builder(context, CH_FARM)
                .setSmallIcon(android.R.drawable.ic_menu_compass) // Troque por R.drawable.ic_pokeball se houver
                .setContentTitle(titleFormatted)
                .setContentText(textFormatted)
                .setColor(0xFFE53935.toInt())
                .setContentIntent(openAppPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Encerrar", stopPendingIntent)
                .build()

            notificationManager.notify(1001, notification)
        }

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
