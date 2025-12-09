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
    
    private var bulkLoadingAddresses: List<Address> = emptyList()
    private var currentBulkAddressIndex = 0

    val addresses: Flow<List<Address>> = db.addressDao().getAll()

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
                
                Logger.logDatabase("Loading meters for ${bulkLoadingAddresses.size} addresses")
                
                // Загружаем первый адрес
                loadNextBulkAddress()
                
            } catch (e: Exception) {
                Logger.logError("Error in loadAllMeters", e)
                _error.value = "Ошибка загрузки всех счетчиков: ${e.message}"
                _bulkLoading.value = false
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
        Logger.logDatabase("Loading meters for address: ${address.fullAddress}")
        
        viewModelScope.launch(Dispatchers.Main) {
            val webViewManager = WebViewManager(context, this@MeterViewModel)
            currentWebViewManager = webViewManager
            
            val metersScript = getMetersParsingScript(address.fullAddress)
            pendingMetersScript = metersScript
            
            webViewManager.loadUrl("https://meter.printecs.com/")
        }
    }

    fun clearCache() {
        Logger.log("Starting cache clearing", "MAIN")
        _isLoggingOut.value = true // Блокируем кнопку входа
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Clear database
                Logger.logDatabase("Clearing all addresses")
                db.addressDao().deleteAll()
                Logger.logDatabase("Clearing all meters")
                db.meterDao().deleteAll()

                // Clear credentials
                Logger.log("Clearing saved credentials", "CREDENTIALS")
                credentialStore.clear()

                // Restore WebView to clean state using restore point
                Logger.logWebView("Restoring WebView to clean state")
                withContext(Dispatchers.Main) {
                    try {
                        // Сначала очищаем старый WebViewManager
                        currentWebViewManager?.let { webViewManager ->
                            try {
                                webViewManager.clearCacheAndReturnToLogin()
                            } catch (e: Exception) {
                                Logger.logError("Error clearing WebView cache", e)
                            }
                        }

                        // Принудительно пересоздаем WebViewManager для полного сброса
                        Logger.logWebView("Recreating WebViewManager for complete reset")
                        currentWebViewManager = null

                        // Восстанавливаем WebView к точке восстановления
                        webViewRestorePoint.restoreToPoint()

                        Logger.logWebView("WebView restored to clean state successfully")
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

                    // Prepare scripts to execute when page is ready
                    val loginScript = getLoginScript(login, password)
                    val addressScript = getAddressParsingScript()
                    pendingLoginScript = loginScript
                    pendingAddressScript = addressScript

                    Logger.logWebView("Loading URL: https://meter.printecs.com/")
                    webViewManager.loadUrl("https://meter.printecs.com/")

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
                
                // Если идет массовая загрузка, переходим к следующему адресу
                if (_bulkLoading.value) {
                    currentBulkAddressIndex++
                    loadNextBulkAddress()
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
                    loadNextBulkAddress()
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
                    const lastValue = input.value;
                    input.value = value;
                    const tracker = input._valueTracker;
                    if (tracker) tracker.setValue(lastValue);
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                    input.dispatchEvent(new Event('blur', { bubbles: true }));
                    await new Promise(resolve => setTimeout(resolve, 100));
                }

                    function findLoginInputs() {
                        // Use specific selectors for React app
                        return {
                            login: document.querySelector('input#login'),
                            password: document.querySelector('input#Пароль'),
                            submit: document.querySelector('button._button_1xwkq_1')
                        };
                    }

                async function login() {
                    try {
                        // Wait a bit for page to fully load
                        await new Promise(resolve => setTimeout(resolve, 1000));

                        // Detailed logging of page state
                        console.log("=== LOGIN DEBUG INFO ===");
                        console.log("Current URL:", window.location.href);
                        console.log("Page title:", document.title);
                        console.log("Document ready state:", document.readyState);
                        
                        // Check all input elements on page
                        const allInputs = document.querySelectorAll('input');
                        console.log("Total inputs found:", allInputs.length);
                        allInputs.forEach((input, index) => {
                            console.log('Input ' + index + ':', {
                                id: input.id,
                                name: input.name,
                                type: input.type,
                                placeholder: input.placeholder,
                                className: input.className
                            });
                        });
                        
                        // Check all buttons on page
                        const allButtons = document.querySelectorAll('button');
                        console.log("Total buttons found:", allButtons.length);
                        allButtons.forEach((button, index) => {
                            console.log('Button ' + index + ':', {
                                id: button.id,
                                className: button.className,
                                textContent: button.textContent?.trim(),
                                type: button.type
                            });
                        });

                        // Test specific selectors
                        const loginById = document.querySelector('input#login');
                        const passwordById = document.querySelector('input#Пароль');
                        const submitByClass = document.querySelector('button._button_1xwkq_1');
                        
                        console.log("Selector test results:");
                        console.log("input#login:", loginById ? "FOUND" : "NOT FOUND");
                        console.log("input#Пароль:", passwordById ? "FOUND" : "NOT FOUND");
                        console.log("button._button_1xwkq_1:", submitByClass ? "FOUND" : "NOT FOUND");
                        
                        // Try alternative selectors
                        const loginByType = document.querySelector('input[type="text"]');
                        const passwordByType = document.querySelector('input[type="password"]');
                        const submitByText = Array.from(document.querySelectorAll('button')).find(btn => 
                            btn.textContent && btn.textContent.toLowerCase().includes('вход')
                        );
                        
                        console.log("Alternative selectors:");
                        console.log("input[type='text']:", loginByType ? "FOUND" : "NOT FOUND");
                        console.log("input[type='password']:", passwordByType ? "FOUND" : "NOT FOUND");
                        console.log("button with 'вход' text:", submitByText ? "FOUND" : "NOT FOUND");

                        // Find login inputs using specific selectors
                        const { login: loginInput, password: passInput, submit: submitBtn } = findLoginInputs();

                        if (!loginInput || !passInput || !submitBtn) {
                            const errorMsg = 'Поля логина не найдены. Login: ' + !!loginInput + ', Password: ' + !!passInput + ', Submit: ' + !!submitBtn;
                            console.log("ERROR:", errorMsg);
                            window.Android.onLoginResult("error", errorMsg);
                            return;
                        }

                        console.log("All elements found, proceeding with login");
                        
                        // Use React input value setting for specific selectors
                        await setReactInputValue('input#login', "$login");
                        await setReactInputValue('input#Пароль', "$password");

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
                            const titleElement = item.querySelector('div._taskTitle_36r29_45 span');
                            if (titleElement) {
                                const addressText = titleElement.innerText.trim();
                                if (addressText && addressText.length > 5) {
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
                        }, 400);
                    });
                }

                async function goToAddressAndParse() {
                    try {
                        // Ждем загрузки страницы
                        await new Promise(resolve => setTimeout(resolve, 2000));

                        // Находим адрес в списке и кликаем по нему
                        const addressItems = document.querySelectorAll('div._taskItem_36r29_38');
                        let targetItem = null;

                        for (let item of addressItems) {
                            const titleElement = item.querySelector('div._taskTitle_36r29_45 span');
                            if (titleElement) {
                                const text = titleElement.innerText.trim();
                                if (text.includes(TARGET_ADDRESS)) {
                                    targetItem = item;
                                    break;
                                }
                            }
                        }

                        if (!targetItem) {
                            window.Android.onMetersParsed("error:Адрес не найден: " + TARGET_ADDRESS);
                            return;
                        }

                        // Кликаем по адресу
                        targetItem.click();
                        await new Promise(resolve => setTimeout(resolve, 3000));

                        // Ждем появления контейнера со счетчиками
                        const metersContainer = await waitForElement('div._tasksContainer_36r29_11', 15000);
                        
                        if (!metersContainer) {
                            window.Android.onMetersParsed("error:Контейнер счетчиков не найден");
                            return;
                        }

                        // Ждем еще немного для полной загрузки
                        await new Promise(resolve => setTimeout(resolve, 1000));

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


