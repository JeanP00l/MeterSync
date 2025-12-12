package com.metersync.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.metersync.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

data class SyncSessionData(
    val token: String,
    val url: String,
    val localIP: String,
    val port: Int
)

data class PhotoInfo(
    val uri: Uri,
    val name: String,
    val dateTaken: Long,
    val size: Long,
    val counterNumber: String?
)

class PhotoSyncManager(private val context: Context) {
    private val client = OkHttpClient()

    /**
     * Инициализирует синхронизацию с сервером
     */
    suspend fun initSync(sessionData: SyncSessionData, totalPhotos: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            // Извлекаем базовый URL из sessionData.url
            val baseUrl = sessionData.url.substringBefore("/sync")
            val url = "$baseUrl/init?token=${sessionData.token}"
            
            Logger.log("Initiating sync: baseUrl=$baseUrl, url=$url, totalPhotos=$totalPhotos", "NETWORK")
            
            // Проверка: если URL содержит localhost, Android не сможет подключиться
            if (url.contains("localhost") || url.contains("127.0.0.1")) {
                Logger.logError("ERROR: Cannot connect to localhost from Android device! URL: $url", null)
                return@withContext false
            }
            
            val json = JSONObject().apply {
                put("total", totalPhotos)
            }
            
            val requestBody = json.toString().toByteArray().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            
            Logger.log("Photo sync init: success=$success", "NETWORK")
            
            if (!success) {
                val responseBody = response.body?.string() ?: ""
                Logger.logError("Init sync failed: code=${response.code}, body=$responseBody", null)
            }
            
            return@withContext success
        } catch (e: Exception) {
            Logger.logError("Failed to init photo sync: url=${sessionData.url}, error=${e.message}", e)
            return@withContext false
        }
    }

    /**
     * Загружает одно фото на сервер
     */
    suspend fun uploadPhoto(
        sessionData: SyncSessionData,
        photoInfo: PhotoInfo,
        progressCallback: (Int, Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Читаем файл из URI
            val inputStream: InputStream? = context.contentResolver.openInputStream(photoInfo.uri)
            if (inputStream == null) {
                Logger.logError("Cannot open photo stream", null)
                return@withContext false
            }

            // Создаем временный файл
            val tempFile = File.createTempFile("photo_sync_", ".jpg", context.cacheDir)
            val outputStream = FileOutputStream(tempFile)
            inputStream.copyTo(outputStream)
            outputStream.close()
            inputStream.close()

            // Читаем EXIF метаданные для получения номера счетчика
            val counterNumber = readCounterNumberFromEXIF(photoInfo.uri) ?: photoInfo.counterNumber

            // Формируем дату создания
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")
            val dateTakenStr = dateFormat.format(Date(photoInfo.dateTaken))

            // Создаем multipart запрос
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("photo", photoInfo.name, tempFile.asRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("counterNumber", counterNumber ?: "")
                .addFormDataPart("originalName", photoInfo.name)
                .addFormDataPart("dateTaken", dateTakenStr)
                .build()

            // Извлекаем базовый URL из sessionData.url
            val baseUrl = sessionData.url.substringBefore("/sync")
            val url = "$baseUrl/sync?token=${sessionData.token}"
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            // Удаляем временный файл
            tempFile.delete()

            val success = response.isSuccessful
            if (success && responseBody != null) {
                val json = JSONObject(responseBody)
                val isDuplicate = json.optBoolean("isDuplicate", false)
                Logger.log("Photo uploaded: ${photoInfo.name}, duplicate=$isDuplicate, response=$responseBody", "NETWORK")
            } else {
                Logger.logError("Upload failed: ${photoInfo.name}, code=${response.code}, body=$responseBody", null)
            }

            return@withContext success
        } catch (e: Exception) {
            Logger.logError("Failed to upload photo: ${photoInfo.name}", e)
            return@withContext false
        }
    }

    /**
     * Читает номер счетчика из EXIF метаданных USER_COMMENT
     */
    private fun readCounterNumberFromEXIF(uri: Uri): String? {
        return try {
            // Пробуем сначала через InputStream
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                try {
                    val exif = ExifInterface(inputStream)
                    val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                    
                    if (userComment != null && userComment.isNotBlank()) {
                        Logger.log("Found USER_COMMENT via InputStream: $userComment for URI: $uri", "SYNC")
                        return userComment
                    } else {
                        Logger.log("No USER_COMMENT found via InputStream for URI: $uri", "SYNC")
                    }
                } finally {
                    inputStream.close()
                }
            } else {
                Logger.log("Cannot open input stream for URI: $uri", "SYNC")
            }
            
            // Если через InputStream не получилось, пробуем через временный файл
            // Это может помочь, если есть проблемы с чтением EXIF через поток
            var tempFile: File? = null
            try {
                val inputStream2 = context.contentResolver.openInputStream(uri)
                if (inputStream2 != null) {
                    tempFile = File.createTempFile("exif_read_", ".jpg", context.cacheDir)
                    val outputStream = FileOutputStream(tempFile)
                    inputStream2.copyTo(outputStream)
                    outputStream.close()
                    inputStream2.close()
                    
                    val exif = ExifInterface(tempFile.absolutePath)
                    val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                    
                    if (userComment != null && userComment.isNotBlank()) {
                        Logger.log("Found USER_COMMENT via temp file: $userComment for URI: $uri", "SYNC")
                        return userComment
                    } else {
                        Logger.log("No USER_COMMENT found via temp file for URI: $uri", "SYNC")
                    }
                }
            } catch (e: Exception) {
                Logger.log("Failed to read EXIF via temp file for URI: $uri: ${e.message}", "SYNC")
            } finally {
                tempFile?.delete()
            }
            
            null
        } catch (e: Exception) {
            Logger.logError("Failed to read EXIF metadata for URI: $uri", e)
            null
        }
    }
    
    /**
     * Получает путь к файлу из MediaStore URI
     */
    private fun getFilePathFromUri(uri: Uri): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - используем MediaStore
                val projection = arrayOf(MediaStore.Images.Media.DATA)
                val cursor = context.contentResolver.query(uri, projection, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val columnIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                        it.getString(columnIndex)
                    } else {
                        null
                    }
                }
            } else {
                // Android 9 и ниже
                uri.path
            }
        } catch (e: Exception) {
            Logger.logError("Failed to get file path from URI: $uri", e)
            null
        }
    }

    /**
     * Получает список фото с EXIF метаданными USER_COMMENT
     * Фильтрует только фото, сделанные через приложение MeterSync (имеют префикс "meter_" в имени файла)
     */
    suspend fun getPhotosWithEXIF(): List<PhotoInfo> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<PhotoInfo>()
        
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.SIZE
            )

            // Фильтруем только фото, сделанные через приложение MeterSync
            // Фото, сделанные через приложение, имеют префикс "meter_" в имени файла
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("meter_%")
            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            
            Logger.log("Searching photos made by MeterSync app (filename starts with 'meter_')", "SYNC")
            
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            var totalScanned = 0
            var withCounterNumber = 0
            
            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                while (it.moveToNext()) {
                    totalScanned++
                    val id = it.getLong(idColumn)
                    val name = it.getString(nameColumn)
                    var dateTaken = it.getLong(dateColumn)
                    val size = it.getLong(sizeColumn)

                    // Если дата равна 0 или не установлена, используем текущее время
                    if (dateTaken == 0L) {
                        dateTaken = System.currentTimeMillis()
                        Logger.log("Date taken is 0 for $name, using current time", "SYNC")
                    }

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    // Читаем номер счетчика из EXIF
                    val counterNumber = readCounterNumberFromEXIF(contentUri)
                    
                    Logger.log("Photo: $name, dateTaken=$dateTaken, counterNumber=${counterNumber ?: "null"}", "SYNC")

                    // Добавляем только фото с USER_COMMENT (номером счетчика)
                    if (counterNumber != null && counterNumber.isNotBlank()) {
                        withCounterNumber++
                        photos.add(
                            PhotoInfo(
                                uri = contentUri,
                                name = name,
                                dateTaken = dateTaken,
                                size = size,
                                counterNumber = counterNumber
                            )
                        )
                    } else {
                        Logger.log("Skipping photo $name: no USER_COMMENT found", "SYNC")
                    }
                }
            }
            
            Logger.log("Photo scan complete: total scanned=$totalScanned, with counter number=$withCounterNumber, final count=${photos.size}", "SYNC")
        } catch (e: Exception) {
            Logger.logError("Failed to get photos with EXIF", e)
        }

        return@withContext photos
    }

    /**
     * Парсит данные сессии из JSON строки (из QR-кода)
     */
    fun parseSessionData(jsonString: String): SyncSessionData? {
        return try {
            val json = JSONObject(jsonString)
            
            // Поддержка нового формата (короткие ключи: t, ip, p) с обратной совместимостью
            val token = json.optString("t").takeIf { it.isNotEmpty() } 
                ?: json.optString("token", "")
            val localIP = json.optString("ip").takeIf { it.isNotEmpty() } 
                ?: json.optString("localIP", "")
            val port = json.optInt("p", -1).takeIf { it > 0 } 
                ?: json.optInt("port", 8080)
            
            // Если url есть в JSON (старый формат), используем его, иначе строим из ip и port
            val url = json.optString("url").takeIf { it.isNotEmpty() }
                ?: "http://$localIP:$port/sync?token=$token"
            
            val parsedData = SyncSessionData(
                token = token,
                url = url,
                localIP = localIP,
                port = port
            )
            Logger.log("Parsed session data: token=${parsedData.token.take(8)}..., url=${parsedData.url}, localIP=${parsedData.localIP}, port=${parsedData.port}", "SYNC")
            
            // Проверка: если URL содержит localhost, это проблема для Android устройства
            if (parsedData.url.contains("localhost") || parsedData.url.contains("127.0.0.1")) {
                Logger.logError("WARNING: URL contains localhost/127.0.0.1! Android device cannot connect to localhost. URL: ${parsedData.url}", null)
            }
            
            parsedData
        } catch (e: Exception) {
            Logger.logError("Failed to parse session data", e)
            null
        }
    }
}

// Расширение для создания RequestBody из ByteArray
private fun ByteArray.toRequestBody(contentType: okhttp3.MediaType): okhttp3.RequestBody {
    return okhttp3.RequestBody.create(contentType, this)
}

