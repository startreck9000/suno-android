package com.sunodownloader

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnDownload: Button
    private lateinit var progressbar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultTitle: TextView
    private lateinit var resultSize: TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        btnDownload = findViewById(R.id.btnDownload)
        progressbar = findViewById(R.id.progressbar)
        statusText = findViewById(R.id.statusText)
        resultCard = findViewById(R.id.resultCard)
        resultTitle = findViewById(R.id.resultTitle)
        resultSize = findViewById(R.id.resultSize)
        logText = findViewById(R.id.logText)
        tvFolder = findViewById(R.id.tvFolder)

        // Кнопки
        btnDownload.setOnClickListener {
            val url = etUrl.text.toString().trim()
            startDownload(url)
        }
        findViewById<Button>(R.id.btnPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { etUrl.setText("") }
        findViewById<Button>(R.id.btnOpenFolder).setOnClickListener { openFolder() }
        findViewById<Button>(R.id.btnBrowse).setOnClickListener { pickFolder() }

        // Toggle log
        var logVisible = false
        findViewById<Button>(R.id.btnToggleLog).setOnClickListener {
            logVisible = !logVisible
            logText.visibility = if (logVisible) View.VISIBLE else View.GONE
            findViewById<Button>(R.id.btnToggleLog).text = if (logVisible) "−" else "+"
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { logText.text = "" }

        // Enter в поле
        etUrl.setOnEditorActionListener { v, _, _ ->
            startDownload(v.text.toString().trim()); true
        }

        addLog("Приложение запущено.")
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
                // 1. Resolve
                val songId = withContext(Dispatchers.IO) { resolveClipId(url) }
                addLog("Song ID: $songId")
                setStatus("Получение названия..."); progressbar.progress = 10

                // 2. Title
                val title = withContext(Dispatchers.IO) { getTitle(songId) }
                addLog("Title: $title")
                setStatus("Запрос доступа..."); progressbar.progress = 20

                // 3. Rights
                val rights = withContext(Dispatchers.IO) { getRights(songId) }
                addLog("Права получены")
                setStatus("Скачивание аудио..."); progressbar.progress = 30

                // 4. Download encrypted
                val encData = withContext(Dispatchers.IO) { downloadEncrypted(songId) }
                addLog("Скачано: ${encData.size / 1024} КБ")
                setStatus("Расшифровка..."); progressbar.progress = 70

                // 5. Decrypt
                val plain = withContext(Dispatchers.IO) { decryptClip(songId, encData, rights) }
                addLog("Расшифровано: ${plain.size / 1024} КБ")
                setStatus("Сохранение..."); progressbar.progress = 85

                // 6. Save
                val savedFile = withContext(Dispatchers.IO) { saveAudio(plain, title, songId) }
                val sizeKb = plain.size / 1024
                addLog("Готово! ${savedFile.name} ($sizeKb КБ)")

                progressbar.progress = 100
                statusText.text = "Готово!"
                resultTitle.text = savedFile.name
                resultSize.text = "$sizeKb КБ"
                resultCard.visibility = View.VISIBLE
                setButtonState(false)
                isBusy = false

            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                addLog("ОШИБКА: $msg")
                showError(msg)
            }
        }
    }

    // ─── Step 1: Resolve short link → UUID ──────────────────────────────────
    private fun resolveClipId(link: String): String {
        val songRe = Regex("/song/([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})", RegexOption.IGNORE_CASE)

        // Direct /song/<uuid>
        val directMatch = songRe.find(link)
        if (directMatch != null) return directMatch.groupValues[1].lowercase()

        // Short link: follow redirect
        val headReq = Request.Builder().url(link).head().build()
        http.newCall(headReq).execute().use { r ->
            val loc = r.header("Location") ?: ""
            val locMatch = songRe.find(loc)
            if (locMatch != null) return locMatch.groupValues[1].lowercase()
        }

        // GET and parse HTML
        val getReq = Request.Builder().url(link).get().build()
        http.newCall(getReq).execute().use { r ->
            val html = r.body.string()
            val htmlMatch = songRe.find(html)
            if (htmlMatch != null) return htmlMatch.groupValues[1].lowercase()

            // Fallback: most frequent UUID
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
    private fun getTitle(clip: String): String {
        return try {
            val url = "https://studio-api-prod.suno.com/api/oembed?url=${java.net.URLEncoder.encode("https://suno.com/song/$clip", "UTF-8")}"
            val req = Request.Builder().url(url).get().build()
            val resp = http.newCall(req).execute().use { r ->
                val json = JSONObject(r.body.string())
                json.optString("title", clip.take(8))
            }
        } catch (_: Exception) { clip.take(8) }
    }

    // ─── Step 3: Get playback rights ────────────────────────────────────────
    private fun getRights(clip: String): Map<String, String> {
        val body = JSONObject().put("content_params", JSONObject()
            .put("content_id", clip)
            .put("content_type", "clip")).toString()
        val req = Request.Builder()
            .url("https://studio-api-prod.suno.com/api/mango/rights")
            .post(okhttp3.RequestBody.create(body.toByteArray(), okhttp3.MediaType.parse("application/json")))
            .build()
        val resp = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Сервер прав вернул ${r.code}")
            val json = JSONObject(r.body.string())
            mapOf(
                "key" to json.getString("key"),
                "iv" to json.getString("iv"),
                "glt" to json.getString("glt")
            )
        }
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
                val resp = http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) return r.body.bytes()
                }
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
            val ctTag = raw.copyOfRange(12, raw.size) // ciphertext + tag(16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(gltHash, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(ctTag)
        }

        val contentKey = unwrap(rights["key"]!!)
        val contentIv = unwrap(rights["iv"]!!)

        // AES-CTR: manual via ECB
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"))
        val plain = ByteArray(enc.size)
        var counter = contentIv.clone() // 16-byte initial counter

        var offset = 0
        while (offset < enc.size) {
            val keystream = ecb.doFinal(counter)
            val chunkLen = minOf(16, enc.size - offset)
            for (i in 0 until chunkLen) {
                plain[offset + i] = (enc[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            // Increment counter (big-endian, 16 bytes)
            for (j in 15 downTo 0) {
                val v = counter[j].toInt() and 0xFF
                counter[j] = (v + 1).toByte()
                if ((v + 1) and 0xFF != 0) break
            }
            offset += 16
        }

        // Verify: M4A starts with 'ftyp', WebM with 0x1A45DFA3
        val hasFtyp = plain.size > 8 && String(plain, 4, 4, Charsets.US_ASCII) == "ftyp"
        val hasWebm = plain.size >= 4 && (plain[0].toInt() and 0xFF) == 0x1A && (plain[1].toInt() and 0xFF) == 0x45
        if (!hasFtyp && !hasWebm) {
            throw Exception("Ошибка расшифровки аудио.\nКлючи устаревают — повторите попытку.")
        }
        return plain
    }

    // ─── Step 6: Save file ──────────────────────────────────────────────────
    private fun saveAudio(data: ByteArray, title: String, songId: String): File {
        val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").replace(Regex("\\s+"), " ").trim('.', ' ').take(150)
        val baseName = if (safeName.isNotEmpty()) safeName else "track"
        val ext = if (data.size > 4 && String(data, 4, 4, Charsets.US_ASCII) == "ftyp") "m4a" else "webm"

        // Determine output directory
        val outDir: File
        val targetUri: Uri? = selectedUri
        if (targetUri != null) {
            // Use SAF (Storage Access Framework)
            val displayName = "${baseName}.$ext"
            val docUri = androidx.documentfile.provider.DocumentFile.fromTree(targetUri)
                ?.createFile("audio/*", displayName)
                ?: throw Exception("Не удалось создать файл в выбранной папке")
            contentResolver.openOutputStream(docUri)!!.use { it.write(data) }
            // Return a dummy File for display purposes
            return File("", displayName)
        } else {
            // Fallback: Downloads/Suno
            outDir = if (Build.VERSION.SDK_INT >= 29) {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Suno")
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Suno")
            }
            outDir.mkdirs()

            var path = File(outDir, "$baseName.$ext")
            var i = 1
            while (path.exists()) {
                path = File(outDir, "$baseName($i).$ext")
                i++
            }
            path.writeBytes(data)
            return path
        }
    }

    // ─── UI helpers ─────────────────────────────────────────────────────────
    private fun setStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "[$time] $msg\n"
        runOnUiThread {
            val current = logText.text.toString()
            logText.text = current + line
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
