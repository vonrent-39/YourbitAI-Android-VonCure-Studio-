package com.youraicqa.app

enum class PluginPermission(val label: String) {
    UI_THEME("Оформление (цвета, градиенты, эффекты, рамки, анимации, шрифт)"),
    QUICK_ACTIONS("Быстрые действия / кнопки в чате"),
    MESSAGE_HOOKS("Обработка текста сообщений перед отправкой"),
    MESSAGE_RENDER("Обработка текста ответа модели перед показом"),
    SEARCH_SOURCE("Свой источник поиска"),
    CUSTOM_PANEL("Свой экран/панель (HTML внутри песочницы)"),
    APP_SETTINGS("Небезопасные-для-данных параметры приложения (не ключи/прокси)"),
    SETTINGS("Собственные параметры плагина (свой изолированный стор)"),
    NETWORK("Сетевые запросы к любым публичным серверам через nano.net"),
    DOWNLOAD("Скачивание файлов с любых публичных серверов через nano.download"),
    FILES("Сохранение файлов в собственную папку плагина"),
    BACKGROUND_TASK("Фоновые задачи с прогрессом"),
    UI_ACTION("Свои действия в интерфейсе и уведомления"),
    UI_EXTEND("Встраивать кнопки, вкладки и блоки в интерфейс приложения"),
    AI_PROVIDER("Свои AI-провайдеры и модели (получают историю чата, когда выбрана их модель)");

    companion object {
        fun parseSet(raw: org.json.JSONArray?): Set<PluginPermission> {
            if (raw == null) return emptySet()
            val out = mutableSetOf<PluginPermission>()
            for (i in 0 until raw.length()) {
                val name = raw.optString(i).trim().uppercase().replace('.', '_')
                entries.firstOrNull { it.name == name }?.let { out.add(it) }
            }
            return out
        }
    }
}
