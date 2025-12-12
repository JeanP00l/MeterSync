(function() {
    const TARGET_ADDRESS = "__TARGET_ADDRESS__";

    // Улучшенная функция ожидания элемента для React (оптимизированная)
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
            }, 200); // Увеличена частота проверок с 400ms до 200ms
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
            // Оптимизированное ожидание загрузки списка адресов
            await waitForElement('div._dateTasks_36r29_18', 8000);
            // Минимальная задержка для инициализации React
            await new Promise(resolve => setTimeout(resolve, 200)); // Уменьшено с 300ms до 200ms

            // Находим адрес в списке с приоритетом более точных совпадений
            const items = document.querySelectorAll('div._taskItem_36r29_38');
            const addressList = [];
            
            for (let item of items) {
                const span = item.querySelector('div._taskTitle_36r29_45 > span') ||
                           item.querySelector('div._taskTitle_36r29_45 span');
                if (span) {
                    const text = span.innerText.trim();
                    addressList.push({ text: text, element: item });
                }
            }
            
            let targetItem = null;
            
            // Сначала ищем точное совпадение
            for (let addr of addressList) {
                if (addr.text === TARGET_ADDRESS) {
                    targetItem = addr.element;
                    break;
                }
            }
            
            // Если точного совпадения нет, ищем по началу адреса
            // Сортируем адреса по длине (от длинных к коротким) для приоритета более точных совпадений
            if (!targetItem) {
                addressList.sort((a, b) => b.text.length - a.text.length);
                
                for (let addr of addressList) {
                    // Проверяем, что текст адреса начинается с целевого адреса
                    if (addr.text.startsWith(TARGET_ADDRESS)) {
                        // Проверяем, что после целевого адреса идет разделитель (запятая, пробел, слэш, буква корпуса)
                        const nextChar = addr.text[TARGET_ADDRESS.length];
                        const isLetter = nextChar && /[а-яА-Яa-zA-Z]/.test(nextChar);
                        if (addr.text.length === TARGET_ADDRESS.length || 
                            nextChar === ',' || 
                            nextChar === ' ' ||
                            nextChar === '/' ||
                            nextChar === 'к' ||
                            nextChar === 'К' ||
                            isLetter) {
                            // Важно: проверяем, что нет более длинного адреса, который тоже начинается с целевого
                            const hasLongerMatch = addressList.some(other => {
                                if (other === addr || other.text.length <= addr.text.length) return false;
                                if (!other.text.startsWith(TARGET_ADDRESS)) return false;
                                const otherNextChar = other.text[TARGET_ADDRESS.length];
                                const otherIsLetter = otherNextChar && /[а-яА-Яa-zA-Z]/.test(otherNextChar);
                                return other.text.length === TARGET_ADDRESS.length || 
                                       otherNextChar === ',' || 
                                       otherNextChar === ' ' ||
                                       otherNextChar === '/' ||
                                       otherNextChar === 'к' ||
                                       otherNextChar === 'К' ||
                                       otherIsLetter;
                            });
                            
                            if (!hasLongerMatch) {
                                targetItem = addr.element;
                                break;
                            }
                        }
                    }
                }
            }

            if (!targetItem) {
                throw new Error("Адрес не найден: " + TARGET_ADDRESS);
            }

            // Используем React-совместимый клик
            reactClick(targetItem);
            
            // Оптимизированное ожидание: ждем появления контейнера со счетчиками
            // Вместо фиксированной задержки используем умное ожидание
            const metersContainer = await waitForElement('div._tasksContainer_36r29_11', 10000);
            
            if (!metersContainer) {
                throw new Error("Контейнер счетчиков не найден");
            }

            // Ждем появления хотя бы одного счетчика - более агрессивная проверка
            let attempts = 0;
            while (attempts < 15) { // Уменьшено с 20 до 15 попыток
                const meterItems = metersContainer.querySelectorAll('div._taskItem_36r29_38');
                if (meterItems.length > 0) {
                    break;
                }
                await new Promise(resolve => setTimeout(resolve, 80)); // Уменьшено с 100ms до 80ms
                attempts++;
            }

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


