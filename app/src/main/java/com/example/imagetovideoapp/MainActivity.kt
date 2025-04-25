package com.example.imagetovideoapp

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.StatisticsCallback
import com.example.imagetovideoapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selectedImageUris = mutableListOf<Uri>()
    private val isEncoding = AtomicBoolean(false)
    private var lastToastTime = 0L
    private val TOAST_INTERVAL = 2000L

    private lateinit var vibrator: Vibrator
    private lateinit var lightTick: VibrationEffect

    private val pickImagesLauncher = registerForActivityResult(OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) {
            updateStatus("Изображения надо бы выбрать")
            binding.btnCreateVideo.isEnabled = false
        } else {
            selectedImageUris.clear()
            uris.forEach { uri ->
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            selectedImageUris.addAll(uris)
            updateStatus("Вы выбрали изображений, вот столько: ${uris.size}")
            binding.btnCreateVideo.isEnabled = true
        }
    }

    private val pickVideoLauncher = registerForActivityResult(OpenDocument()) { uri ->
        uri?.let { startDecodingProcess(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Haptic setup
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        lightTick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else {
            VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
        }

        // FFmpegKit logging and stats
        FFmpegKitConfig.enableLogCallback(LogCallback { log ->
            Log.d("FFMPEG_LOG", log.message)
        })
        FFmpegKitConfig.enableStatisticsCallback(StatisticsCallback { stat ->
            updateStatusOnMainThread(
                "Кодирование: кадр ${stat.videoFrameNumber}, " +
                        "fps=${"%.1f".format(stat.videoFps)}, " +
                        "time=${"%.1f".format(stat.time / 1000.0)}с"
            )
        })

        binding.btnSelectImages.apply {
            isHapticFeedbackEnabled = true
            setOnClickListener {
                vibrator.vibrate(lightTick)
                pickImagesLauncher.launch(arrayOf("image/*"))
            }
        }

        binding.btnCreateVideo.apply {
            isEnabled = false
            isHapticFeedbackEnabled = true
            setOnClickListener {
                vibrator.vibrate(lightTick)
                when {
                    isEncoding.get() -> showToast("Уже в процессе ведь")
                    selectedImageUris.isEmpty() -> showToast("Сначала выберите изображения")
                    else -> startEncodingProcess()
                }
            }
        }

        binding.btnDecodeVideo.apply {
            isHapticFeedbackEnabled = true
            setOnClickListener {
                vibrator.vibrate(lightTick)
                pickVideoLauncher.launch(arrayOf("video/*"))
            }
        }

        updateStatus("Полная готовность\uD83E\uDEE1")
    }

    private fun startEncodingProcess() {
        if (!isEncoding.compareAndSet(false, true)) return
        updateStatus("Преобразовываем…")
        lifecycleScope.launch(Dispatchers.IO) {
            val tmpDir = createTempDir("ffmpeg_in")
            try {
                selectedImageUris.forEachIndexed { idx, uri ->
                    val fn = String.format(Locale.US, "img%05d.jpg", idx + 1)
                    contentResolver.openInputStream(uri)?.use { ins ->
                        FileOutputStream(File(tmpDir, fn)).use { it.write(ins.readBytes()) }
                    }
                }

                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val displayName = "VIDEO_$ts.mp4"
                val outUri = insertVideoToGallery(displayName)
                    ?: throw IllegalStateException("Не удалось заполучить Uri для записи видео")

                // Encode to temp file
                val tempOut = File(cacheDir, displayName)
                val cmd = arrayOf(
                    "-y", "-framerate", "10", "-start_number", "1",
                    "-i", "${tmpDir.absolutePath}/img%05d.jpg",
                    "-c:v", "mpeg4", "-pix_fmt", "yuv420p", "-qscale:v", "5",
                    tempOut.absolutePath
                ).joinToString(" ") { arg -> if (arg.contains(' ')) "\"$arg\"" else arg }

                val session = FFmpegKit.execute(cmd)
                if (session.returnCode.isValueSuccess) {
                    contentResolver.openOutputStream(outUri)?.use { os ->
                        FileInputStream(tempOut).use { it.copyTo(os) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                            .also { cv -> contentResolver.update(outUri, cv, null, null) }
                    }
                    withContext(Dispatchers.Main) {
                        updateStatus("Сохранено:\n$outUri")
                        showToast("Видео уже в галерее")
                    }
                } else {
                    throw RuntimeException("FFmpeg завершился с ошибкой: ${session.failStackTrace}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateStatus("Ошибочка: ${e.message}")
                    showToast("Не удалось создать видео")
                }
            } finally {
                tmpDir.deleteRecursively()
                isEncoding.set(false)
            }
        }
    }

    private fun startDecodingProcess(videoUri: Uri) {
        updateStatus("Начинаем декодирование…")
        lifecycleScope.launch(Dispatchers.IO) {
            val tmpDir = createTempDir("ffmpeg_frames")
            try {
                val inputPath = getTempFilePathForUri(videoUri)
                // Extract all frames
                val cmd = arrayOf(
                    "-y", "-i", inputPath, "-vsync", "0",
                    "${tmpDir.absolutePath}/frame%05d.jpg"
                ).joinToString(" ") { arg -> if (arg.contains(' ')) "\"$arg\"" else arg }

                val session = FFmpegKit.execute(cmd)
                if (session.returnCode.isValueSuccess) {
                    tmpDir.listFiles()?.forEach { file ->
                        // Save each frame to gallery
                        val tsFrame = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val name = "FRAME_${tsFrame}_${file.name}"
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, name)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.Images.Media.RELATIVE_PATH,
                                    Environment.DIRECTORY_PICTURES + "/DecodedFrames")
                                put(MediaStore.Images.Media.IS_PENDING, 1)
                            }
                        }
                        val outUri = contentResolver.insert(
                            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            values
                        )
                        outUri?.let { uri ->
                            contentResolver.openOutputStream(uri)?.use { os ->
                                FileInputStream(file).use { it.copyTo(os) }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                values.clear()
                                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                                contentResolver.update(uri, values, null, null)
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        val count = tmpDir.listFiles()?.size ?: 0
                        updateStatus("Кадры сохранены: $count файлов")
                        showToast("Декодирование выполнено")
                    }
                } else {
                    throw RuntimeException("FFmpeg завершился с ошибкой: ${session.failStackTrace}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateStatus("Ошибочка декодирования: ${e.message}")
                    showToast("Не вышло это задекодировать")
                }
            } finally {
                tmpDir.deleteRecursively()
            }
        }
    }

    private fun insertVideoToGallery(displayName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        return contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        )
    }

    private fun getTempFilePathForUri(uri: Uri): String {
        val f = File(cacheDir, "tmp_${System.currentTimeMillis()}.mp4")
        contentResolver.openInputStream(uri)?.use { ins ->
            FileOutputStream(f).use { it.write(ins.readBytes()) }
        }
        return f.absolutePath
    }

    private fun updateStatus(text: String) {
        binding.tvStatus.text = "Статус:\n$text"
    }

    private fun updateStatusOnMainThread(text: String) {
        runOnUiThread { updateStatus(text) }
    }

    private fun showToast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastTime >= TOAST_INTERVAL) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            lastToastTime = now
        }
    }
}
