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
                            console.log('Starting UI logout sequence...');
                            
                            // Функция ожидания элемента
                            function waitForElement(selector, timeout = 5000) {
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
                            
                            // Шаг 1: Нажимаем кнопку "Профиль"
                            console.log('Step 1: Looking for Profile button...');
                            const profileBtn = await waitForElement('div._navItem_snyxi_40:nth-child(3)', 10000);
                            console.log('Profile button found, clicking...');
                            profileBtn.click();
                            
                            // Небольшая задержка
                            await new Promise(resolve => setTimeout(resolve, 1000));
                            
                            // Шаг 2: Нажимаем кнопку "Выход"
                            console.log('Step 2: Looking for Logout link...');
                            const logoutLink = await waitForElement('div._listLink_14tur_15', 10000);
                            console.log('Logout link found, clicking...');
                            logoutLink.click();
                            
                            // Небольшая задержка
                            await new Promise(resolve => setTimeout(resolve, 1000));
                            
                            // Шаг 3: Нажимаем кнопку "Выйти"
                            console.log('Step 3: Looking for Exit button...');
                            const exitBtn = await waitForElement('button._full_1xwkq_187:nth-child(1)', 10000);
                            console.log('Exit button found, clicking...');
                            exitBtn.click();
                            
                            console.log('UI logout sequence completed successfully');
                            
                        } catch (error) {
                            console.log('Error in UI logout sequence:', error.message);
                            // Если UI выход не сработал, делаем принудительную очистку
                            console.log('Falling back to cache clearing...');
                            
                            // Очистка localStorage и sessionStorage
                            try {
                                localStorage.clear();
                                sessionStorage.clear();
                                console.log('Storage cleared');
                            } catch (e) {
                                console.log('Error clearing storage:', e);
                            }
                        }
                    })();
                """.trimIndent(), null)
                
                Logger.logWebView("UI logout sequence initiated")
            } catch (e: Exception) {
                Logger.logError("Error initiating UI logout sequence", e)
            }
        }
}


