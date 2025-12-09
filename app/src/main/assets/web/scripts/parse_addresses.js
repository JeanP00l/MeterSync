(function() {
    async function parseAddresses() {
        try {
            await new Promise(resolve => setTimeout(resolve, 1000));

            const container = document.querySelector('div._dateTasks_36r29_18');
            if (!container) throw new Error("Контейнер адресов не найден");

            const items = container.querySelectorAll('div._taskItem_36r29_38');
            const addresses = [];

            items.forEach(item => {
                const span = item.querySelector('div._taskTitle_36r29_45 > span');
                if (span) {
                    const text = span.innerText.trim();
                    if (text && text !== "Без даты") {
                        addresses.push(text);
                    }
                }
            });

            if (addresses.length === 0) throw new Error("Адреса не найдены");

            window.Android.onAddressesParsed(JSON.stringify(addresses));
        } catch (err) {
            window.Android.onAddressesParsed("error:" + err.message);
        }
    }

    parseAddresses();
})();


