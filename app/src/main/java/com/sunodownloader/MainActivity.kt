package com.sunodownloader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressbar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultTitle: TextView
    private lateinit var resultSize: TextView
    private lateinit var logText: TextView
    private var isBusy = false
    private var coverData: ByteArray? = null
    private var trackTitle: String = ""
    private var songId: String = ""
    private var selectedDir: File? = null

    private val outputDir: File
        get() = selectedDir ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Suno").apply { mkdirs() }

    private val logFile: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "suno_log.txt")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        progressbar = findViewById(R.id.progressbar)
        statusText = findViewById(R.id.statusText)
        resultCard = findViewById(R.id.resultCard)
        resultTitle = findViewById(R.id.resultTitle)
        resultSize = findViewById(R.id.resultSize)
        logText = findViewById(R.id.logText)

        // Кнопки
        findViewById<Button>(R.id.btnDownload).setOnClickListener {
            val url = findViewById<EditText>(R.id.etUrl).text.toString().trim()
            startDownload(url)
        }
        findViewById<Button>(R.id.btnPaste).setOnClickListener {
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    findViewById<EditText>(R.id.etUrl).setText(text)
                }
            } catch (_: Exception) {}
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            findViewById<EditText>(R.id.etUrl).setText("")
        }
        findViewById<Button>(R.id.btnOpenFolder).setOnClickListener {
            openFolder()
        }

        // Кнопка выбора папки — диалог со стандартными папками
        findViewById<Button>(R.id.btnBrowse).setOnClickListener {
            showFolderDialog()
        }

        // Папка сохранения
        findViewById<TextView>(R.id.tvFolder).text = "Папка: ${outputDir.absolutePath}"

        // Enter в поле ввода
        findViewById<EditText>(R.id.etUrl).setOnEditorActionListener { v, _, _ ->
            val url = v.text.toString().trim()
            startDownload(url)
            true
        }

        // Настройка WebView
        webView.visibility = View.GONE
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(DownloadBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (!isBusy) return
                runOnUiThread {
                    progressbar.progress = newProgress / 2
                }
            }
        }

        // Извлекаем ffmpeg из assets при первом запуске
        extractFFmpeg()

        addLog("Приложение запущено.")
    }

    private fun extractFFmpeg() {
        val target = File(filesDir, "ffmpeg")
        if (target.exists() && target.canExecute()) return
        try {
            assets.open("ffmpeg").use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            target.setExecutable(true, false)
            addLog("FFmpeg извлечён: ${target.length() / 1024} КБ")
        } catch (e: Exception) {
            addLog("Не удалось извлечь FFmpeg: ${e.message}")
        }
    }

    private fun showFolderDialog() {
        val options = arrayOf(
            "Загрузки/Suno",
            "Музыка/Suno",
            "Документы/Suno",
            "Видео/Suno"
        )
        val paths = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Suno"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Suno"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Suno"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Suno")
        )
        AlertDialog.Builder(this)
            .setTitle("Выберите папку для сохранения")
            .setItems(options) { _, which ->
                val dir = paths[which].apply { mkdirs() }
                selectedDir = dir
                findViewById<TextView>(R.id.tvFolder).text = "Папка: ${dir.absolutePath}"
                addLog("Папка изменена: ${dir.absolutePath}")
            }
            .show()
    }

    inner class DownloadBridge {
        @JavascriptInterface
        fun onAudioReady(b64: String, title: String, size: Long) {
            runOnUiThread {
                onAudioReceived(b64, title, size)
            }
        }

        @JavascriptInterface
        fun onError(msg: String) {
            runOnUiThread {
                showError(msg)
            }
        }

        @JavascriptInterface
        fun log(msg: String) {
            runOnUiThread {
                addLog(msg)
            }
        }
    }

    private fun addLog(msg: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg\n"

        // Экран
        logText.text = logText.text.toString() + line

        // Файл
        try {
            logFile.appendText(line)
        } catch (_: Exception) {}
    }

    fun startDownload(url: String) {
        if (isBusy) return
        if (url.isBlank()) {
            Toast.makeText(this, "Вставьте ссылку", Toast.LENGTH_SHORT).show()
            return
        }

        val idMatch = Regex("/song/([a-f0-9\\-]{36})").find(url)
        val shortMatch = Regex("/s/([A-Za-z0-9]+)").find(url)

        if (idMatch == null && shortMatch == null) {
            Toast.makeText(this, "Не распознана ссылка Suno", Toast.LENGTH_LONG).show()
            return
        }

        isBusy = true
        resultCard.visibility = View.GONE
        setButtonState(true)
        progressbar.progress = 0
        statusText.text = "Запуск..."
        addLog("=== Новая загрузка ===")
        addLog("URL: $url")

        Thread {
            try {
                // Разворачиваем короткую ссылку
                var fullUrl = url
                if (shortMatch != null && idMatch == null) {
                    try {
                        val conn = java.net.URL(fullUrl).openConnection() as java.net.HttpURLConnection
                        conn.instanceFollowRedirects = false
                        conn.requestMethod = "HEAD"
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        val location = conn.getHeaderField("Location") ?: conn.url.toString()
                        conn.disconnect()
                        fullUrl = location
                        addLog("Редирект: $fullUrl")
                    } catch (_: Exception) {}
                }

                val sid = Regex("/song/([a-f0-9\\-]{36})").find(fullUrl)?.groupValues?.get(1)
                    ?: shortMatch!!.groupValues[1]

                songId = sid
                runOnUiThread {
                    statusText.text = "Подключение..."
                    addLog("Song ID: $sid")
                }

                // Загружаем usesuno.com через UI-поток
                val loaded = CountDownLatch(1)
                var loadError: String? = null

                runOnUiThread {
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            loaded.countDown()
                        }
                        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                            loadError = description ?: "HTTP $errorCode"
                            loaded.countDown()
                        }
                    }
                    webView.loadUrl("https://usesuno.com/tools/downloader/")
                }

                loaded.await(30, TimeUnit.SECONDS)

                if (loadError != null) {
                    addLog("Ошибка загрузки страницы: $loadError")
                    runOnUiThread { showError("Ошибка загрузки: $loadError") }
                    return@Thread
                }

                addLog("Страница загружена. Ожидание Cloudflare...")

                // Ждём SunoAudioDecoder
                var decoderReady = false
                for (i in 0..60) {
                    Thread.sleep(500)
                    val check = CountDownLatch(1)
                    var result = ""
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "(document.readyState === 'complete' && !!window.SunoAudioDecoder) ? 'OK' : document.title || 'loading'",
                            { value -> result = value; check.countDown() }
                        )
                    }
                    check.await(5, TimeUnit.SECONDS)
                    if (result.contains("\"OK\"")) {
                        decoderReady = true
                        break
                    }
                    if (i % 10 == 9) {
                        runOnUiThread {
                            statusText.text = "Ожидание... (${i * 0.5}с)"
                            addLog("Ждём декодер... (${i * 0.5}с)")
                        }
                    }
                }

                if (!decoderReady) {
                    addLog("Cloudflare не прошёл за 30 сек")
                    runOnUiThread {
                        showError("Сервер не отвечает.\nCloudflare не прошёл.\nПопробуйте через минуту.")
                    }
                    return@Thread
                }

                addLog("Декодер готов. Скачивание аудио...")
                runOnUiThread {
                    statusText.text = "Скачивание трека..."
                    progressbar.progress = 50
                }

                // Вызываем getAudio через UI-поток
                val jsCode = """
                    (async function() {
                        var contentId = '$songId';
                        try {
                            var blob = await window.SunoAudioDecoder.getAudio(contentId);
                            if (!(blob instanceof Blob)) {
                                Android.onError('Аудио: не Blob');
                                return;
                            }
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                var b64 = reader.result.split(',')[1];
                                fetch('/api/proxy?url=https%3A%2F%2Fsuno.com%2Fsong%2F$songId')
                                    .then(function(r) { return r.ok ? r.text() : ''; })
                                    .then(function(html) {
                                        var m = html.match(/property="og:title"[^>]+content="([^"]*)"/);
                                        var title = m ? m[1] : 'suno_track';
                                        Android.onAudioReady(b64, title, blob.size);
                                    })
                                    .catch(function() {
                                        Android.onAudioReady(b64, 'suno_track', blob.size);
                                    });
                            };
                            reader.readAsDataURL(blob);
                        } catch(e) {
                            Android.onError(e.message || String(e));
                        }
                    })()
                """.trimIndent()

                runOnUiThread {
                    webView.evaluateJavascript(jsCode, null)
                }

                // Ждём результат (до 90 сек)
                Thread.sleep(90000)

            } catch (e: Exception) {
                addLog("ИСКЛЮЧЕНИЕ: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                runOnUiThread { showError("Ошибка: ${e.message}") }
            }
        }.start()
    }

    private fun onAudioReceived(b64: String, title: String, size: Long) {
        try {
            trackTitle = title
            val audioBytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            addLog("Аудио получено: ${size / 1024} КБ, Название: $title")

            statusText.text = "Сохранение..."
            progressbar.progress = 80

            Thread {
                // Скачиваем обложку
                var cover: ByteArray? = null
                try {
                    val coverUrl = "https://cdn2.suno.ai/image_large_${songId}.jpeg"
                    val conn = java.net.URL(coverUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    if (conn.responseCode == 200) {
                        cover = conn.inputStream.readBytes()
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
                coverData = cover
                if (cover != null) addLog("Обложка скачана: ${cover.size / 1024} КБ")

                saveFile(audioBytes, title)
            }.start()

        } catch (e: Exception) {
            addLog("Ошибка приёма: ${e.message}")
            showError("Ошибка: ${e.message}")
        }
    }

    private fun saveFile(audioBytes: ByteArray, title: String) {
        try {
            val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ").trim('.', ' ').take(150)
            val fileName = if (safeName.isNotEmpty()) safeName else "track"

            // Формат из RadioButton
            val wantMp3 = findViewById<RadioButton>(R.id.rbMp3).isChecked

            addLog("Сохраняю в: ${outputDir.absolutePath}")
            outputDir.mkdirs()

            var path: File
            if (wantMp3) {
                path = File(outputDir, "$fileName.mp3")
                var i = 1
                while (path.exists()) {
                    path = File(outputDir, "$fileName($i).mp3")
                    i++
                }
            } else {
                path = File(outputDir, "$fileName.m4a")
                var i = 1
                while (path.exists()) {
                    path = File(outputDir, "$fileName($i).m4a")
                    i++
                }
            }

            val tmpM4a = File(outputDir, "_tmp_${songId.take(8)}.m4a")
            tmpM4a.writeBytes(audioBytes)
            addLog("Временный файл M4A: ${tmpM4a.length() / 1024} КБ")

            val ffmpegPath = findFFmpeg()
            var finalPath: File

            if (wantMp3 && ffmpegPath != null) {
                addLog("Конвертация в MP3 (ffmpeg: $ffmpegPath)...")
                val cmd = mutableListOf(ffmpegPath, "-y", "-i", tmpM4a.absolutePath)
                if (coverData != null && coverData!!.isNotEmpty()) {
                    val coverFile = File(outputDir, "_cover.jpg")
                    coverFile.writeBytes(coverData!!)
                    cmd += listOf("-i", coverFile.absolutePath, "-map", "0:a", "-map", "1:v")
                }
                cmd += listOf(
                    "-codec:a", "libmp3lame", "-b:a", "256k",
                    "-id3v2_version", "3",
                    "-metadata", "title=$title",
                    "-metadata", "artist=Suno",
                    path.absolutePath
                )

                val process = ProcessBuilder(*cmd.toTypedArray()).redirectErrorStream(true).start()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    finalPath = path
                    tmpM4a.delete()
                    File(outputDir, "_cover.jpg").delete()
                    addLog("MP3 готов: ${finalPath.length() / 1024} КБ")
                } else {
                    addLog("FFmpeg ошибка (код $exitCode), сохраняю M4A")
                    val m4aPath = File(outputDir, "${path.nameWithoutExtension}.m4a")
                    tmpM4a.renameTo(m4aPath)
                    finalPath = m4aPath
                    File(outputDir, "_cover.jpg").delete()
                }
            } else {
                if (wantMp3) addLog("FFmpeg не найден на устройстве, сохраняю M4A")
                else addLog("Формат M4A выбран")
                val m4aPath = File(outputDir, "${path.nameWithoutExtension}.m4a")
                tmpM4a.renameTo(m4aPath)
                finalPath = m4aPath
            }

            val sizeKb = finalPath.length() / 1024
            addLog("ГОТОВО: ${finalPath.absolutePath} ($sizeKb КБ)")

            runOnUiThread {
                progressbar.progress = 100
                statusText.text = "Готово!"
                resultTitle.text = finalPath.name
                resultSize.text = "$sizeKb КБ • ${outputDir.absolutePath}"
                resultCard.visibility = View.VISIBLE
                setButtonState(false)
                isBusy = false
            }

        } catch (e: Exception) {
            addLog("Ошибка сохранения: ${e.message}")
            runOnUiThread { showError("Ошибка сохранения: ${e.message}") }
        }
    }

    private fun findFFmpeg(): String? {
        val candidates = listOf(
            File(filesDir, "ffmpeg").absolutePath,
            "/system/bin/ffmpeg",
            "/system/xbin/ffmpeg",
            "/data/local/bin/ffmpeg"
        )
        for (c in candidates) {
            if (File(c).exists() && File(c).canExecute()) return c
        }
        return null
    }

    private fun showError(msg: String) {
        isBusy = false
        setButtonState(false)
        statusText.text = "Ошибка"
        addLog("ОШИБКА: $msg")
        AlertDialog.Builder(this)
            .setTitle("Ошибка")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setButtonState(busy: Boolean) {
        val btn = findViewById<Button>(R.id.btnDownload)
        btn.isEnabled = !busy
        btn.text = if (busy) "⏳ Загрузка..." else "⬇ Загрузить"
    }

    fun openFolder() {
        val dir = outputDir
        // Пробуем открыть через файловый менеджер
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.fromFile(dir), "resource/folder")
            startActivity(intent)
            return
        } catch (_: Exception) {}
        // Альтернатива: открываем корневой /storage/emulated/0
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.fromFile(File("/storage/emulated/0")), "resource/folder")
            startActivity(intent)
            return
        } catch (_: Exception) {}
        // Если ничего не открылось — показываем путь
        Toast.makeText(this, "Папка: ${dir.absolutePath}", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
