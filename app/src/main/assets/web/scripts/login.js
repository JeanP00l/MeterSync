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

    async function login() {
        try {
            await waitForElement('div._loginForm_16eqr_15');

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
                
                // Также проверяем по name атрибутам
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
                window.Android.onLoginResult("error", "Поля не найдены");
                return;
            }
            
            console.log('Input elements found! Waiting additional 1 second for React to fully initialize...');
            // Дополнительное ожидание 1 секунду после появления полей
            await new Promise(resolve => setTimeout(resolve, 1000));

            const submitBtn = document.querySelector('button[type="submit"]');
            if (!submitBtn) {
                window.Android.onLoginResult("error", "Кнопка отправки не найдена");
                return;
            }

            // Кликаем на контейнеры и input элементы для активации React полей
            console.log("Activating login field...");
            const loginContainer = loginInput.closest('div._inputContainer_ydbik_41');
            if (loginContainer) {
                loginContainer.click();
                await new Promise(resolve => setTimeout(resolve, 100));
            }
            
            // Кликаем на input логина несколько раз
            for (let i = 0; i < 3; i++) {
                loginInput.click();
                await new Promise(resolve => setTimeout(resolve, 50));
            }
            loginInput.focus();
            await new Promise(resolve => setTimeout(resolve, 100));
            
            console.log("Activating password field...");
            const passwordContainer = passInput.closest('div._inputContainer_ydbik_41');
            if (passwordContainer) {
                passwordContainer.click();
                await new Promise(resolve => setTimeout(resolve, 100));
            }
            
            // Кликаем на input пароля несколько раз
            for (let i = 0; i < 3; i++) {
                passInput.click();
                await new Promise(resolve => setTimeout(resolve, 50));
            }
            passInput.focus();
            await new Promise(resolve => setTimeout(resolve, 100));

            loginInput.value = "__LOGIN__";
            passInput.value = "__PASSWORD__";

            [loginInput, passInput].forEach(input => {
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
            });

            submitBtn.click();

            await waitForElement('div._dateTasks_36r29_18');

            window.Android.onLoginResult("success", "");
        } catch (err) {
            window.Android.onLoginResult("error", err.message);
        }
    }

    login();
})();



