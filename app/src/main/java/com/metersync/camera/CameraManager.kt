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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class CameraManager(private val context: Context) {
    
    suspend fun addWatermarkToImage(
        imageUri: Uri,
        meterInfo: String
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            // Создаем водяной знак с информацией о счетчике
            val watermarkedBitmap = createWatermarkedBitmap(imageUri, meterInfo)
            
            // Сохраняем изображение с водяным знаком в галерею
            saveImageToGallery(watermarkedBitmap, meterInfo)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun createWatermarkedBitmap(imageUri: Uri, meterInfo: String): Bitmap? {
        return try {
            // Загружаем исходное изображение
            val inputStream = context.contentResolver.openInputStream(imageUri)
            val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            if (originalBitmap == null) return null
            
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
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    stream.close()
                }
                imageUri
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    fun createTempImageUri(): Uri {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "METER_${timeStamp}_"
        
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val image = File.createTempFile(imageFileName, ".jpg", storageDir)
        
        // Используем FileProvider для создания безопасного URI
        return FileProvider.getUriForFile(
            context,
            "com.metersync.fileprovider",
            image
        )
    }
}

@Composable
fun rememberCameraManager(): CameraManager {
    val context = LocalContext.current
    return remember { CameraManager(context) }
}
