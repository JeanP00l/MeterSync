package com.metersync.viewmodel

import android.app.Application
import android.content.Context
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metersync.data.AppDatabase
import com.metersync.data.entity.Address
import com.metersync.data.entity.Meter
import com.metersync.security.CredentialStore
import com.metersync.utils.Logger
import com.metersync.web.WebViewManager
import com.metersync.web.WebViewRestorePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MeterViewModel(app: Application) : AndroidViewModel(app), WebViewManager.Listener {
    private var currentWebViewManager: WebViewManager? = null
    private var pendingLoginScript: String? = null
    private var pendingAddressScript: String? = null
    private var pendingMetersScript: String? = null
    private var pendingLoginCallback: (() -> Unit)? = null
    private var pendingMetersCallback: (() -> Unit)? = null
    private val db = AppDatabase.get(app)
    private val credentialStore = CredentialStore(app)
    private val context = app.applicationContext
    private val webViewRestorePoint = WebViewRestorePoint(app.applicationContext)

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    private val _bulkLoading = MutableStateFlow(false)
    val bulkLoading: StateFlow<Boolean> = _bulkLoading

    private val _isLoggingOut = MutableStateFlow(false)
    val isLoggingOut: StateFlow<Boolean> = _isLoggingOut
    
    private val _showWebView = MutableStateFlow(false)
    val showWebView: StateFlow<Boolean> = _showWebView
    
    private var bulkLoadingAddresses: List<Address> = emptyList()
    private var currentBulkAddressIndex = 0

    val addresses: Flow<List<Address>> = db.addressDao().getAll()
    
    fun getCurrentWebView(): android.webkit.WebView? = currentWebViewManager?.getWebView()
    
    fun setShowWebView(show: Boolean) {
        _showWebView.value = show
    }

    init {
        Logger.init(context)
        Logger.log("MeterViewModel initialized", "INIT")
        
        // Создаем точку восстановления WebView при первом запуске
        if (!webViewRestorePoint.isRestorePointCreated()) {
            Logger.logWebView("Creating WebView restore point on first launch")
            webViewRestorePoint.createRestorePoint()
        } else {
            Logger.logWebView("WebView restore point already exists")
        }
    }

    fun metersByAddress(addressId: Long): Flow<List<Meter>> = db.meterDao().getByAddress(addressId)
    
    // Статистика по счетчикам
    suspend fun getTotalMetersCount(): Int = db.meterDao().getTotalMetersCount()
    suspend fun getNotCheckedCount(): Int = db.meterDao().getNotCheckedCount()
    suspend fun getCheckedNotLoadedCount(): Int = db.meterDao().getCheckedNotLoadedCount()
    suspend fun getLoadedCount(): Int = db.meterDao().getLoadedCount()
    
    // Статистика по конкретному адресу
    suspend fun getTotalMetersCountByAddress(addressId: Long): Int = db.meterDao().getTotalMetersCountByAddress(addressId)
    suspend fun getCheckedMetersCountByAddress(addressId: Long): Int = db.meterDao().getCheckedMetersCountByAddress(addressId)
    
    // Обновление статуса счетчика
    suspend fun updateMeterStatus(meterId: Long, status: com.metersync.data.entity.MeterStatus) {
        db.meterDao().updateMeterStatus(meterId, status)
    }

    fun saveCredentials(login: String, password: String) {
        Logger.log("Saving credentials for user: $login", "CREDENTIALS")
        try {
            credentialStore.save(login, password)
            Logger.log("Credentials saved successfully", "CREDENTIALS")
        } catch (e: Exception) {
            Logger.logError("Failed to save credentials", e)
        }
    }

    fun loadAllMeters() {
        Logger.log("Starting loadAllMeters", "MAIN")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _bulkLoading.value = true
                _error.value = null
                
                bulkLoadingAddresses = db.addressDao().getAll().first()
                currentBulkAddressIndex = 0
                
                Logger.logDatabase("Loading meters for ${bulkLoadingAddresses.size} addresses (updating all, including existing)")
                
                // Загружаем первый адрес
                loadNextBulkAddress()
                
            } catch (e: Exception) {
                Logger.logError("Error in loadAllMeters", e)
                _error.value = "Ошибка загрузки всех счетчиков: ${e.message}"
                _bulkLoading.value = false
            }
        }
    }

    /**
     * Загружает счетчики для конкретного адреса (оптимизированная версия)
     */
    private fun loadMetersForAddress(webViewManager: WebViewManager, address: Address) {
        Logger.logWebView("Loading meters for address: ${address.fullAddress}")
        val metersScript = getMetersParsingScript(address.fullAddress)
        pendingMetersScript = metersScript
        
        // Быстрая проверка: находимся ли мы на странице со списком адресов
        webViewManager.evaluateJs("""
            (function() {
                const addressContainer = document.querySelector('div._dateTasks_36r29_18');
                return !!addressContainer;
            })();
        """.trimIndent()) { result ->
            if (result == "false") {
                // Не на странице со списком, возвращаемся назад
                webViewManager.navigateBackToAddressList {
                    // После возврата запускаем скрипт с минимальной задержкой
                    viewModelScope.launch(Dispatchers.Main) {
                        kotlinx.coroutines.delay(300) // Уменьшено с 1000ms до 300ms
                        webViewManager.evaluateJs(metersScript)
                    }
                }
            } else {
                // Уже на странице со списком, запускаем скрипт сразу
                webViewManager.evaluateJs(metersScript)
            }
        }
    }
    
    private fun loadNextBulkAddress() {
        if (currentBulkAddressIndex >= bulkLoadingAddresses.size) {
            // Все адреса загружены
            _bulkLoading.value = false
            Logger.log("loadAllMeters completed", "MAIN")
            return
        }
        
        val address = bulkLoadingAddresses[currentBulkAddressIndex]
        Logger.logDatabase("Loading meters for address: ${address.fullAddress} (${currentBulkAddressIndex + 1}/${bulkLoadingAddresses.size})")
        
        viewModelScope.launch(Dispatchers.Main) {
            // Переиспользуем существующий WebView или создаем новый только при первом адресе
            val webViewManager = currentWebViewManager ?: run {
                Logger.logWebView("Creating new WebViewManager for bulk loading")
                WebViewManager(context, this@MeterViewModel).also {
                    currentWebViewManager = it
                }
            }
            
            // При массовой загрузке проверяем авторизацию только при первом адресе
            val isFirstAddress = currentBulkAddressIndex == 0
            if (isFirstAddress) {
                // Проверяем авторизацию только при первом адресе
                webViewManager.checkIfAuthorized { isAuthorized ->
                    if (isAuthorized) {
                        loadMetersForAddress(webViewManager, address)
                    } else {
                        // Не авторизованы, нужно войти сначала
                        Logger.logWebView("Not authorized, need to login first")
                        val (login, password) = credentialStore.get()
                        if (!login.isNullOrEmpty() && !password.isNullOrEmpty()) {
                            val loginScript = getLoginScript(login, password)
                            val metersScript = getMetersParsingScript(address.fullAddress)
                            pendingLoginScript = loginScript
                            pendingMetersScript = metersScript
                            webViewManager.loadUrl("https://meter.printecs.com/")
                        } else {
                            Logger.logError("Credentials not found for bulk loading")
                            _error.value = "Учетные данные не найдены"
                            _bulkLoading.value = false
                        }
                    }
                }
            } else {
                // Для последующих адресов считаем, что уже авторизованы
                loadMetersForAddress(webViewManager, address)
            }
        }
    }

    fun clearCache() {
        Logger.log("Starting cache clearing", "MAIN")
        _isLoggingOut.value = true // Блокируем кнопку входа
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Clear database - синхронно, чтобы убедиться что данные удалены
                Logger.logDatabase("Clearing all addresses")
                val addressesBefore = db.addressDao().getAll().first().size
                Logger.logDatabase("Addresses before deletion: $addressesBefore")
                db.addressDao().deleteAll()
                val addressesAfter = db.addressDao().getAll().first().size
                Logger.logDatabase("Addresses after deletion: $addressesAfter")
                
                Logger.logDatabase("Clearing all meters")
                val metersBefore = db.meterDao().getTotalMetersCount()
                Logger.logDatabase("Meters before deletion: $metersBefore")
                db.meterDao().deleteAll()
                val metersAfter = db.meterDao().getTotalMetersCount()
                Logger.logDatabase("Meters after deletion: $metersAfter")

                // Clear credentials
                Logger.log("Clearing saved credentials", "CREDENTIALS")
                credentialStore.clear()

                // Restore WebView to clean state using restore point
                Logger.logWebView("Restoring WebView to clean state")
                withContext(Dispatchers.Main) {
                    try {
                        // Шаг 1: Сначала пытаемся выйти через UI (если WebView еще существует)
                        // Это важно - нужно завершить сессию на сервере ПЕРЕД уничтожением WebView
                        currentWebViewManager?.let { webViewManager ->
                            try {
                                Logger.logWebView("Attempting UI logout before destroying WebView")
                                // Пытаемся выйти через UI - это завершит сессию на сервере
                                webViewManager.clearCacheAndReturnToLogin()
                                // Даем время на выполнение выхода через UI (серия кликов и ожиданий)
                                kotlinx.coroutines.delay(5000)
                                Logger.logWebView("UI logout sequence completed, destroying WebView")
                            } catch (e: Exception) {
                                Logger.logError("Error during UI logout", e)
                            }
                        }
                        
                        // Шаг 2: Теперь уничтожаем WebView
                        currentWebViewManager?.let { webViewManager ->
                            try {
                                Logger.logWebView("Destroying old WebViewManager")
                                webViewManager.destroy()
                            } catch (e: Exception) {
                                Logger.logError("Error destroying WebViewManager", e)
                            }
                        }
                        
                        // Обнуляем ссылку
                        currentWebViewManager = null

                        // Шаг 3: Восстанавливаем WebView к точке восстановления (очищает все данные)
                        Logger.logWebView("Clearing all WebView data via restore point")
                        webViewRestorePoint.restoreToPoint()
                        
                        // Шаг 4: Дополнительная очистка: удаляем все куки глобально
                        try {
                            val cookieManager = android.webkit.CookieManager.getInstance()
                            cookieManager.removeAllCookies(null)
                            cookieManager.flush()
                            Logger.logWebView("All cookies cleared globally")
                        } catch (e: Exception) {
                            Logger.logError("Error clearing cookies", e)
                        }
                        
                        // Небольшая задержка для завершения очистки
                        kotlinx.coroutines.delay(1000)
                        
                        // Шаг 5: Создаем новый WebViewManager с чистым состоянием
                        Logger.logWebView("Creating new WebViewManager with clean state")
                        val newWebViewManager = WebViewManager(context, this@MeterViewModel)
                        currentWebViewManager = newWebViewManager
                        
                        // Шаг 6: Загружаем страницу входа с timestamp для новой сессии
                        val loginUrl = "https://meter.printecs.com/?_t=${System.currentTimeMillis()}"
                        Logger.logWebView("Loading login page: $loginUrl")
                        newWebViewManager.loadUrl(loginUrl)

                        Logger.logWebView("WebView restored to clean state and login page loaded successfully")
                    } catch (e: Exception) {
                        Logger.logError("Error recreating WebViewManager", e)
                    }
                }

                Logger.log("Cache cleared successfully", "MAIN")

            } catch (e: Exception) {
                Logger.logError("Error clearing cache", e)
            } finally {
                // Разблокируем кнопку входа после завершения процесса
                _isLoggingOut.value = false
                Logger.log("Logout process completed, login button enabled", "MAIN")
            }
        }
    }

    fun loadAddressesOnceIfNeeded(onDone: (() -> Unit)? = null) {
        Logger.log("Starting loadAddressesOnceIfNeeded", "MAIN")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Check if addresses are already loaded
                val existingAddresses = db.addressDao().getAll().first()
                if (existingAddresses.isNotEmpty()) {
                    Logger.log("Addresses already loaded: ${existingAddresses.size}", "MAIN")
                    _loading.value = false
                    withContext(Dispatchers.Main) {
                        onDone?.invoke()
                    }
                    return@launch
                }

                _loading.value = true
                _error.value = null
                Logger.log("Loading state set to true", "MAIN")

                val (login, password) = credentialStore.get()
                Logger.log("Retrieved credentials: login=${login?.take(3)}***", "CREDENTIALS")

                if (login.isNullOrEmpty() || password.isNullOrEmpty()) {
                    val errorMsg = "Логин и пароль не найдены"
                    Logger.logError(errorMsg)
                    _error.value = errorMsg
                    _loading.value = false
                    return@launch
                }

                // Switch to Main thread for WebView creation
                withContext(Dispatchers.Main) {
                    Logger.logWebView("Creating WebViewManager on Main thread")
                    val webViewManager = WebViewManager(context, this@MeterViewModel)
                    currentWebViewManager = webViewManager
                    
                    // Показываем WebView для отладки
                    _showWebView.value = true

                    // Prepare scripts to execute when page is ready
                    val loginScript = getLoginScript(login, password)
                    val addressScript = getAddressParsingScript()
                    pendingLoginScript = loginScript
                    pendingAddressScript = addressScript

                    // Загружаем страницу входа с timestamp для создания новой сессии
                    val loginUrl = "https://meter.printecs.com/?_t=${System.currentTimeMillis()}"
                    Logger.logWebView("Loading URL: $loginUrl")
                    webViewManager.loadUrl(loginUrl)

                    Logger.log("WebView created and URL loading started", "MAIN")
                }

                // Store callback to execute after addresses are parsed
                pendingLoginCallback = onDone

            } catch (e: Exception) {
                Logger.logError("Error in loadAddressesOnceIfNeeded", e)
                _error.value = "Ошибка загрузки: ${e.message}"
                _loading.value = false
                onDone?.let { it() }
            }
        }
    }

    fun refreshMetersForAddress(addressId: Long, targetAddress: String, onDone: (() -> Unit)? = null) {
        Logger.log("Starting refreshMetersForAddress for addressId: $addressId, target: $targetAddress", "MAIN")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _loading.value = true
                _error.value = null
                Logger.log("Loading state set to true for meters refresh", "MAIN")
                
                // Store callback to execute after meters are parsed
                pendingMetersCallback = onDone
                
                // Switch to Main thread for WebView creation
                withContext(Dispatchers.Main) {
                    Logger.logWebView("Creating WebViewManager for meters on Main thread")
                    val webViewManager = WebViewManager(context, this@MeterViewModel)
                    currentWebViewManager = webViewManager
                    
                    // Prepare meters script to execute when page is ready
                    val metersScript = getMetersParsingScript(targetAddress)
                    pendingMetersScript = metersScript
                    
                    Logger.logWebView("Loading URL: https://meter.printecs.com/")
                    webViewManager.loadUrl("https://meter.printecs.com/")
                    
                    Logger.log("refreshMetersForAddress completed successfully", "MAIN")
                }
                
            } catch (e: Exception) {
                Logger.logError("Error in refreshMetersForAddress", e)
                _error.value = "Ошибка загрузки счетчиков: ${e.message}"
                _loading.value = false
                onDone?.let { it() }
            }
        }
    }

    override fun onPageReady() {
        Logger.logWebView("Page is ready for JavaScript execution")
        viewModelScope.launch {
            try {
                val webViewManager = currentWebViewManager
                if (webViewManager != null) {
                        // Execute pending scripts
                        pendingLoginScript?.let { script ->
                            Logger.logWebView("Executing pending login script")
                            
                            // Увеличиваем задержку для полной загрузки DOM после восстановления
                            val delay = if (webViewRestorePoint.isRestorePointCreated()) 5000L else 2000L
                            Logger.logWebView("Waiting ${delay}ms for DOM to be fully ready")
                            kotlinx.coroutines.delay(delay)
                            
                            // Выполняем скрипт входа
                            webViewManager.evaluateJs(script)
                            
                            pendingLoginScript = null

                            // Wait longer for login result and page load, then execute address script
                            kotlinx.coroutines.delay(8000)
                            pendingAddressScript?.let { addressScript ->
                                Logger.logWebView("Executing pending address script")
                                webViewManager.evaluateJs(addressScript)
                                pendingAddressScript = null
                            }
                        }
                    
                    pendingMetersScript?.let { script ->
                        Logger.logWebView("Executing pending meters script")
                        webViewManager.evaluateJs(script)
                        pendingMetersScript = null
                    }
                }
            } catch (e: Exception) {
                Logger.logError("Error executing pending scripts", e)
            }
        }
    }

    override fun onLoginResult(status: String, message: String) {
        Logger.logWebView("Login result received: status=$status, message=$message")
        viewModelScope.launch {
            if (status == "error") {
                val errorMsg = "Ошибка входа: $message"
                Logger.logError(errorMsg)
                _error.value = errorMsg
                _loading.value = false
                // WebView остается видимым при ошибке для отладки
            } else {
                Logger.logWebView("Login successful")
            }
        }
    }

    override fun onAddressesParsed(json: String) {
        Logger.logWebView("Addresses parsed result received: ${json.take(100)}...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (json.startsWith("error:")) {
                    val errorMsg = json.substring(6)
                    Logger.logError("Address parsing error: $errorMsg")
                    _error.value = errorMsg
                    return@launch
                }
                
                Logger.logDatabase("Parsing addresses JSON")
                val jsonArray = JSONArray(json)
                val addresses = mutableListOf<Address>()
                
                for (i in 0 until jsonArray.length()) {
                    val addressText = jsonArray.getString(i)
                    addresses.add(Address(fullAddress = addressText))
                    Logger.logDatabase("Added address: $addressText")
                }
                
                Logger.logDatabase("Saving ${addresses.size} addresses to database")
                db.addressDao().insertAll(addresses)
                Logger.logDatabase("Addresses saved successfully")
                
                    // Complete the login process
                    _loading.value = false
                    // Скрываем WebView после успешного входа
                    _showWebView.value = false
                    pendingLoginCallback?.let { callback ->
                        Logger.logUI("Executing login success callback")
                        // Execute callback on Main thread to avoid navigation issues
                        withContext(Dispatchers.Main) {
                            callback()
                        }
                        pendingLoginCallback = null
                    }
                
            } catch (e: Exception) {
                Logger.logError("Error parsing addresses", e)
                _error.value = "Ошибка парсинга адресов: ${e.message}"
            }
        }
    }

    override fun onMetersParsed(json: String) {
        Logger.logWebView("Meters parsed result received: ${json.take(100)}...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (json.startsWith("error:")) {
                    val errorMsg = json.substring(6)
                    Logger.logError("Meters parsing error: $errorMsg")
                    _error.value = errorMsg
                    return@launch
                }
                
                    Logger.logDatabase("Parsing meters JSON")
                    val jsonArray = JSONArray(json)
                    val meters = mutableListOf<Meter>()
                    
                    Logger.logDatabase("Total meters in JSON: ${jsonArray.length()}")
                    
                    for (i in 0 until jsonArray.length()) {
                        val meterObj = jsonArray.getJSONObject(i)
                        val apartment = meterObj.getString("apartment")
                        val meterNumber = meterObj.getString("meter")
                        val statusString = meterObj.optString("status", "NOT_CHECKED")
                        
                        // Преобразуем строку статуса в enum
                        val status = when (statusString) {
                            "NOT_CHECKED" -> com.metersync.data.entity.MeterStatus.NOT_CHECKED
                            "CHECKED_NOT_LOADED" -> com.metersync.data.entity.MeterStatus.CHECKED_NOT_LOADED
                            "LOADED" -> com.metersync.data.entity.MeterStatus.LOADED
                            else -> com.metersync.data.entity.MeterStatus.NOT_CHECKED
                        }
                        
                        Logger.logDatabase("Processing meter $i: apartment=$apartment, meter=$meterNumber, status=$statusString")
                    
                    // Find address ID by apartment text
                    val addressId = findAddressIdByApartment(apartment)
                    if (addressId != null) {
                        meters.add(Meter(
                            addressId = addressId,
                            apartment = apartment,
                            meterNumber = meterNumber,
                            status = status
                        ))
                        Logger.logDatabase("Added meter for addressId: $addressId with status: $statusString")
                    } else {
                        Logger.logError("Could not find addressId for apartment: $apartment")
                    }
                }
                
                // Save to database (clear old meters for this address first)
                if (meters.isNotEmpty()) {
                    val firstAddressId = meters.first().addressId
                    Logger.logDatabase("Clearing old meters for addressId: $firstAddressId")
                    db.meterDao().deleteByAddress(firstAddressId)
                    
                    Logger.logDatabase("Saving ${meters.size} meters to database")
                    db.meterDao().insertAll(meters)
                    Logger.logDatabase("Meters saved successfully")
                    
                    // Verify meters were saved (non-blocking)
                    try {
                        val meterList = db.meterDao().getByAddress(firstAddressId).first()
                        Logger.logDatabase("Verification: Found ${meterList.size} meters in database for addressId: $firstAddressId")
                        meterList.forEach { meter ->
                            Logger.logDatabase("Saved meter: ${meter.apartment} - ${meter.meterNumber}")
                        }
                    } catch (e: Exception) {
                        Logger.logError("Error verifying meters", e)
                    }
                } else {
                    Logger.logError("No meters to save")
                }
                
                Logger.logDatabase("Setting loading to false")
                _loading.value = false
                
                // Execute callback after meters are saved
                pendingMetersCallback?.let { callback ->
                    Logger.logUI("Executing meters success callback")
                    withContext(Dispatchers.Main) {
                        callback()
                    }
                    pendingMetersCallback = null
                }
                
                // Если идет массовая загрузка, возвращаемся к списку адресов и переходим к следующему
                if (_bulkLoading.value) {
                    currentBulkAddressIndex++
                    // Возвращаемся назад к списку адресов вместо перезагрузки страницы
                    withContext(Dispatchers.Main) {
                        currentWebViewManager?.let { webViewManager ->
                            webViewManager.navigateBackToAddressList {
                                // После возврата к списку загружаем следующий адрес
                                viewModelScope.launch(Dispatchers.Main) {
                                    kotlinx.coroutines.delay(200) // Уменьшено с 500ms до 200ms
                                    loadNextBulkAddress()
                                }
                            }
                        } ?: run {
                            // Если WebView был уничтожен, создаем новый
                            loadNextBulkAddress()
                        }
                    }
                }
                
            } catch (e: Exception) {
                Logger.logError("Error parsing meters", e)
                _error.value = "Ошибка парсинга счетчиков: ${e.message}"
                _loading.value = false
                
                // Execute callback even on error
                pendingMetersCallback?.let { callback ->
                    Logger.logUI("Executing meters error callback")
                    withContext(Dispatchers.Main) {
                        callback()
                    }
                    pendingMetersCallback = null
                }
                
                // Если идет массовая загрузка, переходим к следующему адресу даже при ошибке
                if (_bulkLoading.value) {
                    currentBulkAddressIndex++
                    withContext(Dispatchers.Main) {
                        currentWebViewManager?.let { webViewManager ->
                            webViewManager.navigateBackToAddressList {
                                viewModelScope.launch(Dispatchers.Main) {
                                    kotlinx.coroutines.delay(200) // Уменьшено с 500ms до 200ms
                                    loadNextBulkAddress()
                                }
                            }
                        } ?: run {
                            loadNextBulkAddress()
                        }
                    }
                }
            }
        }
    }

    private suspend fun findAddressIdByApartment(apartment: String): Long? {
        Logger.logDatabase("Finding addressId for apartment: $apartment")
        return withContext(Dispatchers.IO) {
            try {
                val addressList = db.addressDao().getAll().first()
                Logger.logDatabase("Checking ${addressList.size} addresses")
                
                // Сортируем адреса по длине (от длинных к коротким) для более точного поиска
                val sortedAddresses = addressList.sortedByDescending { it.fullAddress.length }
                
                // Пробуем разные стратегии поиска с более точным сопоставлением
                val result = sortedAddresses.find { address ->
                    // Стратегия 1: точное совпадение начала с учетом границ слов
                    val matches = apartment.startsWith(address.fullAddress) && 
                    (apartment.length == address.fullAddress.length || 
                     apartment[address.fullAddress.length] == ',' ||
                     apartment[address.fullAddress.length] == ' ')
                    if (matches) {
                        Logger.logDatabase("Strategy 1 match: apartment='$apartment' -> address='${address.fullAddress}'")
                    }
                    matches
                }?.id ?: sortedAddresses.find { address ->
                    // Стратегия 2: содержит адрес, но не является подстрокой другого адреса
                    val matches = apartment.contains(address.fullAddress) && 
                    !sortedAddresses.any { other -> 
                        other != address && other.fullAddress.contains(address.fullAddress)
                    }
                    if (matches) {
                        Logger.logDatabase("Strategy 2 match: apartment='$apartment' -> address='${address.fullAddress}'")
                    }
                    matches
                }?.id ?: sortedAddresses.find { address ->
                    // Стратегия 3: обратный поиск по первой части квартиры
                    val apartmentFirstPart = apartment.split(',')[0].trim()
                    val matches = address.fullAddress.contains(apartmentFirstPart) &&
                    apartmentFirstPart.length > 5 // Минимальная длина для надежности
                    if (matches) {
                        Logger.logDatabase("Strategy 3 match: apartment='$apartment' -> address='${address.fullAddress}'")
                    }
                    matches
                }?.id
                
                Logger.logDatabase("Found addressId: $result for apartment: $apartment")
                result
            } catch (e: Exception) {
                Logger.logError("Error finding addressId", e)
                null
            }
        }
    }

    private fun getLoginScript(login: String, password: String): String {
        return """
            (function() {
                function waitForElement(selector, timeout = 10000) {
                    return new Promise((resolve, reject) => {
                        const start = Date.now();
                        const interval = setInterval(() => {
                            const el = document.querySelector(selector);
                            if (el) {
                                clearInterval(interval);
                                resolve(el);
                            } else if (Date.now() - start > timeout) {
                                clearInterval(interval);
                                reject(new Error("Timeout: " + selector));
                            }
                        }, 300);
                    });
                }

                // Специальная функция для работы с React полями (как в вашем примере)
                async function setReactInputValue(selector, value) {
                    const input = await waitForElement(selector);
                    await setReactInputValueForElement(input, value);
                }
                
                // Функция для установки значения в элемент напрямую (для React Hook Form)
                async function setReactInputValueForElement(input, value) {
                    console.log("Setting value for input:", {id: input.id, name: input.name, currentValue: input.value, newValue: value});
                    
                    // Кликаем на элемент для активации
                    input.click();
                    await new Promise(resolve => setTimeout(resolve, 100));
                    
                    // Фокус на элемент
                    input.focus();
                    await new Promise(resolve => setTimeout(resolve, 50));
                    
                    // Очищаем текущее значение
                    input.value = '';
                    
                    // Устанавливаем новое значение через нативный setter
                    const nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value")?.set;
                    if (nativeInputValueSetter) {
                        nativeInputValueSetter.call(input, value);
                    } else {
                        input.value = value;
                    }
                    
                    // Обновляем внутренний трекер React
                    const tracker = input._valueTracker;
                    if (tracker) {
                        tracker.setValue('');
                    }
                    
                    // Создаем события для React Hook Form
                    // React Hook Form использует onChange события
                    const inputEvent = new Event('input', { bubbles: true, cancelable: true });
                    const changeEvent = new Event('change', { bubbles: true, cancelable: true });
                    
                    // Используем InputEvent вместо обычного Event (лучше для React)
                    const inputEventObj = new InputEvent('input', {
                        bubbles: true,
                        cancelable: true,
                        data: value,
                        inputType: 'insertText',
                        isComposing: false
                    });
                    
                    input.dispatchEvent(inputEventObj);
                    input.dispatchEvent(changeEvent);
                    
                    // Для React Hook Form нужно вызвать onChange с объектом события
                    const syntheticEvent = {
                        target: {
                            value: value,
                            name: input.name || input.id,
                            id: input.id
                        },
                        currentTarget: {
                            value: value,
                            name: input.name || input.id,
                            id: input.id
                        },
                        bubbles: true,
                        cancelable: true
                    };
                    
                    // Поиск React Fiber и вызов обработчиков
                    function findReactFiber(element) {
                        const keys = Object.keys(element);
                        for (let key of keys) {
                            if (key.startsWith('__reactFiber') || 
                                key.startsWith('__reactInternalInstance') ||
                                key.startsWith('__reactContainer')) {
                                return element[key];
                            }
                        }
                        return null;
                    }
                    
                    // Ищем React Fiber и вызываем обработчики
                    let fiber = findReactFiber(input);
                    let depth = 0;
                    while (fiber && depth < 15) {
                        if (fiber.memoizedProps) {
                            const props = fiber.memoizedProps;
                            
                            // Вызываем onChange если есть
                            if (props.onChange) {
                                props.onChange(syntheticEvent);
                                console.log("Called onChange from React Fiber at depth " + depth);
                            }
                            
                            // Вызываем onInput если есть
                            if (props.onInput) {
                                props.onInput(syntheticEvent);
                                console.log("Called onInput from React Fiber at depth " + depth);
                            }
                            
                            // Если есть register, пробуем его использовать
                            if (props.register && typeof props.register === 'function') {
                                try {
                                    const handlers = props.register(props.name || input.name || input.id);
                                    if (handlers && handlers.onChange) {
                                        handlers.onChange(syntheticEvent);
                                        console.log("Called onChange from register");
                                    }
                                } catch (e) {
                                    console.log("Error using register:", e);
                                }
                            }
                        }
                        
                        // Переходим к родительскому компоненту
                        fiber = fiber.return || fiber._owner;
                        depth++;
                    }
                    
                    // Проверяем наличие onInput handler напрямую
                    if (input.oninput) {
                        input.oninput(syntheticEvent);
                    }
                    
                    // Blur для завершения
                    input.blur();
                    
                    await new Promise(resolve => setTimeout(resolve, 200));
                    
                    console.log("Value set, final value:", input.value);
                }

                    function findLoginInputs() {
                        console.log("=== Finding login inputs ===");
                        
                        // Пробуем разные способы поиска
                        let login = null;
                        let password = null;
                        
                        // Способ 1: По id (если есть)
                        login = document.querySelector('input#login');
                        password = document.querySelector('input#Пароль');
                        
                        console.log("By ID:", {login: !!login, password: !!password});
                        
                        // Способ 2: По name атрибуту
                        if (!login) {
                            login = document.querySelector('input[name="login"]');
                        }
                        if (!password) {
                            password = document.querySelector('input[name="password"]') ||
                                      document.querySelector('input[type="password"]');
                        }
                        
                        console.log("By name:", {login: !!login, password: !!password});
                        
                        // Способ 3: По типу и позиции (первый text, первый password)
                        if (!login) {
                            const textInputs = Array.from(document.querySelectorAll('input[type="text"]'));
                            login = textInputs.find(input => 
                                input.name === 'login' || 
                                input.id === 'login' ||
                                (input.getAttribute('data-cursor-ref') && input.closest('div[class*="_inputField"]'))
                            ) || textInputs[0];
                        }
                        
                        if (!password) {
                            password = document.querySelector('input[type="password"]');
                        }
                        
                        // Способ 4: Ищем внутри div с классом _inputField_1lul1_31
                        if (!login || !password) {
                            const inputFields = document.querySelectorAll('div._inputField_1lul1_31, div[class*="_inputField"]');
                            inputFields.forEach(field => {
                                const input = field.querySelector('input');
                                if (input) {
                                    if (input.type === 'text' && !login) {
                                        login = input;
                                    } else if (input.type === 'password' && !password) {
                                        password = input;
                                    }
                                }
                            });
                        }
                        
                        console.log("After all methods:", {
                            login: login ? {id: login.id, name: login.name, type: login.type} : null,
                            password: password ? {id: password.id, name: password.name, type: password.type} : null
                        });
                        
                        // Ищем кнопку входа
                        let submit = Array.from(document.querySelectorAll('button')).find(btn => {
                            const text = btn.textContent?.trim().toLowerCase();
                            return text === 'вход' || text === 'войти';
                        }) || document.querySelector('button[type="submit"]');
                        
                        console.log("Submit button:", submit ? {text: submit.textContent, type: submit.type} : null);
                        
                        return { login, password, submit };
                    }

                async function login() {
                    try {
                        console.log("=== LOGIN DEBUG INFO ===");
                        console.log("Current URL:", window.location.href);
                        console.log("Page title:", document.title);
                        console.log("Document ready state:", document.readyState);
                        
                        // Ждем появления input элементов напрямую
                        console.log("Waiting for input elements to appear...");
                        let loginInput = null;
                        let passInput = null;
                        let attempts = 0;
                        
                        while (attempts < 50 && (!loginInput || !passInput)) {
                            await new Promise(resolve => setTimeout(resolve, 200));
                            
                            // Ищем input элементы напрямую
                            loginInput = document.querySelector('input#login');
                            passInput = document.querySelector('input#Пароль');
                            
                            // Также проверяем по name атрибутам на случай если id не работают
                            if (!loginInput) {
                                loginInput = document.querySelector('input[name="login"]');
                            }
                            if (!passInput) {
                                passInput = document.querySelector('input[name="password"]');
                            }
                            
                            attempts++;
                            if (attempts % 5 === 0) {
                                console.log('Attempt ' + attempts + ': login=' + !!loginInput + ', password=' + !!passInput);
                            }
                        }
                        
                        if (!loginInput || !passInput) {
                            const errorMsg = 'Поля ввода не найдены после ' + (attempts * 200) + 'ms. Login: ' + !!loginInput + ', Password: ' + !!passInput;
                            console.log("ERROR:", errorMsg);
                            window.Android.onLoginResult("error", errorMsg);
                            return;
                        }
                        
                        console.log('Input elements found! Waiting additional 1 second for React to fully initialize...');
                        // Дополнительное ожидание 1 секунду после появления полей
                        await new Promise(resolve => setTimeout(resolve, 1000));
                        
                        // Ищем кнопку отправки
                        let submitBtn = document.querySelector('button[type="submit"]');
                        if (!submitBtn) {
                            // Пробуем альтернативные селекторы для кнопки
                            submitBtn = document.querySelector('button._button_1xwkq_1');
                        }
                        if (!submitBtn) {
                            // Пробуем найти по тексту
                            const allButtons = document.querySelectorAll('button');
                            submitBtn = Array.from(allButtons).find(btn => 
                                btn.textContent && btn.textContent.toLowerCase().includes('вход')
                            );
                        }
                        
                        if (!submitBtn) {
                            const errorMsg = 'Кнопка отправки не найдена';
                            console.log("ERROR:", errorMsg);
                            window.Android.onLoginResult("error", errorMsg);
                            return;
                        }
                        
                        console.log("All elements found:", {
                            login: {id: loginInput.id, name: loginInput.name, type: loginInput.type},
                            password: {id: passInput.id, name: passInput.name, type: passInput.type},
                            submit: {text: submitBtn.textContent}
                        });

                        console.log("All elements found, proceeding with login");
                        console.log("Login element details:", {
                            id: loginInput.id,
                            name: loginInput.name,
                            type: loginInput.type,
                            className: loginInput.className,
                            value: loginInput.value
                        });
                        console.log("Password element details:", {
                            id: passInput.id,
                            name: passInput.name,
                            type: passInput.type,
                            className: passInput.className
                        });
                        
                        // Убеждаемся, что элементы кликабельны и активны
                        console.log("Activating input fields...");
                        
                        // Кликаем на контейнер логина, если есть
                        const loginContainer = loginInput.closest('div._inputContainer_ydbik_41');
                        if (loginContainer) {
                            console.log("Clicking on login container...");
                            loginContainer.click();
                            await new Promise(resolve => setTimeout(resolve, 100));
                        }
                        
                        // Кликаем непосредственно на input логина несколько раз для активации
                        console.log("Clicking on login input...");
                        for (let i = 0; i < 3; i++) {
                            loginInput.click();
                            await new Promise(resolve => setTimeout(resolve, 50));
                        }
                        
                        // Используем найденные элементы напрямую, а не через селекторы
                        console.log("Setting login value...");
                        await setReactInputValueForElement(loginInput, "$login");
                        console.log("Login value set, current value:", loginInput.value);
                        
                        // Кликаем на контейнер пароля, если есть
                        const passwordContainer = passInput.closest('div._inputContainer_ydbik_41');
                        if (passwordContainer) {
                            console.log("Clicking on password container...");
                            passwordContainer.click();
                            await new Promise(resolve => setTimeout(resolve, 100));
                        }
                        
                        // Кликаем непосредственно на input пароля несколько раз для активации
                        console.log("Clicking on password input...");
                        for (let i = 0; i < 3; i++) {
                            passInput.click();
                            await new Promise(resolve => setTimeout(resolve, 50));
                        }
                        
                        console.log("Setting password value...");
                        await setReactInputValueForElement(passInput, "$password");
                        console.log("Password value set");

                        // Wait a bit before submitting
                        await new Promise(resolve => setTimeout(resolve, 500));

                        // Submit form
                        submitBtn.click();

                        // Wait for navigation or success indicator
                        await new Promise(resolve => setTimeout(resolve, 3000));

                        // Check if we're still on login page or got redirected
                        const currentUrl = window.location.href;
                        const stillOnLogin = document.querySelector('input[type="password"]') || 
                                          document.querySelector('input[placeholder*="пароль"]') ||
                                          currentUrl.includes('login');

                        if (stillOnLogin) {
                            window.Android.onLoginResult("error", "Неверный логин или пароль");
                        } else {
                            window.Android.onLoginResult("success", "");
                        }
                    } catch (err) {
                        window.Android.onLoginResult("error", err.message);
                    }
                }

                login();
            })();
        """.trimIndent()
    }

    private fun getAddressParsingScript(): String {
        return """
            (function() {
                function waitForElement(selector, timeout = 15000) {
                    return new Promise((resolve, reject) => {
                        const start = Date.now();
                        const interval = setInterval(() => {
                            const el = document.querySelector(selector);
                            if (el) {
                                clearInterval(interval);
                                resolve(el);
                            } else if (Date.now() - start > timeout) {
                                clearInterval(interval);
                                reject(new Error("Timeout: " + selector));
                            }
                        }, 300);
                    });
                }

                async function parseAddresses() {
                    try {
                        // Ждем появления контейнера с адресами (как в вашем примере)
                        const container = await waitForElement('div._dateTasks_36r29_18', 15000);
                        
                        if (!container) {
                            window.Android.onAddressesParsed("error:Контейнер адресов не найден после ожидания");
                            return;
                        }

                        // Ждем еще немного, чтобы все адреса загрузились
                        await new Promise(resolve => setTimeout(resolve, 1000));

                        // Извлекаем адреса из конкретной структуры
                        const addresses = [];
                        const taskItems = container.querySelectorAll('div._taskItem_36r29_38');
                        
                        taskItems.forEach(item => {
                            // Пробуем разные селекторы для надежности
                            const titleElement = item.querySelector('div._taskTitle_36r29_45 > span') ||
                                               item.querySelector('div._taskTitle_36r29_45 span') ||
                                               item.querySelector('span');
                            if (titleElement) {
                                const addressText = titleElement.innerText.trim();
                                // Фильтруем валидные адреса
                                if (addressText && 
                                    addressText.length > 5 && 
                                    addressText !== "Без даты" &&
                                    !addressText.includes('Загрузка')) {
                                    addresses.push(addressText);
                                }
                            }
                        });

                        if (addresses.length === 0) {
                            // Если не нашли по точным селекторам, попробуем альтернативные
                            const allSpans = container.querySelectorAll('span');
                            allSpans.forEach(span => {
                                const text = span.innerText.trim();
                                if (text && text.length > 5 && 
                                    !text.includes('Без даты') && 
                                    !text.includes('Загрузка') &&
                                    (text.includes('Волжск') || text.includes('Звенигово') || 
                                     text.includes('Йошкар-Ола') || text.includes('ул.') || 
                                     text.includes('д.') || text.includes(','))) {
                                    addresses.push(text);
                                }
                            });
                        }
                        
                        if (addresses.length === 0) {
                            window.Android.onAddressesParsed("error:Адреса не найдены в контейнере");
                        } else {
                            // Remove duplicates
                            const uniqueAddresses = [...new Set(addresses)];
                            window.Android.onAddressesParsed(JSON.stringify(uniqueAddresses));
                        }
                    } catch (err) {
                        window.Android.onAddressesParsed("error:" + err.message);
                    }
                }

                parseAddresses();
            })();
        """.trimIndent()
    }

    private fun getMetersParsingScript(targetAddress: String): String {
        return """
            (function() {
                const TARGET_ADDRESS = "$targetAddress";

                function waitForElement(selector, timeout = 10000) {
                    return new Promise((resolve, reject) => {
                        const start = Date.now();
                        const interval = setInterval(() => {
                            const el = document.querySelector(selector);
                            if (el) {
                                clearInterval(interval);
                                resolve(el);
                            } else if (Date.now() - start > timeout) {
                                clearInterval(interval);
                                reject(new Error("Timeout: " + selector));
                            }
                        }, 200); // Увеличена частота проверок с 400ms до 200ms
                    });
                }

                async function goToAddressAndParse() {
                    try {
                        // Оптимизированное ожидание загрузки списка адресов
                        await waitForElement('div._dateTasks_36r29_18', 8000);
                        // Минимальная задержка для инициализации React
                        await new Promise(resolve => setTimeout(resolve, 200)); // Уменьшено с 300ms до 200ms

                        // Находим адрес в списке и кликаем по нему
                        const addressItems = document.querySelectorAll('div._taskItem_36r29_38');
                        let targetItem = null;

                        for (let item of addressItems) {
                            const titleElement = item.querySelector('div._taskTitle_36r29_45 span');
                            if (titleElement) {
                                const text = titleElement.innerText.trim();
                                // Используем более гибкий поиск (в обе стороны)
                                if (text.includes(TARGET_ADDRESS) || TARGET_ADDRESS.includes(text)) {
                                    targetItem = item;
                                    break;
                                }
                            }
                        }

                        if (!targetItem) {
                            window.Android.onMetersParsed("error:Адрес не найден: " + TARGET_ADDRESS);
                            return;
                        }

                        // React-совместимый клик (для правильной обработки React событий)
                        function reactClick(element) {
                            const mouseDown = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
                            const mouseUp = new MouseEvent('mouseup', { bubbles: true, cancelable: true });
                            const click = new MouseEvent('click', { bubbles: true, cancelable: true });
                            element.dispatchEvent(mouseDown);
                            element.dispatchEvent(mouseUp);
                            element.dispatchEvent(click);
                        }
                        
                        reactClick(targetItem);
                        
                        // Оптимизированное ожидание: ждем появления контейнера со счетчиками
                        // Вместо фиксированной задержки используем умное ожидание
                        const metersContainer = await waitForElement('div._tasksContainer_36r29_11', 10000);
                        
                        if (!metersContainer) {
                            window.Android.onMetersParsed("error:Контейнер счетчиков не найден");
                            return;
                        }

                        // Ждем появления хотя бы одного счетчика - более агрессивная проверка
                        let attempts = 0;
                        while (attempts < 15) { // Уменьшено с 20 до 15 попыток
                            const meterItems = metersContainer.querySelectorAll('div._taskItem_36r29_38');
                            if (meterItems.length > 0) {
                                break;
                            }
                            await new Promise(resolve => setTimeout(resolve, 80)); // Уменьшено с 100ms до 80ms
                            attempts++;
                        }

                        // Извлекаем данные о счетчиках
                        const meters = [];
                        const meterItems = metersContainer.querySelectorAll('div._taskItem_36r29_38');
                        
                        console.log('Found ' + meterItems.length + ' meter items');
                        
                        meterItems.forEach((item, index) => {
                            const titleElement = item.querySelector('div._taskTitle_36r29_45');
                            if (titleElement) {
                                const text = titleElement.innerText.trim();
                                console.log('Processing item ' + index + ': ' + text);
                                if (text && text.includes('№')) {
                                    // Парсим текст вида "Звенигово, Палантая, 9, 1\n№23301337 (1 зона)"
                                    const lines = text.split('\n');
                                    if (lines.length >= 2) {
                                        const apartment = lines[0].trim();
                                        const meterLine = lines[1].trim();
                                        // Извлекаем номер счетчика (убираем "(1 зона)" и "№")
                                        // Более точное извлечение номера счетчика с поддержкой букв
                                        let meterNumber = meterLine.replace(/№/, '').trim();
                                        
                                        // Убираем информацию в скобках, но сохраняем буквы в номере
                                        meterNumber = meterNumber.replace(/\s*\([^)]*\)/, '').trim();
                                        
                                        // Дополнительная очистка - убираем только лишние пробелы
                                        meterNumber = meterNumber.replace(/\s+/g, ' ').trim();
                                        
                                        // Определяем статус счетчика по значкам
                                        let status = 'NOT_CHECKED'; // По умолчанию не проверен
                                        
                                        // Ищем красный значок слева (не проверен)
                                        const redIcon = item.querySelector('svg[color="#E0B3B2"], svg[style*="rgb(224, 179, 178)"]');
                                        if (redIcon) {
                                            status = 'NOT_CHECKED';
                                            console.log('Found red icon - meter not checked');
                                        } else {
                                            // Ищем зеленые значки справа
                                            const greenIcons = item.querySelectorAll('svg[color="#95CAB4"], svg[style*="rgb(149, 202, 180)"]');
                                            if (greenIcons.length > 0) {
                                                // Проверяем, есть ли значок облачка (загружен)
                                                let hasCloudIcon = false;
                                                greenIcons.forEach(icon => {
                                                    const path = icon.querySelector('path[d*="M19.35 10.04"]'); // Путь облачка
                                                    if (path) {
                                                        hasCloudIcon = true;
                                                    }
                                                });
                                                
                                                if (hasCloudIcon) {
                                                    status = 'LOADED';
                                                    console.log('Found cloud icon - meter loaded');
                                                } else {
                                                    status = 'CHECKED_NOT_LOADED';
                                                    console.log('Found green icon - meter checked but not loaded');
                                                }
                                            }
                                        }
                                        
                                        console.log('Adding meter: ' + apartment + ' - ' + meterNumber + ' (status: ' + status + ')');
                                        console.log('Original meterLine: ' + meterLine);
                                        console.log('Processed meterNumber: ' + meterNumber);
                                        meters.push({
                                            apartment: apartment,
                                            meter: meterNumber,
                                            status: status
                                        });
                                    }
                                }
                            }
                        });
                        
                        console.log('Total meters found: ' + meters.length);

                        if (meters.length === 0) {
                            window.Android.onMetersParsed("error:Счетчики не найдены");
                        } else {
                            window.Android.onMetersParsed(JSON.stringify(meters));
                        }
                    } catch (err) {
                        window.Android.onMetersParsed("error:" + err.message);
                    }
                }

                goToAddressAndParse();
            })();
        """.trimIndent()
    }
}


