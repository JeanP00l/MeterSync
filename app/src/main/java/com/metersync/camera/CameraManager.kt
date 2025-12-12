package com.metersync.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import android.util.Log
import android.os.ParcelFileDescriptor
import com.metersync.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class CameraManager(private val context: Context) {
    
    suspend fun addWatermarkToImage(
        imageUri: Uri,
        counterAddress: String,
        counterNumber: String,
        tempFile: File
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // Формируем строку для ватермарки (для обратной совместимости)
            val meterInfo = "${counterAddress}      №${counterNumber}"
            
            Logger.log("Step 1: Loading temporary image and adding watermark", "CAMERA")
            // Шаг 1: Загружаем временный файл и добавляем водяной знак
            val watermarkedBitmap = createWatermarkedBitmap(imageUri, meterInfo)
            
            if (watermarkedBitmap == null) {
                Logger.logError("Failed to create watermarked bitmap, keeping temp file", null)
                return@withContext null
            }
            
            Logger.log("Step 2: Saving watermarked image to gallery", "CAMERA")
            // Шаг 2: Сохраняем изображение С ВОДЯНЫМ ЗНАКОМ в галерею
            val savedUri = saveImageToGallery(watermarkedBitmap, meterInfo)
            
            if (savedUri == null) {
                Logger.logError("Failed to save image to gallery, keeping temp file: ${tempFile.absolutePath}", null)
                return@withContext null
            }
            
            Logger.log("Step 3: Writing EXIF metadata (counterAddress and counterNumber)", "CAMERA")
            // Шаг 3: Записываем метаданные EXIF
            // Добавляем прямое логирование для отладки
            Log.d("MeterSync", "Writing EXIF metadata: counterNumber='$counterNumber'")
            writeExifMetadata(savedUri, counterAddress, counterNumber)
            Log.d("MeterSync", "EXIF metadata write completed")
            
            Logger.log("Step 4: Verifying saved image", "CAMERA")
            // Шаг 4: Проверяем, что изображение действительно сохранено
            if (verifyImageSaved(savedUri)) {
                Logger.log("Step 5: Deleting temporary file (image is safely saved)", "CAMERA")
                // Шаг 5: ТОЛЬКО ПОСЛЕ подтверждения сохранения удаляем временный файл
                deleteTempFileSafely(tempFile)
            } else {
                // Если сохранение не подтверждено, НЕ удаляем временный файл
                Logger.logError("Failed to verify saved image, keeping temp file: ${tempFile.absolutePath}", null)
            }
            
            savedUri
        } catch (e: Exception) {
            Logger.logError("Error processing image with watermark", e)
            // При ошибке НЕ удаляем временный файл - он может быть единственной копией
            null
        }
    }
    
    private fun createWatermarkedBitmap(imageUri: Uri, meterInfo: String): Bitmap? {
        return try {
            Logger.log("Loading temporary image from: $imageUri", "CAMERA")
            // Загружаем исходное изображение из временного файла
            val inputStream = context.contentResolver.openInputStream(imageUri)
            val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            if (originalBitmap == null) {
                Logger.logError("Failed to load original bitmap from temp file", null)
                return null
            }
            
            Logger.log("Adding watermark to image: $meterInfo", "CAMERA")
            // Создаем новый bitmap с водяным знаком
            val watermarkedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(watermarkedBitmap)
            
            // Создаем белую панель снизу
            val paint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            
                val panelHeight = 120 // Увеличили высоту панели с 80 до 120 пикселей
            val panelRect = android.graphics.RectF(
                0f,
                (watermarkedBitmap.height - panelHeight).toFloat(),
                watermarkedBitmap.width.toFloat(),
                watermarkedBitmap.height.toFloat()
            )
            
            canvas.drawRect(panelRect, paint)
            
                // Добавляем текст с информацией о счетчике
                val textPaint = Paint().apply {
                    color = Color.BLACK
                    textSize = 48f  // Увеличили размер шрифта с 32f до 48f
                    typeface = Typeface.DEFAULT_BOLD
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }
            
            val textX = watermarkedBitmap.width / 2f
            val textY = watermarkedBitmap.height - (panelHeight / 2f) + (textPaint.textSize / 3f)
            
            canvas.drawText(meterInfo, textX, textY, textPaint)
            
            watermarkedBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun saveImageToGallery(bitmap: Bitmap?, meterInfo: String): Uri? {
        if (bitmap == null) return null
        
        return try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "meter_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                    }
                }
            
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            
            uri?.let { imageUri ->
                val outputStream: OutputStream? = context.contentResolver.openOutputStream(imageUri)
                outputStream?.let { stream ->
                    try {
                        // Сохраняем изображение
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                        
                        // КРИТИЧЕСКИ ВАЖНО: Принудительно записываем все данные на диск
                        stream.flush()
                        
                        // Для FileOutputStream дополнительно синхронизируем с диском
                        if (stream is FileOutputStream) {
                            stream.fd.sync()
                        }
                    } finally {
                        stream.close()
                    }
                }
                imageUri
            }
        } catch (e: Exception) {
            Logger.logError("Error saving image to gallery", e)
            null
        }
    }
    
    /**
     * Проверяет, что изображение действительно сохранено в галерее.
     * Выполняет несколько проверок для гарантии сохранности данных.
     */
    private fun verifyImageSaved(uri: Uri): Boolean {
        return try {
            // Проверка 1: Файл существует и доступен
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Logger.logError("Cannot open saved image for verification", null)
                return false
            }
            
            // Проверка 2: Файл имеет размер > 0 (данные записались)
            val fileSize = inputStream.available()
            inputStream.close()
            
            if (fileSize <= 0) {
                Logger.logError("Saved image has zero size", null)
                return false
            }
            
            // Проверка 3: Можно прочитать первые байты (файл не поврежден)
            val verifyStream = context.contentResolver.openInputStream(uri)
            if (verifyStream != null) {
                val buffer = ByteArray(1024)
                val bytesRead = verifyStream.read(buffer)
                verifyStream.close()
                
                if (bytesRead <= 0) {
                    Logger.logError("Cannot read saved image data", null)
                    return false
                }
            }
            
            Logger.log("Image saved and verified successfully: size=$fileSize bytes", "CAMERA")
            true
        } catch (e: Exception) {
            Logger.logError("Error verifying saved image", e)
            false
        }
    }
    
    /**
     * Записывает метаданные EXIF в сохраненное изображение.
     * Записывает только номер счетчика в USER_COMMENT с поддержкой UTF-8.
     * 
     * Для работы с MediaStore на Android 10+ используем следующий подход:
     * 1. Читаем изображение через InputStream
     * 2. Создаем временный файл с EXIF метаданными
     * 3. Перезаписываем оригинальный файл в MediaStore
     * 
     * Для поддержки UTF-8 с кириллицей и латиницей используем формат "UTF-8\u0000строка"
     * согласно спецификации EXIF 2.3 и выше.
     */
    private fun writeExifMetadata(uri: Uri, counterAddress: String, counterNumber: String) {
        Log.d("MeterSync", "writeExifMetadata called: uri=$uri, counterNumber='$counterNumber'")
        var tempFile: File? = null
        try {
            // Открываем поток для чтения изображения
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e("MeterSync", "Cannot open image stream for EXIF metadata writing")
                Logger.logError("Cannot open image stream for EXIF metadata writing", null)
                return
            }
            Log.d("MeterSync", "Image stream opened successfully")
            
            try {
                // Создаем временный файл для работы с EXIF
                tempFile = File.createTempFile("exif_temp_", ".jpg", context.cacheDir)
                
                // Копируем изображение во временный файл
                val tempOutputStream = FileOutputStream(tempFile)
                inputStream.copyTo(tempOutputStream)
                tempOutputStream.close()
                inputStream.close()
                
                // Теперь работаем с временным файлом через ExifInterface
                val exif = ExifInterface(tempFile.absolutePath)
                
                // Записываем только номер счетчика в USER_COMMENT напрямую
                // Номер счетчика может содержать цифры, буквы кириллицы и/или латиницы
                // Записываем напрямую без префикса кодировки для совместимости с Windows
                Log.d("MeterSync", "Setting USER_COMMENT: '$counterNumber'")
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, counterNumber)
                
                // Сохраняем изменения во временный файл
                Log.d("MeterSync", "Saving EXIF attributes to temp file")
                exif.saveAttributes()
                Log.d("MeterSync", "EXIF attributes saved successfully")
                
                // Теперь перезаписываем оригинальный файл в MediaStore
                val outputStream: OutputStream? = context.contentResolver.openOutputStream(uri, "w")
                if (outputStream != null) {
                    try {
                        // Копируем файл с EXIF метаданными обратно в MediaStore
                        val tempInputStream = tempFile.inputStream()
                        tempInputStream.copyTo(outputStream)
                        tempInputStream.close()
                        outputStream.flush()
                        
                        Log.d("MeterSync", "EXIF metadata written successfully: counterNumber='$counterNumber'")
                        Logger.log("EXIF metadata written successfully: counterNumber='$counterNumber'", "CAMERA")
                    } catch (e: Exception) {
                        Log.e("MeterSync", "Error copying file with EXIF metadata back to MediaStore", e)
                        Logger.logError("Error copying file with EXIF metadata back to MediaStore", e)
                    } finally {
                        outputStream.close()
                    }
                } else {
                    Log.e("MeterSync", "Cannot open output stream for writing EXIF metadata")
                    Logger.logError("Cannot open output stream for writing EXIF metadata", null)
                }
            } catch (e: Exception) {
                Log.e("MeterSync", "Error writing EXIF metadata", e)
                Logger.logError("Error writing EXIF metadata", e)
                // Не критично - продолжаем работу даже если EXIF не записался
            } finally {
                // Удаляем временный файл
                tempFile?.delete()
            }
        } catch (e: Exception) {
            Logger.logError("Error in writeExifMetadata", e)
            tempFile?.delete()
        }
    }
    
    /**
     * Безопасно удаляет временный файл с обработкой всех ошибок.
     * НЕ выбрасывает исключения, только логирует ошибки.
     */
    private fun deleteTempFileSafely(file: File?) {
        if (file == null || !file.exists()) {
            return
        }
        
        try {
            val deleted = file.delete()
            if (deleted) {
                Logger.log("Temporary file deleted successfully: ${file.absolutePath}", "CAMERA")
            } else {
                Logger.logError("Failed to delete temporary file: ${file.absolutePath}", null)
            }
        } catch (e: Exception) {
            // НЕ выбрасываем исключение - это не критично, файл можно удалить позже
            Logger.logError("Exception while deleting temporary file: ${file.absolutePath}", e)
        }
    }
    
    /**
     * Создает временный файл для фотографии и возвращает URI.
     * Также сохраняет путь к файлу для последующего безопасного удаления.
     */
    fun createTempImageUri(): Pair<Uri, File> {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "METER_${timeStamp}_"
        
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val image = File.createTempFile(imageFileName, ".jpg", storageDir)
        
        // Используем FileProvider для создания безопасного URI
        val uri = FileProvider.getUriForFile(
            context,
            "com.metersync.fileprovider",
            image
        )
        
        return Pair(uri, image)
    }
}

@Composable
fun rememberCameraManager(): CameraManager {
    val context = LocalContext.current
    return remember { CameraManager(context) }
}
