ackage com.sunodownloader

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

class ConvertService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val srcPath = intent?.getStringExtra("src") ?: ""
        val outPath = intent?.getStringExtra("out") ?: ""
        val title = intent?.getStringExtra("title") ?: "track"
        val coverPath = intent?.getStringExtra("cover") ?: ""

        var success = false
        try {
            val safeTitle = title.replace("\"", "").replace("\\", "").trim().ifEmpty { "track" }
            var cmd = "-y -i \"$srcPath\""
            if (coverPath.isNotEmpty() && File(coverPath).exists()) {
                cmd += " -i \"$coverPath\" -map 0:a -map 1:v -c:v mjpeg"
                cmd += " -metadata:s:v title=\"Album cover\" -metadata:s:v comment=\"Cover (front)\""
            }
            cmd += " -c:a libmp3lame -b:a 256k -id3v2_version 3"
            cmd += " -metadata title=\"$safeTitle\" -metadata artist=Suno \"$outPath\""

            val session = FFmpegKit.execute(cmd)
            success = ReturnCode.isSuccess(session.getReturnCode())
                    && File(outPath).exists()
                    && File(outPath).length() > 0
        } catch (_: Exception) {
            success = false
        }

        // Результат в файл (простой IPC)
        try {
            File(filesDir, "_convert_result").writeText(if (success) "OK" else "FAIL")
        } catch (_: Exception) {}

        stopSelf()
        return START_NOT_STICKY
    }
}
