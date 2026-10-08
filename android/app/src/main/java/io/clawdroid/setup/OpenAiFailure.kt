package io.clawdroid.setup

/** Does not accept raw error messages, keys or URLs. */
data class OpenAiFailure(val code: String, val message: String) {
    companion object {
        fun from(code: String?, status: Int): OpenAiFailure {
            val safeCode = code?.takeIf { it in setOf("authentication", "quota", "network", "model", "region", "permission", "rate_limit", "invalid_request", "context", "upstream") }
                ?: if (status == 401) "local_gateway_auth" else "upstream"
        val message = when (safeCode) {
            "authentication" -> "OpenAI отклонил ключ. Проверьте его или создайте новый"
            "quota" -> "В OpenAI API закончились средства или достигнут лимит. Проверьте баланс и ограничения"
            "network" -> "Не удалось связаться с OpenAI. Проверьте интернет и повторите попытку"
            "region" -> "OpenAI API недоступен в регионе подключения. Проверьте список поддерживаемых стран OpenAI"
            "permission" -> "У ключа нет разрешения на Responses API. Проверьте права ключа и проекта OpenAI"
            "rate_limit" -> "Достигнут лимит запросов OpenAI. Подождите и повторите подключение"
            "local_gateway_auth" -> "Встроенный сервер отклонил подключение приложения. Перезапустите Джарвис и выгрузите тестовый файл"
            "model" -> "Для этого ключа недоступна модель Джарвиса. Проверьте доступ в OpenAI"
            "invalid_request" -> "Не удалось проверить ключ OpenAI. Проверьте настройки проекта API"
            else -> "OpenAI временно недоступен. Повторите попытку позже"
        }
            return OpenAiFailure(safeCode, message)
        }
    }
}
