(function() {
    const TARGET_ADDRESS = "__TARGET_ADDRESS__";

    // Улучшенная функция ожидания элемента для React
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

    // React-совместимый клик
    function reactClick(element) {
        // Создаем события для React
        const mouseDown = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
        const mouseUp = new MouseEvent('mouseup', { bubbles: true, cancelable: true });
        const click = new MouseEvent('click', { bubbles: true, cancelable: true });
        
        element.dispatchEvent(mouseDown);
        element.dispatchEvent(mouseUp);
        element.dispatchEvent(click);
    }

    async function goToAddressAndParse() {
        try {
            // Ждем загрузки страницы с адресами
            await waitForElement('div._dateTasks_36r29_18', 15000);
            await new Promise(resolve => setTimeout(resolve, 1000));

            // Находим адрес в списке (используем includes вместо точного сравнения)
            const items = document.querySelectorAll('div._taskItem_36r29_38');
            let targetItem = null;

            for (let item of items) {
                const span = item.querySelector('div._taskTitle_36r29_45 > span') ||
                           item.querySelector('div._taskTitle_36r29_45 span');
                if (span) {
                    const text = span.innerText.trim();
                    // Используем includes для более гибкого поиска
                    if (text.includes(TARGET_ADDRESS) || TARGET_ADDRESS.includes(text)) {
                        targetItem = item;
                        break;
                    }
                }
            }

            if (!targetItem) {
                throw new Error("Адрес не найден: " + TARGET_ADDRESS);
            }

            // Используем React-совместимый клик
            reactClick(targetItem);
            
            // Ждем навигации React и загрузки счетчиков
            await new Promise(resolve => setTimeout(resolve, 2000));
            
            // Ждем появления контейнера со счетчиками
            const metersContainer = await waitForElement('div._tasksContainer_36r29_11', 15000);
            
            if (!metersContainer) {
                throw new Error("Контейнер счетчиков не найден");
            }

            // Дополнительное ожидание для полной загрузки React компонентов
            await new Promise(resolve => setTimeout(resolve, 1000));

            // Извлекаем данные о счетчиках
            const meters = [];
            const meterItems = metersContainer.querySelectorAll('div._taskItem_36r29_38');
            
            meterItems.forEach((item, index) => {
                const titleElement = item.querySelector('div._taskTitle_36r29_45');
                if (titleElement) {
                    const text = titleElement.innerText.trim();
                    if (text && text.includes('№')) {
                        // Парсим текст вида "Йошкар-Ола, Зарубина, 17, 1\n№23301337 (1 зона)"
                        const lines = text.split('\n');
                        if (lines.length >= 2) {
                            const apartment = lines[0].trim();
                            const meterLine = lines[1].trim();
                            
                            // Извлекаем номер счетчика (убираем "№" и информацию в скобках)
                            let meterNumber = meterLine.replace(/№/g, '').trim();
                            meterNumber = meterNumber.replace(/\s*\([^)]*\)/, '').trim();
                            meterNumber = meterNumber.replace(/\s+/g, ' ').trim();
                            
                            // Определяем статус счетчика по значкам
                            let status = 'NOT_CHECKED';
                            
                            // Ищем красный значок (не проверен)
                            const redIcon = item.querySelector('svg[color="#E0B3B2"], svg[style*="rgb(224, 179, 178)"]');
                            if (redIcon) {
                                status = 'NOT_CHECKED';
                            } else {
                                // Ищем зеленые значки
                                const greenIcons = item.querySelectorAll('svg[color="#95CAB4"], svg[style*="rgb(149, 202, 180)"]');
                                if (greenIcons.length > 0) {
                                    // Проверяем наличие облачка (загружен)
                                    let hasCloudIcon = false;
                                    greenIcons.forEach(icon => {
                                        const path = icon.querySelector('path[d*="M19.35 10.04"]');
                                        if (path) {
                                            hasCloudIcon = true;
                                        }
                                    });
                                    
                                    status = hasCloudIcon ? 'LOADED' : 'CHECKED_NOT_LOADED';
                                }
                            }
                            
                            meters.push({
                                apartment: apartment,
                                meter: meterNumber,
                                status: status
                            });
                        }
                    }
                }
            });

            if (meters.length === 0) {
                throw new Error("Счетчики не найдены");
            }

            window.Android.onMetersParsed(JSON.stringify(meters));
        } catch (err) {
            window.Android.onMetersParsed("error:" + err.message);
        }
    }

    goToAddressAndParse();
})();


