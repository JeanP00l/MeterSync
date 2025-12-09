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

            const loginInput = document.querySelector('input#login');
            const passInput = document.querySelector('input#Пароль');
            const submitBtn = document.querySelector('button[type="submit"]');

            if (!loginInput || !passInput || !submitBtn) {
                window.Android.onLoginResult("error", "Поля не найдены");
                return;
            }

            loginInput.value = "__LOGIN__";
            passInput.value = "__PASSWORD__";

            [loginInput, passInput].forEach(input => {
                input.dispatchEvent(new Event('input', { bubbles: true }));
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


