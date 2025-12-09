(function() {
    // Функция ожидания элемента с поддержкой React
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
            // Ждем полной загрузки React приложения
            await waitForElement('div._dateTasks_36r29_18', 15000);
            
            // Дополнительное ожидание для завершения рендеринга React
            await new Promise(resolve => setTimeout(resolve, 1000));

            const container = document.querySelector('div._dateTasks_36r29_18');
            if (!container) {
                throw new Error("Контейнер адресов не найден");
            }

            const items = container.querySelectorAll('div._taskItem_36r29_38');
            const addresses = [];

            items.forEach(item => {
                // Пробуем разные селекторы для надежности
                const span = item.querySelector('div._taskTitle_36r29_45 > span') ||
                           item.querySelector('div._taskTitle_36r29_45 span') ||
                           item.querySelector('span');
                           
                if (span) {
                    const text = span.innerText.trim();
                    // Фильтруем валидные адреса
                    if (text && 
                        text !== "Без даты" && 
                        text.length > 5 &&
                        !text.includes('Загрузка')) {
                        addresses.push(text);
                    }
                }
            });

            // Если не нашли по основным селекторам, пробуем альтернативный метод
            if (addresses.length === 0) {
                // Ищем все span внутри контейнера
                const allSpans = container.querySelectorAll('span');
                allSpans.forEach(span => {
                    const text = span.innerText.trim();
                    if (text && 
                        text.length > 5 && 
                        !text.includes('Без даты') && 
                        !text.includes('Загрузка') &&
                        (text.includes('Йошкар-Ола') || 
                         text.includes('Волжск') || 
                         text.includes('Звенигово') ||
                         text.includes('ул.') || 
                         text.includes('д.'))) {
                        addresses.push(text);
                    }
                });
            }

            if (addresses.length === 0) {
                throw new Error("Адреса не найдены");
            }

            // Удаляем дубликаты
            const uniqueAddresses = [...new Set(addresses)];
            window.Android.onAddressesParsed(JSON.stringify(uniqueAddresses));
        } catch (err) {
            window.Android.onAddressesParsed("error:" + err.message);
        }
    }

    parseAddresses();
})();


