package com.metersync.web

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.metersync.utils.Logger

class WebViewManager(context: Context, private val listener: Listener) {
    private var isPaused = false
    private var isSuspended = false
    
    private val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        
        // Оптимизации для снижения энергопотребления
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = true
        settings.blockNetworkImage = false // Оставляем изображения для корректной работы
        settings.blockNetworkLoads = false
        
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Logger.logWebView("Page finished loading: $url")
                super.onPageFinished(view, url)
                
                // Inject console.log interceptor for debugging только если не приостановлен
                if (!isSuspended) {
                    view?.evaluateJavascript("""
                        (function() {
                            const originalLog = console.log;
                            console.log = function(...args) {
                                originalLog.apply(console, args);
                                window.Android?.onConsoleLog?.('LOG: ' + args.join(' '));
                            };
                        })();
                    """, null)
                    
                    // Notify that page is ready for JavaScript execution
                    listener.onPageReady()
                }
            }
            
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                Logger.logError("WebView error: $description", null)
                super.onReceivedError(view, errorCode, description, failingUrl)
            }
        }
        
        addJavascriptInterface(WebAppInterface(), "Android")
        Logger.logWebView("WebView created and configured")
    }

    interface Listener {
        fun onPageReady()
        fun onLoginResult(status: String, message: String)
        fun onAddressesParsed(json: String)
        fun onMetersParsed(json: String)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onLoginResult(status: String, message: String) {
            Logger.logWebView("JavaScript callback: onLoginResult($status, $message)")
            try {
                listener.onLoginResult(status, message)
            } catch (e: Exception) {
                Logger.logError("Error in onLoginResult callback", e)
            }
        }
        
        @JavascriptInterface
        fun onConsoleLog(message: String) {
            Logger.logWebView("JS Console: $message")
        }

        @JavascriptInterface
        fun onAddressesParsed(json: String) {
            Logger.logWebView("JavaScript callback: onAddressesParsed(${json.take(100)}...)")
            try {
                listener.onAddressesParsed(json)
            } catch (e: Exception) {
                Logger.logError("Error in onAddressesParsed callback", e)
            }
        }

        @JavascriptInterface
        fun onMetersParsed(json: String) {
            Logger.logWebView("JavaScript callback: onMetersParsed(${json.take(100)}...)")
            try {
                listener.onMetersParsed(json)
            } catch (e: Exception) {
                Logger.logError("Error in onMetersParsed callback", e)
            }
        }
    }

    fun getWebView(): WebView = webView
    
    /**
     * Проверяет, авторизован ли пользователь (находится ли на странице со списком адресов)
     */
    fun checkIfAuthorized(callback: (Boolean) -> Unit) {
        Logger.logWebView("Checking if user is authorized")
        webView.evaluateJavascript("""
            (function() {
                // Проверяем наличие контейнера с адресами
                const addressContainer = document.querySelector('div._dateTasks_36r29_18');
                // Проверяем отсутствие полей входа
                const loginInput = document.querySelector('input#login') || document.querySelector('input[name="login"]');
                return !loginInput && !!addressContainer;
            })();
        """.trimIndent()) { result ->
            val isAuthorized = result == "true"
            Logger.logWebView("Authorization check result: $isAuthorized")
            callback(isAuthorized)
        }
    }
    
    /**
     * Возвращается назад к списку адресов (использует history.back())
     */
    fun navigateBackToAddressList(callback: (() -> Unit)? = null) {
        Logger.logWebView("Navigating back to address list")
        webView.evaluateJavascript("""
            (function() {
                if (window.history.length > 1) {
                    window.history.back();
                    return 'navigating_back';
                } else {
                    // Если нет истории, перезагружаем страницу
                    window.location.href = 'https://meter.printecs.com/';
                    return 'reloading';
                }
            })();
        """.trimIndent()) { result ->
            Logger.logWebView("Navigation result: $result")
            // Даем время на навигацию (уменьшено для ускорения)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                callback?.invoke()
            }, 800) // Уменьшено с 1500ms до 800ms
        }
    }
    
    /**
     * Приостанавливает WebView для снижения энергопотребления.
     * Останавливает загрузку, отключает JavaScript и загружает пустую страницу.
     * Используется когда WebView не нужен (пользователь просто просматривает данные).
     */
    fun suspend() {
        if (isSuspended) {
            Logger.logWebView("WebView already suspended")
            return
        }
        
        Logger.logWebView("Suspending WebView to reduce power consumption")
        try {
            // Останавливаем загрузку
            webView.stopLoading()
            
            // Отключаем JavaScript для экономии энергии
            webView.settings.javaScriptEnabled = false
            
            // Загружаем пустую страницу для освобождения ресурсов
            webView.loadUrl("about:blank")
            
            // Приостанавливаем рендеринг
            webView.onPause()
            
            isSuspended = true
            Logger.logWebView("WebView suspended successfully")
        } catch (e: Exception) {
            Logger.logError("Error suspending WebView", e)
        }
    }
    
    /**
     * Возобновляет работу WebView после приостановки.
     * Включает JavaScript и возобновляет рендеринг.
     */
    fun resume() {
        if (!isSuspended) {
            Logger.logWebView("WebView is not suspended, no need to resume")
            return
        }
        
        Logger.logWebView("Resuming WebView")
        try {
            // Включаем JavaScript обратно
            webView.settings.javaScriptEnabled = true
            
            // Возобновляем рендеринг
            webView.onResume()
            
            isSuspended = false
            Logger.logWebView("WebView resumed successfully")
        } catch (e: Exception) {
            Logger.logError("Error resuming WebView", e)
        }
    }
    
    /**
     * Проверяет, приостановлен ли WebView
     */
    fun isSuspended(): Boolean = isSuspended
    
    /**
     * Полностью уничтожает WebView и освобождает все ресурсы
     */
    fun destroy() {
        Logger.logWebView("Destroying WebView completely")
        try {
            // Останавливаем загрузку
            webView.stopLoading()
            
            // Удаляем все JavaScript интерфейсы
            webView.removeJavascriptInterface("Android")
            
            // Очищаем WebViewClient
            webView.webViewClient = object : WebViewClient() {}
            
            // Очищаем все данные
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            webView.clearSslPreferences()
            
            // Загружаем пустую страницу
            webView.loadUrl("about:blank")
            
            // Уничтожаем WebView
            webView.destroy()
            
            isSuspended = false
            isPaused = false
            Logger.logWebView("WebView destroyed successfully")
        } catch (e: Exception) {
            Logger.logError("Error destroying WebView", e)
        }
    }

    fun loadUrl(url: String) {
        Logger.logWebView("Loading URL: $url")
        try {
            // Если WebView приостановлен, возобновляем его перед загрузкой
            if (isSuspended) {
                resume()
            }
            webView.loadUrl(url)
        } catch (e: Exception) {
            Logger.logError("Error loading URL: $url", e)
        }
    }

        fun evaluateJs(script: String, callback: ((String) -> Unit)? = null) {
            Logger.logWebView("Evaluating JavaScript: ${script.take(200)}...")
            try {
                // Если WebView приостановлен, возобновляем его перед выполнением JS
                if (isSuspended) {
                    resume()
                }
                webView.evaluateJavascript(script) { result ->
                    Logger.logWebView("JavaScript result: $result")
                    callback?.invoke(result)
                }
            } catch (e: Exception) {
                Logger.logError("Error evaluating JavaScript", e)
            }
        }

        fun clearCache() {
            Logger.logWebView("Clearing WebView cache and cookies")
            try {
                // Очистка кэша и истории
                webView.clearCache(true)
                webView.clearHistory()
                webView.clearFormData()
                webView.clearSslPreferences()
                
                // Очистка куки через CookieManager
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                
                // Очистка localStorage и sessionStorage через JavaScript
                webView.evaluateJavascript("""
                    try {
                        localStorage.clear();
                        sessionStorage.clear();
                        console.log('LocalStorage and SessionStorage cleared');
                    } catch(e) {
                        console.log('Error clearing storage:', e);
                    }
                """.trimIndent(), null)
                
                // Дополнительная очистка WebView состояния
                webView.loadUrl("about:blank")
                
                // Принудительная очистка всех данных WebView
                webView.clearMatches()
                webView.clearSslPreferences()
                
                Logger.logWebView("WebView cache, cookies, and storage cleared successfully")
            } catch (e: Exception) {
                Logger.logError("Error clearing WebView cache", e)
            }
        }
        
        fun clearCacheAndReturnToLogin() {
            Logger.logWebView("Starting UI-based logout sequence")
            try {
                // Сначала загружаем страницу профиля
                Logger.logWebView("Loading profile page: https://meter.printecs.com/profile")
                loadUrl("https://meter.printecs.com/profile")
                
                // Ждем загрузки страницы, затем выполняем скрипт выхода
                // Используем evaluateJs() вместо прямого вызова, чтобы автоматически возобновить WebView если он приостановлен
                // Даем время на загрузку страницы профиля перед выполнением скрипта
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    evaluateJs("""
                        (async function() {
                            try {
                                console.log('=== Starting UI logout sequence ===');
                                
                                // Функция ожидания элемента с более надежной проверкой
                                function waitForElement(selector, timeout = 10000) {
                                    return new Promise((resolve, reject) => {
                                        const start = Date.now();
                                        const interval = setInterval(() => {
                                            const el = document.querySelector(selector);
                                            if (el && el.offsetParent !== null) { // Проверяем, что элемент видим
                                                clearInterval(interval);
                                                resolve(el);
                                            } else if (Date.now() - start > timeout) {
                                                clearInterval(interval);
                                                reject(new Error('Timeout waiting for: ' + selector));
                                            }
                                        }, 200);
                                    });
                                }
                                
                                // React-совместимый клик (как в других скриптах)
                                function reactClick(element) {
                                    const mouseDown = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
                                    const mouseUp = new MouseEvent('mouseup', { bubbles: true, cancelable: true });
                                    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
                                    element.dispatchEvent(mouseDown);
                                    element.dispatchEvent(mouseUp);
                                    element.dispatchEvent(click);
                                }
                                
                                // Функция прокрутки элемента в видимую область
                                function scrollIntoView(element) {
                                    element.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                    return new Promise(resolve => setTimeout(resolve, 500));
                                }
                                
                                // Проверяем, находимся ли мы на странице входа
                                const isLoginPage = document.querySelector('input#login') || 
                                                    document.querySelector('input[name="login"]') ||
                                                    document.querySelector('input[type="password"]');
                                
                                if (isLoginPage) {
                                    console.log('Already on login page, skipping logout');
                                    localStorage.clear();
                                    sessionStorage.clear();
                                    return 'already_logged_out';
                                }
                                
                                // КРИТИЧЕСКИ ВАЖНО: Ждем полной загрузки страницы профиля перед поиском элементов
                                console.log('Waiting for profile page to be fully loaded...');
                                if (document.readyState !== 'complete') {
                                    await new Promise(resolve => {
                                        if (document.readyState === 'complete') {
                                            resolve();
                                        } else {
                                            window.addEventListener('load', resolve, { once: true });
                                            // Таймаут на случай, если событие load не сработает
                                            setTimeout(resolve, 3000);
                                        }
                                    });
                                }
                                
                                // Дополнительная задержка для полной инициализации React компонентов (минимум 2 секунды)
                                console.log('Waiting 2 seconds for React components to initialize...');
                                await new Promise(resolve => setTimeout(resolve, 2000));
                                console.log('Page should be fully loaded now');
                                
                                // Шаг 1: Находим и нажимаем кнопку "Выход" (div._listLink_cwmac_15)
                                console.log('Step 1: Looking for Logout button (Выход)...');
                                try {
                                    const logoutLink = await waitForElement('div._listLink_cwmac_15', 10000);
                                    
                                    // Проверяем, что это действительно "Выход"
                                    if (logoutLink.textContent.trim() === 'Выход') {
                                        console.log('Logout button found, scrolling into view...');
                                        await scrollIntoView(logoutLink);
                                        
                                        // Дополнительное ожидание минимум 2 секунды перед кликом
                                        console.log('Waiting additional 2 seconds before clicking...');
                                        await new Promise(resolve => setTimeout(resolve, 2000));
                                        
                                        console.log('Clicking Logout button using React-compatible click...');
                                        reactClick(logoutLink);
                                        
                                        // Ждем открытия диалога подтверждения
                                        await new Promise(resolve => setTimeout(resolve, 2000));
                                        console.log('Logout confirmation dialog should be open now');
                                    } else {
                                        throw new Error('Logout button text incorrect: ' + logoutLink.textContent);
                                    }
                                } catch (logoutError) {
                                    console.log('Error finding Logout button:', logoutError.message);
                                    // Пробуем альтернативный селектор
                                    const altLogoutLink = Array.from(document.querySelectorAll('div[class*="_listLink"]'))
                                        .find(el => el.textContent.trim() === 'Выход');
                                    if (altLogoutLink) {
                                        await scrollIntoView(altLogoutLink);
                                        await new Promise(resolve => setTimeout(resolve, 2000));
                                        reactClick(altLogoutLink);
                                        await new Promise(resolve => setTimeout(resolve, 2000));
                                    } else {
                                        throw logoutError;
                                    }
                                }
                                
                                // Шаг 2: Находим и нажимаем кнопку "Выйти" (div._label_alwez_26)
                                console.log('Step 2: Looking for Exit button (Выйти)...');
                                try {
                                    // Ищем элемент с классом _label_alwez_26, который содержит текст "Выйти"
                                    const exitLabel = await waitForElement('div._label_alwez_26', 10000);
                                    
                                    // Проверяем, что это действительно "Выйти"
                                    if (exitLabel.textContent.trim() === 'Выйти') {
                                        console.log('Exit button found, scrolling into view...');
                                        await scrollIntoView(exitLabel);
                                        
                                        // Дополнительное ожидание минимум 2 секунды перед кликом
                                        console.log('Waiting additional 2 seconds before clicking...');
                                        await new Promise(resolve => setTimeout(resolve, 2000));
                                        
                                        // Находим родительскую кнопку для клика
                                        const exitButton = exitLabel.closest('button') || exitLabel.closest('div[class*="_button"]');
                                        if (exitButton) {
                                            console.log('Clicking Exit button using React-compatible click...');
                                            reactClick(exitButton);
                                        } else {
                                            // Если не нашли родительскую кнопку, кликаем на сам label
                                            reactClick(exitLabel);
                                        }
                                        
                                        // Ждем редиректа на страницу входа
                                        console.log('Waiting for redirect to login page...');
                                        await new Promise(resolve => setTimeout(resolve, 3000));
                                        
                                        // Проверяем, что мы на странице входа
                                        const checkLoginPage = document.querySelector('input#login') || 
                                                              document.querySelector('input[name="login"]') ||
                                                              document.querySelector('input[type="password"]');
                                        
                                        if (checkLoginPage) {
                                            console.log('Successfully redirected to login page');
                                        } else {
                                            console.log('Warning: May not be on login page yet, waiting additional time...');
                                            // Дополнительное ожидание и проверка URL
                                            await new Promise(resolve => setTimeout(resolve, 2000));
                                            const currentUrl = window.location.href;
                                            console.log('Current URL:', currentUrl);
                                            if (currentUrl.includes('meter.printecs.com') && !currentUrl.includes('profile')) {
                                                console.log('URL indicates we are on login page');
                                            }
                                        }
                                    } else {
                                        throw new Error('Exit button text incorrect: ' + exitLabel.textContent);
                                    }
                                } catch (exitError) {
                                    console.log('Error finding Exit button:', exitError.message);
                                    // Пробуем альтернативный селектор
                                    const altExitLabel = Array.from(document.querySelectorAll('div[class*="_label"]'))
                                        .find(el => el.textContent.trim() === 'Выйти');
                                    if (altExitLabel) {
                                        await scrollIntoView(altExitLabel);
                                        await new Promise(resolve => setTimeout(resolve, 2000));
                                        const exitButton = altExitLabel.closest('button') || altExitLabel.closest('div[class*="_button"]');
                                        if (exitButton) {
                                            reactClick(exitButton);
                                        } else {
                                            reactClick(altExitLabel);
                                        }
                                        await new Promise(resolve => setTimeout(resolve, 3000));
                                    } else {
                                        throw exitError;
                                    }
                                }
                                
                                console.log('=== UI logout sequence completed successfully ===');
                                
                                // Очищаем все данные после выхода
                                localStorage.clear();
                                sessionStorage.clear();
                                console.log('Storage cleared');
                                
                                return 'logout_success';
                                
                            } catch (error) {
                                console.log('Error in UI logout sequence:', error.message);
                                console.log('Stack:', error.stack);
                                
                                // Очистка localStorage и sessionStorage в любом случае
                                try {
                                    localStorage.clear();
                                    sessionStorage.clear();
                                    console.log('Storage cleared as fallback');
                                } catch (e) {
                                    console.log('Error clearing storage:', e);
                                }
                                
                                return 'logout_error: ' + error.message;
                            }
                        })();
                    """.trimIndent()) { result ->
                        Logger.logWebView("Logout sequence result: $result")
                    }
                }, 3000) // Даем 3 секунды на загрузку страницы профиля
                
                Logger.logWebView("UI logout sequence initiated")
            } catch (e: Exception) {
                Logger.logError("Error initiating UI logout sequence", e)
            }
        }
        
        /**
         * Принудительно сбрасывает WebView к странице входа
         * Завершает текущую сессию, очищает все данные и загружает страницу входа заново
         */
        fun forceReloadToLogin() {
            Logger.logWebView("Force reloading to login page - terminating session")
            try {
                // Шаг 1: Пытаемся выйти через UI (если пользователь залогинен)
                // Это завершит сессию на сервере
                webView.evaluateJavascript("""
                    (async function() {
                        try {
                            console.log('Attempting to logout via UI...');
                            
                            // Функция ожидания элемента
                            function waitForElement(selector, timeout = 3000) {
                                return new Promise((resolve, reject) => {
                                    const start = Date.now();
                                    const interval = setInterval(() => {
                                        const el = document.querySelector(selector);
                                        if (el) {
                                            clearInterval(interval);
                                            resolve(el);
                                        } else if (Date.now() - start > timeout) {
                                            clearInterval(interval);
                                            reject(new Error('Timeout: ' + selector));
                                        }
                                    }, 100);
                                });
                            }
                            
                            // Пытаемся найти и нажать кнопку выхода
                            try {
                                // Ищем кнопку профиля
                                const profileBtn = await waitForElement('div._navItem_snyxi_40:nth-child(3)', 2000);
                                if (profileBtn) {
                                    profileBtn.click();
                                    await new Promise(resolve => setTimeout(resolve, 500));
                                    
                                    // Ищем ссылку выхода
                                    const logoutLink = await waitForElement('div._listLink_14tur_15', 2000);
                                    if (logoutLink) {
                                        logoutLink.click();
                                        await new Promise(resolve => setTimeout(resolve, 500));
                                        
                                        // Ищем кнопку подтверждения выхода
                                        const exitBtn = await waitForElement('button._full_1xwkq_187:nth-child(1)', 2000);
                                        if (exitBtn) {
                                            exitBtn.click();
                                            await new Promise(resolve => setTimeout(resolve, 1000));
                                            console.log('Logout via UI completed');
                                        }
                                    }
                                }
                            } catch (e) {
                                console.log('UI logout not available or already logged out:', e.message);
                            }
                            
                            // Очищаем все данные хранилища
                            try {
                                localStorage.clear();
                                sessionStorage.clear();
                                console.log('Storage cleared');
                            } catch (e) {
                                console.log('Error clearing storage:', e);
                            }
                            
                            // Очищаем куки через document.cookie
                            try {
                                if (document.cookie) {
                                    document.cookie.split(";").forEach(function(c) { 
                                        document.cookie = c.replace(/^ +/, "").replace(/=.*/, "=;expires=" + new Date().toUTCString() + ";path=/"); 
                                    });
                                }
                                console.log('Cookies cleared');
                            } catch (e) {
                                console.log('Error clearing cookies:', e);
                            }
                            
                        } catch (error) {
                            console.log('Error in logout sequence:', error.message);
                        }
                    })();
                """.trimIndent(), null)
                
                // Шаг 2: Очищаем кэш и данные WebView на уровне Android
                webView.clearCache(true)
                webView.clearHistory()
                webView.clearFormData()
                webView.clearSslPreferences()
                
                // Очистка куки через CookieManager (глобально)
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
                
                // Шаг 3: Загружаем пустую страницу для полной очистки состояния
                Logger.logWebView("Loading blank page to clear state")
                webView.loadUrl("about:blank")
                
                // Шаг 4: Загружаем страницу входа с параметром для принудительного сброса сессии
                // Используем post для выполнения после текущего цикла событий
                webView.post {
                    Logger.logWebView("Loading login page: https://meter.printecs.com/")
                    // Добавляем timestamp для предотвращения кэширования и создания новой сессии
                    val loginUrl = "https://meter.printecs.com/?_t=${System.currentTimeMillis()}"
                    webView.loadUrl(loginUrl)
                }
                
                Logger.logWebView("Force reload to login initiated")
            } catch (e: Exception) {
                Logger.logError("Error force reloading to login", e)
            }
        }
}


