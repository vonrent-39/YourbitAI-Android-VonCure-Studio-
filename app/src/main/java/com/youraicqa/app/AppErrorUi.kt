package com.youraicqa.app

/** Stable, user-facing error catalogue used by the diagnostic popup. */
object AppErrorUi {
    data class Item(val code: Int, val title: String, val description: String, val technical: String = "")

    fun classify(raw: String?, isVision: Boolean = false, isLocal: Boolean = false): Item {
        val original = raw.orEmpty()
        val s = original.lowercase()
        val serverCode = Regex("(?i)код ошибки\\s*:\\s*(e\\d{2})").find(original)?.groupValues?.get(1)?.uppercase()
            ?: Regex("(?i)\\[\\[srverr\\]\\]\\s*(e\\d{2})").find(original)?.groupValues?.get(1)?.uppercase()
        fun item(code: Int, title: String, description: String) =
            Item(code, title, description, original.take(240))

        return when {
            s.contains("no_api_key") || s.contains("no api key") || s.contains("missing api key") ||
                s.contains("api key is required") || s.contains("api_key is required") || s.contains("missing key") ||
                s.contains("нет ключа") || s.contains("ключ не задан") || s.contains("api-ключ отсутств") ->
                item(22, "Нет ключа", "Добавьте API-ключ в настройках провайдера или выберите локальную модель/свой сервер.")

            s.contains("password") || s.contains("парол") || s.contains("account credential") ->
                item(17, "Неверный пароль аккаунта", "Сервис отклонил данные входа. Проверьте логин и пароль в настройках сервиса.")

            serverCode == "E01" || s.contains("401") || s.contains("unauthorized") ||
                s.contains("invalid api key") || s.contains("incorrect api key") || s.contains("неверный ключ") ||
                s.contains("authentication failed") || s.contains("сервер не принял ключ") ->
                item(1, "Неверный ключ", "Провайдер отклонил ключ. Проверьте его значение и доступ к API.")

            s.contains("image input is not supported") || s.contains("images are not supported") ||
                s.contains("image is not supported") || s.contains("vision is not supported") ||
                s.contains("does not support image") || s.contains("doesn't support image") ||
                s.contains("does not support vision") || s.contains("doesn't support vision") ||
                s.contains("не поддерживает изображ") || s.contains("анализ изображений недоступен") ||
                s.contains("image_url is not supported") ->
                item(10, "Анализ изображений недоступен", "Эта модель или провайдер не поддерживает входные изображения. Выберите vision-модель или отправьте текстовое описание.")

            serverCode == "E05" || s.contains("timeout") || s.contains("timed out") || s.contains("таймаут") ||
                s.contains("не ответил вовремя") || s.contains("время ожидания истек") ->
                item(5, "Таймаут ответа", "Сервер не ответил за отведённое время. Попробуйте повторить запрос или выбрать другую модель.")

            serverCode == "E09" || s.contains("429") || s.contains("rate limit") || s.contains("quota") ||
                s.contains("лимит") || s.contains("too many requests") ->
                item(9, "Лимит исчерпан", "У провайдера закончился доступный лимит или слишком много запросов. Подождите либо проверьте баланс.")

            serverCode == "E03" || s.contains("404") || s.contains("model not found") || s.contains("unknown model") ||
                s.contains("invalid model") || s.contains("некорректная модель") || s.contains("модель не найдена") ||
                s.contains("не нашёл адрес или модель") || s.contains("не нашел адрес или модель") ->
                item(4, "Некорректная модель", "Проверьте ID модели и то, что выбранный провайдер её поддерживает.")

            serverCode == "E06" || serverCode == "E14" || s.contains("connection refused") ||
                s.contains("unknownhost") || s.contains("unable to resolve host") || s.contains("custom server") ||
                s.contains("свой сервер") || s.contains("кастомный сервер") || s.contains("не удаётся подключиться к серверу") ||
                s.contains("не удается подключиться к серверу") || s.contains("dns_resolution_failed") ||
                s.contains("адрес api") ->
                item(6, "Кастомный сервер недоступен", "Не удалось подключиться к указанному серверу. Проверьте URL, сеть и доступность сервиса.")

            s.contains("plugin") || s.contains("плагин") || s.contains("plugin_runtime") ->
                item(8, "Ошибка плагина", "Плагин завершился с ошибкой. Отключите его и попробуйте снова; затем проверьте журнал плагинов.")

            s.contains("system prompt") || (s.contains("системн") && s.contains("промпт")) ->
                item(11, "Ошибка системного промпта", "Системный промпт не удалось обработать. Проверьте его формат, размер и настройки режима разработчика.")

            s.contains("archive") || s.contains("zip") || s.contains("docx") || s.contains("pdf") ||
                (s.contains("поврежд") && s.contains("файл")) || s.contains("word/document.xml") ->
                item(12, "Повреждённый архив или файл", "Не удалось прочитать файл. Убедитесь, что он не повреждён, не защищён паролем и имеет правильное расширение.")

            s.contains("search") || s.contains("поисков") || s.contains("duckduckgo") || s.contains("wikipedia") ->
                item(15, "Ошибка поисковика", "Поиск временно недоступен. Проверьте подключение к интернету или повторите запрос позже.")

            s.contains("context length") || s.contains("context window") || s.contains("maximum context") ||
                s.contains("контекст") || s.contains("too many tokens") ->
                item(20, "Ошибка контекста", "История или вложение слишком велики для контекстного окна. Сократи запрос или начни новый чат.")

            serverCode == "E21" || (s.contains("stream") && (s.contains("cut") || s.contains("closed") || s.contains("disconnect"))) ||
                s.contains("обрыв") || s.contains("оборван") || s.contains("поток ответа прервался") ->
                item(21, "Ответ оборван", "Соединение прервалось во время генерации. Продолжи ответ или повторите запрос.")

            s.contains("no answer") || s.contains("empty response") || s.contains("пустой ответ") ||
                s.contains("не отвечает") || s.contains("не удалось получить ответ") || s.contains("модель вернула пуст") ->
                item(3, "Модель не отвечает", "Сервер вернул пустой ответ или временно не отвечает. Попробуйте ещё раз или выберите другую модель.")

            isLocal -> item(19, "Ошибка локальной модели", "Локальная модель не смогла выполнить запрос. Проверьте файл модели, свободную память и совместимость формата.")
            isVision -> item(3, "Модель не отвечает", "Не удалось получить ответ при обработке запроса с изображением. Проверьте доступность выбранной модели и повтори попытку.")
            else -> item(3, "Модель не отвечает", "Не удалось получить ответ от выбранной модели. Проверьте подключение и попробуйте ещё раз.")
        }
    }
}
