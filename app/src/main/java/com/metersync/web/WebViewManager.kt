package com.metersync.web

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.metersync.utils.Logger

class WebViewManager(context: Context, private val listener: Listener) {
    private val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Logger.logWebView("Page finished loading: $url")
                super.onPageFinished(view, url)
                
                // Inject console.log interceptor for debugging
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
            
            Logger.logWebView("WebView destroyed successfully")
        } catch (e: Exception) {
            Logger.logError("Error destroying WebView", e)
        }
    }

    fun loadUrl(url: String) {
        Logger.logWebView("Loading URL: $url")
        try {
            webView.loadUrl(url)
        } catch (e: Exception) {
            Logger.logError("Error loading URL: $url", e)
        }
    }

        fun evaluateJs(script: String, callback: ((String) -> Unit)? = null) {
            Logger.logWebView("Evaluating JavaScript: ${script.take(200)}...")
            try {
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
                // Выполняем последовательность выхода через UI кнопки
                webView.evaluateJavascript("""
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
                                    }, 100);
                                });
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
                            
                            // Шаг 1: Находим и нажимаем кнопку "Профиль"
                            console.log('Step 1: Looking for Profile button...');
                            try {
                                // Ищем кнопку "Профиль" по классу и тексту label
                                const profileBtn = await waitForElement('div._navItem_fugwz_40', 10000);
                                
                                // Проверяем, что это действительно кнопка "Профиль" по тексту
                                const label = profileBtn.querySelector('label');
                                if (label && label.textContent.trim() === 'Профиль') {
                                    console.log('Profile button found, scrolling into view...');
                                    await scrollIntoView(profileBtn);
                                    
                                    console.log('Clicking Profile button...');
                                    profileBtn.click();
                                    
                                    // Ждем открытия меню профиля
                                    await new Promise(resolve => setTimeout(resolve, 2000));
                                    console.log('Profile menu should be open now');
                                } else {
                                    throw new Error('Profile button label not found or incorrect');
                                }
                            } catch (profileError) {
                                console.log('Error finding Profile button:', profileError.message);
                                // Пробуем альтернативный селектор
                                const altProfileBtn = document.querySelector('div[class*="_navItem"] label');
                                if (altProfileBtn && altProfileBtn.textContent.includes('Профиль')) {
                                    const parent = altProfileBtn.closest('div[class*="_navItem"]');
                                    if (parent) {
                                        await scrollIntoView(parent);
                                        parent.click();
                                        await new Promise(resolve => setTimeout(resolve, 2000));
                                    }
                                } else {
                                    throw profileError;
                                }
                            }
                            
                            // Шаг 2: Находим и нажимаем кнопку "Выход"
                            console.log('Step 2: Looking for Logout link...');
                            try {
                                const logoutLink = await waitForElement('div._listLink_cwmac_15', 10000);
                                
                                // Проверяем, что это действительно "Выход"
                                if (logoutLink.textContent.trim() === 'Выход') {
                                    console.log('Logout link found, scrolling into view...');
                                    await scrollIntoView(logoutLink);
                                    
                                    console.log('Clicking Logout link...');
                                    logoutLink.click();
                                    
                                    // Ждем открытия диалога подтверждения
                                    await new Promise(resolve => setTimeout(resolve, 2000));
                                    console.log('Logout confirmation dialog should be open now');
                                } else {
                                    throw new Error('Logout link text incorrect: ' + logoutLink.textContent);
                                }
                            } catch (logoutError) {
                                console.log('Error finding Logout link:', logoutError.message);
                                // Пробуем альтернативный селектор
                                const altLogoutLink = Array.from(document.querySelectorAll('div[class*="_listLink"]'))
                                    .find(el => el.textContent.trim() === 'Выход');
                                if (altLogoutLink) {
                                    await scrollIntoView(altLogoutLink);
                                    altLogoutLink.click();
                                    await new Promise(resolve => setTimeout(resolve, 2000));
                                } else {
                                    throw logoutError;
                                }
                            }
                            
                            // Шаг 3: Находим и нажимаем кнопку "Выйти"
                            console.log('Step 3: Looking for Exit button...');
                            try {
                                const exitBtn = await waitForElement('button._button_alwez_1._full_alwez_187', 10000);
                                
                                // Проверяем, что это действительно кнопка "Выйти"
                                const label = exitBtn.querySelector('div._label_alwez_26');
                                if (label && label.textContent.trim() === 'Выйти') {
                                    console.log('Exit button found, scrolling into view...');
                                    await scrollIntoView(exitBtn);
                                    
                                    console.log('Clicking Exit button...');
                                    exitBtn.click();
                                    
                                    // Ждем редиректа на страницу входа
                                    await new Promise(resolve => setTimeout(resolve, 3000));
                                    console.log('Should be redirected to login page now');
                                    
                                    // Проверяем, что мы на странице входа
                                    const checkLoginPage = document.querySelector('input#login') || 
                                                          document.querySelector('input[name="login"]') ||
                                                          document.querySelector('input[type="password"]');
                                    
                                    if (checkLoginPage) {
                                        console.log('Successfully redirected to login page');
                                    } else {
                                        console.log('Warning: May not be on login page yet');
                                    }
                                } else {
                                    throw new Error('Exit button label not found or incorrect');
                                }
                            } catch (exitError) {
                                console.log('Error finding Exit button:', exitError.message);
                                // Пробуем альтернативный селектор
                                const altExitBtn = Array.from(document.querySelectorAll('button[class*="_button"][class*="_full"]'))
                                    .find(btn => {
                                        const label = btn.querySelector('div[class*="_label"]');
                                        return label && label.textContent.trim() === 'Выйти';
                                    });
                                if (altExitBtn) {
                                    await scrollIntoView(altExitBtn);
                                    altExitBtn.click();
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


