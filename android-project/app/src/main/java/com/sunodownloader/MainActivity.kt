package com.sunodownloader

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnDownload: Button
    private lateinit var progressbar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultTitle: TextView
    private lateinit var resultSize: TextView
    private lateinit var resultCover: ImageView
    private lateinit var logText: TextView
    private lateinit var tvFolder: TextView

    private var isBusy = false
    private var selectedUri: Uri? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private var logFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val sunoDir = File(dir, "Suno")
            if (!sunoDir.exists()) sunoDir.mkdirs()
            logFile = File(sunoDir, "suno_log.txt")
            fileLog("=== Запуск приложения ===")
        } catch (_: Exception) {}

        etUrl = findViewById(R.id.etUrl)
        btnDownload = findViewById(R.id.btnDownload)
        progressbar = findViewById(R.id.progressbar)
        statusText = findViewById(R.id.statusText)
        resultCard = findViewById(R.id.resultCard)
        resultTitle = findViewById(R.id.resultTitle)
        resultSize = findViewById(R.id.resultSize)
        resultCover = findViewById(R.id.resultCover)
        logText = findViewById(R.id.logText)
        tvFolder = findViewById(R.id.tvFolder)

        btnDownload.setOnClickListener {
            startDownload(etUrl.text.toString().trim())
        }
        findViewById<Button>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { etUrl.setText("") }
        findViewById<Button>(R.id.btnOpenFolder).setOnClickListener { openFolder() }
        findViewById<Button>(R.id.btnBrowse).setOnClickListener { pickFolder() }

        var logVisible = false
        findViewById<Button>(R.id.btnToggleLog).setOnClickListener {
            logVisible = !logVisible
            logText.visibility = if (logVisible) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.btnToggleLog).text = if (logVisible) "\u2212" else "+"
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { logText.text = "" }

        etUrl.setOnEditorActionListener { v, _, _ ->
            startDownload(v.text.toString().trim()); true
        }

        addLog("Приложение запущено.")
    }

    // ─── File logging ───────────────────────────────────────────────────────
    private fun fileLog(msg: String) {
        try {
            val lf = logFile ?: return
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            FileWriter(lf, true).use { it.append("[$time] $msg\n") }
        } catch (_: Exception) {}
    }

    // ─── Clipboard ──────────────────────────────────────────────────────────
    private fun pasteFromClipboard() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) etUrl.setText(text)
        } catch (_: Exception) {}
    }

    // ─── Folder picker ──────────────────────────────────────────────────────
    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try { startActivityForResult(intent, 1001) }
        catch (_: Exception) { Toast.makeText(this, "Не удалось открыть выбор папки", Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                selectedUri = uri
                val name = getTreeName(uri)
                tvFolder.text = "Папка: $name"
                addLog("Папка: $name")
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getTreeName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else uri.lastPathSegment ?: "Папка"
                } else "Папка"
            } ?: "Папка"
        } catch (_: Exception) { "Папка" }
    }

    // ─── Download logic ─────────────────────────────────────────────────────
    private fun startDownload(url: String) {
        if (isBusy) return
        if (url.isBlank()) {
            Toast.makeText(this, "Вставьте ссылку на трек Suno", Toast.LENGTH_SHORT).show(); return
        }
        if (!Regex("^https?://suno\\.com/(s|song)/[\\w-]+").matches(url)) {
            AlertDialog.Builder(this)
                .setTitle("Неверная ссылка")
                .setMessage("Введите ссылку вида:\nhttps://suno.com/s/...\nили\nhttps://suno.com/song/<uuid>")
                .setPositiveButton("OK", null).show()
            return
        }

        isBusy = true
        resultCard.visibility = View.GONE
        setButtonState(true)
        progressbar.progress = 0
        statusText.text = "Начинаю..."
        addLog("Загрузка: $url")

        scope.launch {
            try {
                val songId = withContext(Dispatchers.IO) { resolveClipId(url) }
                addLog("Song ID: $songId")
                setStatus("Получение названия..."); progressbar.progress = 10

                val meta = withContext(Dispatchers.IO) { getTrackMeta(songId) }
                val title = meta.title
                addLog("Title: $title")
                if (meta.coverUrl != null) addLog("Обложка: ${meta.coverUrl}")

                val coverDeferred = async(Dispatchers.IO) { meta.coverUrl?.let { downloadCover(it) } }

                setStatus("Запрос доступа..."); progressbar.progress = 20
                val rights = withContext(Dispatchers.IO) { getRights(songId) }
                addLog("Права получены")
                setStatus("Скачивание аудио..."); progressbar.progress = 30

                val encData = withContext(Dispatchers.IO) { downloadEncrypted(songId) }
                addLog("Скачано: ${encData.size / 1024} КБ")
                setStatus("Расшифровка..."); progressbar.progress = 70

                val plain = withContext(Dispatchers.IO) { decryptClip(songId, encData, rights) }
                addLog("Расшифровано: ${plain.size / 1024} КБ")

                val cover = coverDeferred.await()
                if (cover != null) addLog("Обложка скачана: ${cover.size / 1024} КБ")

                val wantMp3 = findViewById<RadioButton>(R.id.rbMp3).isChecked
                setStatus(if (wantMp3) "Конвертация в MP3..." else "Сохранение M4A...")
                progressbar.progress = 85

                val (savedFile, savedSize) = withContext(Dispatchers.IO) { saveAudio(plain, title, songId, wantMp3, cover) }
                val sizeKb = savedSize / 1024
                addLog("Готово! ${savedFile.name} ($sizeKb КБ)")

                progressbar.progress = 100
                statusText.text = "Готово!"
                resultTitle.text = savedFile.name
                resultSize.text = "$sizeKb КБ"
                showCover(cover)
                resultCard.visibility = View.VISIBLE
                setButtonState(false)
                isBusy = false

            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                addLog("ОШИБКА: $msg")
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                fileLog("STACKTRACE:\n${sw.toString()}")
                showError(msg)
            }
        }
    }

    // ─── Step 1: Resolve short link → UUID ──────────────────────────────────
    private fun resolveClipId(link: String): String {
        val songRe = Regex("/song/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})", RegexOption.IGNORE_CASE)

        val directMatch = songRe.find(link)
        if (directMatch != null) return directMatch.groupValues[1].lowercase()

        val getReq = Request.Builder().url(link).get().build()
        http.newCall(getReq).execute().use { r ->
            val html = r.body!!.string()
            val htmlMatch = songRe.find(html)
            if (htmlMatch != null) return htmlMatch.groupValues[1].lowercase()

            val uuids = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", RegexOption.IGNORE_CASE)
                .findAll(html).map { it.value.lowercase() }.toList()
            if (uuids.isNotEmpty()) {
                val freq = mutableMapOf<String, Int>()
                uuids.forEach { u -> freq[u] = (freq[u] ?: 0) + 1 }
                return freq.maxByOrNull { it.value }?.key ?: throw Exception("Не удалось определить ID трека")
            }
        }
        throw Exception("Не удалось определить ID трека по ссылке.")
    }

    // ─── Step 2: Get title via oEmbed ───────────────────────────────────────
    private data class TrackMeta(val title: String, val coverUrl: String?)

    private fun getTrackMeta(clip: String): TrackMeta {
        return try {
            val encoded = java.net.URLEncoder.encode("https://suno.com/song/$clip", "UTF-8")
            val url = "https://studio-api-prod.suno.com/api/oembed?url=$encoded"
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { r ->
                val json = JSONObject(r.body!!.string())
                val title = json.optString("title", clip.take(8))
                val cover = json.optString("thumbnail_url", "").takeIf { it.isNotEmpty() }
                    ?: "https://cdn2.suno.ai/image_large_$clip.jpeg"
                TrackMeta(title, cover)
            }
        } catch (_: Exception) {
            TrackMeta(clip.take(8), "https://cdn2.suno.ai/image_large_$clip.jpeg")
        }
    }

    private fun downloadCover(url: String): ByteArray? {
        return try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { r ->
                if (r.isSuccessful) r.body!!.bytes() else null
            }
        } catch (_: Exception) { null }
    }

    // ─── Step 3: Get playback rights ────────────────────────────────────────
    private fun getRights(clip: String): Map<String, String> {
        val body = JSONObject().put("content_params", JSONObject()
            .put("content_id", clip)
            .put("content_type", "clip")).toString()
        val req = Request.Builder()
            .url("https://studio-api-prod.suno.com/api/mango/rights")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Сервер прав вернул ${r.code}")
            val json = JSONObject(r.body!!.string())
            mapOf(
                "key" to json.getString("key"),
                "iv" to json.getString("iv"),
                "glt" to json.getString("glt")
            )
        }
        return resp
    }

    // ─── Step 4: Download encrypted audio ───────────────────────────────────
    private fun downloadEncrypted(clip: String): ByteArray {
        val urls = listOf(
            "https://d2lwuy8qc234o3.cloudfront.net/1/clip/$clip.m4a",
            "https://d2lwuy8qc234o3.cloudfront.net/1/clip/$clip.webm"
        )
        for (url in urls) {
            try {
                val req = Request.Builder().url(url).get().build()
                val resp = http.newCall(req).execute()
                if (resp.isSuccessful) {
                    val data = resp.body!!.bytes()
                    resp.close()
                    return data
                }
                resp.close()
            } catch (_: Exception) { continue }
        }
        throw Exception("Не удалось скачать аудиопоток.\nТрек приватный или CDN недоступен.")
    }

    // ─── Step 5: Decrypt (AES-GCM unwrap + AES-CTR) ─────────────────────────
    private fun decryptClip(clip: String, enc: ByteArray, rights: Map<String, String>): ByteArray {
        val gltHash = MessageDigest.getInstance("SHA-256").digest(rights["glt"]!!.toByteArray())
        val aad = clip.toByteArray()

        fun unwrap(pkgB64: String): ByteArray {
            val raw = android.util.Base64.decode(pkgB64, android.util.Base64.DEFAULT)
            val nonce = raw.copyOfRange(0, 12)
            val ctTag = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(gltHash, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(ctTag)
        }

        val contentKey = unwrap(rights["key"]!!)
        val contentIv = unwrap(rights["iv"]!!)

        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"))
        val plain = ByteArray(enc.size)
        var counter = contentIv.clone()

        var offset = 0
        while (offset < enc.size) {
            val keystream = ecb.doFinal(counter)
            val chunkLen = minOf(16, enc.size - offset)
            for (i in 0 until chunkLen) {
                plain[offset + i] = (enc[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            for (j in 15 downTo 0) {
                val v = counter[j].toInt() and 0xFF
                counter[j] = (v + 1).toByte()
                if ((v + 1) and 0xFF != 0) break
            }
            offset += 16
        }

        val hasFtyp = plain.size > 8 && String(plain, 4, 4, Charsets.US_ASCII) == "ftyp"
        val hasWebm = plain.size >= 4 && (plain[0].toInt() and 0xFF) == 0x1A && (plain[1].toInt() and 0xFF) == 0x45
        if (!hasFtyp && !hasWebm) {
            throw Exception("Ошибка расшифровки аудио.\nКлючи устаревают — повторите попытку.")
        }
        return plain
    }

    // ─── Step 6: Save file ──────────────────────────────────────────────────
    private fun saveAudio(data: ByteArray, title: String, songId: String, wantMp3: Boolean, cover: ByteArray?): Pair<File, Long> {
        val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ").trim('.', ' ').take(150)
        val baseName = if (safeName.isNotEmpty()) safeName else "track"
        val isM4a = data.size > 4 && String(data, 4, 4, Charsets.US_ASCII) == "ftyp"
        val srcExt = if (isM4a) "m4a" else "webm"

        val tmpSrc = File(cacheDir, "_src_${songId.take(8)}.$srcExt")
        tmpSrc.writeBytes(data)

        var converted: ByteArray? = null
        var outExt = srcExt

        if (wantMp3) {
            converted = tryConvertToMp3(tmpSrc, title, cover)
            if (converted != null) {
                outExt = "mp3"
                addLog("MP3 готов: ${converted.size / 1024} КБ")
            } else {
                addLog("Конвертация не удалась — сохраняю M4A")
            }
        }

        val finalData = converted ?: data
        val displayName = "$baseName.$outExt"

        val savedSize = finalData.size.toLong()
        if (selectedUri != null) {
            val values = android.content.ContentValues().apply {
                put(android.provider.OpenableColumns.DISPLAY_NAME, displayName)
                put("mime_type", "audio/*")
            }
            val docUri = try {
                contentResolver.insert(selectedUri!!, values)
                    ?: throw Exception("insert returned null")
            } catch (e: Exception) {
                throw Exception("Не удалось создать файл в выбранной папке")
            }
            contentResolver.openOutputStream(docUri)!!.use { it.write(finalData) }
        } else {
            val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Suno")
            try {
                outDir.mkdirs()
                var path = File(outDir, displayName)
                var i = 1
                while (path.exists()) {
                    path = File(outDir, "$baseName($i).$outExt")
                    i++
                }
                path.writeBytes(finalData)
            } catch (e: Exception) {
                throw Exception("Нет доступа к папке Загрузки.\nВыберите другую папку кнопкой «Обзор».")
            }
        }

        tmpSrc.delete()
        return Pair(File(displayName), savedSize)
    }

    // Конвертация через отдельный процесс (Service) чтобы нативный краш FFmpeg
    // не убил главное приложение. Если воркер упадёт — возвращаем null (M4A).
    private fun tryConvertToMp3(src: File, title: String, cover: ByteArray?): ByteArray? {
        val tmpMp3 = File(cacheDir, "_out_${System.currentTimeMillis()}.mp3")
        val resultFile = File(cacheDir, "_convert_result")
        var tmpCover: File? = null
        try {
            resultFile.delete()

            if (cover != null && cover.isNotEmpty()) {
                tmpCover = File(cacheDir, "_cover_${System.currentTimeMillis()}.jpg")
                tmpCover.writeBytes(cover)
            }

            val intent = Intent(this, ConvertService::class.java).apply {
                putExtra("src", src.absolutePath)
                putExtra("out", tmpMp3.absolutePath)
                putExtra("title", title)
                putExtra("cover", tmpCover?.absolutePath ?: "")
                putExtra("result", resultFile.absolutePath)
            }
            startService(intent)

            // Ждём результат до 90 секунд
            var waited = 0L
            while (waited < 90_000) {
                Thread.sleep(500)
                waited += 500
                if (resultFile.exists()) break
            }

            val ok = resultFile.exists() && resultFile.readText().trim() == "OK"
            resultFile.delete()

            if (ok && tmpMp3.exists() && tmpMp3.length() > 0) {
                return tmpMp3.readBytes()
            }
            addLog("FFmpeg конвертация не удалась")
            fileLog("FFmpeg: FAIL (timed out or error)")
            return null
        } catch (e: Exception) {
            addLog("Ошибка конвертации: ${e.message}")
            fileLog("FFmpeg EXCEPTION: ${e.message}")
            return null
        } finally {
            tmpMp3.delete()
            tmpCover?.delete()
        }
    }

    private fun showCover(cover: ByteArray?) {
        if (cover == null || cover.isEmpty()) {
            resultCover.visibility = View.GONE
            return
        }
        val bmp = BitmapFactory.decodeByteArray(cover, 0, cover.size)
        if (bmp == null) {
            resultCover.visibility = View.GONE
            return
        }
        resultCover.setImageBitmap(bmp)
        resultCover.visibility = View.VISIBLE
    }

    // ─── UI helpers ─────────────────────────────────────────────────────────
    private fun setStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "[$time] $msg"
        fileLog(line)
        runOnUiThread {
            logText.text = logText.text.toString() + line + "\n"
            logText.post { logText.scrollTo(0, logText.bottom) }
        }
    }

    private fun showError(msg: String) {
        isBusy = false
        setButtonState(false)
        statusText.text = "Ошибка"
        AlertDialog.Builder(this)
            .setTitle("Ошибка")
            .setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    private fun setButtonState(busy: Boolean) {
        runOnUiThread {
            btnDownload.isEnabled = !busy
            btnDownload.text = if (busy) "⏳ Загрузка..." else "⬇ Загрузить"
            etUrl.isEnabled = !busy
        }
    }

    private fun openFolder() {
        val uri = selectedUri
        if (uri != null) {
            try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "resource/folder")) }
            catch (_: Exception) { Toast.makeText(this, "Откройте файловый менеджер", Toast.LENGTH_LONG).show() }
        } else {
            Toast.makeText(this, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/Suno", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
