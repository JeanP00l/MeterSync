(function() {
    const TARGET_ADDRESS = "__TARGET_ADDRESS__";

    async function waitForElement(selector, timeout = 15000) {
        return new Promise((resolve, reject) => {
            const start = Date.now();
            const interval = setInterval(() => {
                const el = document.querySelector(selector);
                if (el) {
                    clearInterval(interval);
                    resolve(el);
                } else if (Date.now() - start > timeout) {
                    clearInterval(interval);
                    reject("Timeout: " + selector);
                }
            }, 400);
        });
    }

    async function goToAddressAndParse() {
        try {
            await waitForElement('div._dateTasks_36r29_18');

            const items = document.querySelectorAll('div._taskItem_36r29_38');
            let targetItem = null;

            for (let item of items) {
                const span = item.querySelector('div._taskTitle_36r29_45 > span');
                if (span && span.innerText.trim() === TARGET_ADDRESS) {
                    targetItem = item;
                    break;
                }
            }

            if (!targetItem) throw new Error("Адрес не найден: " + TARGET_ADDRESS);

            targetItem.click();

            await waitForElement('div._taskDate_36r29_23', 10000);

            await new Promise(r => setTimeout(r, 1200));

            const meterItems = document.querySelectorAll('div._taskItem_36r29_38');
            const meters = [];

            meterItems.forEach(item => {
                const titleDiv = item.querySelector('div._taskTitle_36r29_45');
                if (titleDiv) {
                    const text = titleDiv.innerText.trim();
                    if (text && text.includes('№')) {
                        const lines = text.split('\n');
                        const apartment = lines[0].trim();
                        const meter = lines[1].trim();
                        meters.push({ apartment, meter });
                    }
                }
            });

            if (meters.length === 0) throw new Error("Счетчики не найдены");

            window.Android.onMetersParsed(JSON.stringify(meters));
        } catch (err) {
            window.Android.onMetersParsed("error:" + err.message);
        }
    }

    goToAddressAndParse();
})();


