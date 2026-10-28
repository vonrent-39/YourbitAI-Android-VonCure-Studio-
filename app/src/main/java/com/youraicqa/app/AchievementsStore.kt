package com.youraicqa.app

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper

data class Achievement(
    val id: Int,
    val title: String,
    val description: String,
    val hint: String = "",
    val hidden: Boolean = false
)

object AchievementsStore {
    private const val PREFS = "nanoai"
    private const val KEY_UNLOCKED = "ach_unlocked"
    private const val KEY_REVEALED = "ach_revealed"
    private const val KEY_VISITED = "ach_visited"

    const val A_FIRST_KEY = 1
    const val A_DOOR_KEYS = 2
    const val A_NEWBIE = 3
    const val A_REGULAR = 4
    const val A_TERMUX_LARP = 5
    const val A_ALL_SYSTEMS = 6
    const val A_LIBRARIAN = 7
    const val A_CURIOUS = 8
    const val A_ASHIBKA = 9
    const val A_BEARS = 10
    const val A_EAGLES = 11
    const val A_WHO = 12
    const val A_FREEDOM = 13
    const val A_TIKTOK = 14
    const val A_CLOSE_TO_TRUTH = 15
    const val A_FANS = 16
    const val A_DELTARUNE = 17
    const val A_SPACES = 18
    const val A_SOON = 19
    const val A_PRONOUNS = 20
    const val A_BURMALDA = 21
    const val A_SYBAU = 22
    const val A_KEYS10 = 23
    const val A_GRASS = 24
    const val A_ANGRY = 25
    const val A_SIX_SEVEN = 67
    const val A_CURSED = 26

    private const val KEY_MSG_COUNT = "ach_msg_count"
    private const val KEY_KEY_ERRORS = "ach_key_errors"
    private const val KEY_FG_MS = "ach_fg_ms"
    private const val GRASS_MS = 3L * 60 * 60 * 1000

    val ALL: List<Achievement> = listOf(
        Achievement(
            A_FIRST_KEY, "Начало экспериментов",
            "Подготовьте хотя бы один API-Ключ для начала работы с ИИ.",
            hidden = true
        ),
        Achievement(
            A_DOOR_KEYS, "А вы точно умеете открывать дверь ключами?",
            "Я умею открывать двери, это просто ключи не правильные какие-то..."
        ),
        Achievement(A_NEWBIE, "Новичок", "Отправьте свой первый запрос."),
        Achievement(
            A_REGULAR, "Обычный",
            "Поздравляю, вы освоили базовые функции! Обычно это вкладки «Модели, API-Keys, FAQ, Кастомизация, Библиотека»."
        ),
        Achievement(A_TERMUX_LARP, "Ларп термукса", "Найдите терминал, и напишите там свою первую команду."),
        Achievement(A_ALL_SYSTEMS, "Все системы включены!", "Включите сразу все параметры во вкладке экономики."),
        Achievement(A_LIBRARIAN, "Библиотекарь", "Найдите библиотеку файлов."),
        Achievement(A_CURIOUS, "Любопытство залог успеха!", "Найдите DevMenu."),
        Achievement(
            A_ASHIBKA, "АЩИБКА", "Получите любую «АщИбКу»."
        ),
        Achievement(
            A_BEARS, "А у вас учатся медведи в школах?",
            "Ни один стереотип не постра-..."
        ),
        Achievement(A_EAGLES, "🦅🦅🦅🦅🦅🦅🦅🦅🦅🦅🦅", "???", hidden = true),
        Achievement(A_WHO, "А вы кто?", "Напишите запрос «Yourbit AI» типа — «ты кто»"),
        Achievement(
            A_FREEDOM, "*Звуки of the Freedom Motive*",
            "Я могу всё! Хаос хаос! (Спросите у Yourbit AI что она умеет)."
        ),
        Achievement(
            A_TIKTOK, "ТикТок против капли человечности",
            "Напишите в запросе слова «Коч братан, Пантера, Эль патрон, Голда»."
        ),
        Achievement(A_CLOSE_TO_TRUTH, "Кажется, я близок к разгадке.", "Напишите 228 сообщений."),
        Achievement(A_FANS, "Вентиляторы, быки!", "Напишите уже 1[—][—][—] сообщений."),
        Achievement(
            A_DELTARUNE, "Ядерка прикрывает мое сознание",
            "Снова трещит мотив свободы, гастер награждает вас званием «доходяги».",
            hidden = true
        ),
        Achievement(A_SPACES, "ПРОБЕЛматик!", "У нас всё завтра, за то речь красивая.", hidden = true),
        Achievement(A_SOON, "Soon🤞🤞🤞", "Сыр.", hidden = true),
        Achievement(A_PRONOUNS, "Hello, Место... Имения!", "Срачи в твиттерах это конечно бомба!", hidden = true),
        Achievement(A_BURMALDA, "Бурмалда", "Вкусность.", hidden = true),
        Achievement(A_SYBAU, "Sy[—][—][—]🤞🥀", "Кд чд шд бд дд кд шд чд фр мб бтв бмв.", hidden = true),
        Achievement(A_KEYS10, "Ну научись ты уже открывать дверь ключами, господи.", "???", hidden = true),
        Achievement(A_GRASS, "Потрогайте траву.", "Пробудьте в YourAI три часа!"),
        Achievement(A_ANGRY, "Артемзлой6769777", "Злюка, бяка забияка, вы кака*ка.", hidden = true),
        Achievement(A_SIX_SEVEN, "SixSeveeeeeeeen!!!!", "Напишите 67 сообщений."),
        Achievement(A_CURSED, "Проклятый запрос.", "Напишите 666 сообщений.")
    )

    private const val NB = "(?<![\\p{L}\\p{N}])"
    private const val NA = "(?![\\p{L}\\p{N}])"

    private val RX_WHO = Regex(
        "(?:$NB(?:ты|вы)\\s+кто$NA|${NB}кто\\s+(?:ты|вы)$NA|who\\s+are\\s+you)", RegexOption.IGNORE_CASE
    )
    private val RX_CAPS = Regex(
        "(?:${NB}что\\s+(?:(?:ты|вы)\\s+)?(?:умеешь|умеете|можешь|можете)$NA|what\\s+can\\s+you\\s+do)",
        RegexOption.IGNORE_CASE
    )
    private val RX_TIKTOK = Regex("коч\\s+братан|пантера|эль\\s+патрон|голда", RegexOption.IGNORE_CASE)
    private val RX_DELTARUNE = Regex("deltarune|дельтарун", RegexOption.IGNORE_CASE)
    private val RX_PRONOUNS = Regex("местоимен|pronoun", RegexOption.IGNORE_CASE)
    private val RX_BURMALDA = Regex("бурмалд|burmalda", RegexOption.IGNORE_CASE)
    private val RX_SYBAU = Regex("sybau", RegexOption.IGNORE_CASE)
    private val RX_SPACES = Regex("\\S {5,}")
    private val RX_ANGRY = Regex(
        "(?:${NB}(?:за|на|вы|по|до|от|съ|объ|разъ|раз|под|при|про|пере|у|с|в|о|ра)?(?:ебан|ебат|ебал|ебну|ебуч|ёбан|ёбн|ебыр|ёбыр)" +
            "|${NB}(?:на|по|за|от|вы|до|при|раз|с|не|ни)?(?:хуй|хуе|хуё|хуя)" +
            "|${NB}(?:пизд|бляд|блят|гандон|залуп|шлюх|мудак|мудил|пидор|пидар|пидр|сука|суки|суку|сучк|долбо[её]б|уебан|уёбок|уебок)" +
            "|${NB}(?:идиот|дебил|кретин|мразь|мрази|тварь|тупица|придурок|заткнись|ничтожеств|ненавижу\\s+тебя)" +
            "|\\b(?:fuck|fucking|bitch|asshole|cunt|dumbass|shut\\s+up)\\b)",
        RegexOption.IGNORE_CASE
    )

    private val REGULAR_TABS = setOf("models", "apikeys", "faq", "customize", "library")

    private val CYRILLIC_REGEX = Regex("[А-Яа-яЁё]")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readSet(context: Context, key: String): Set<String> =
        prefs(context).getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun writeSet(context: Context, key: String, value: Set<String>) {
        prefs(context).edit().putStringSet(key, HashSet(value)).apply()
    }

    fun isUnlocked(context: Context, id: Int): Boolean = readSet(context, KEY_UNLOCKED).contains(id.toString())

    fun unlockedCount(context: Context): Int = readSet(context, KEY_UNLOCKED).size

    fun isVisible(context: Context, a: Achievement): Boolean =
        !a.hidden || isUnlocked(context, a.id) || readSet(context, KEY_REVEALED).contains(a.id.toString())

    fun unlock(context: Context, id: Int) {
        val app = context.applicationContext
        val cur = readSet(app, KEY_UNLOCKED)
        if (cur.contains(id.toString())) return
        writeSet(app, KEY_UNLOCKED, cur + id.toString())
        val a = ALL.firstOrNull { it.id == id } ?: return
        AchievementPopup.show(app, a)
    }

    fun reveal(context: Context, id: Int) {
        val app = context.applicationContext
        val cur = readSet(app, KEY_REVEALED)
        if (!cur.contains(id.toString())) writeSet(app, KEY_REVEALED, cur + id.toString())
    }

    fun onMessageSent(context: Context, text: String, raw: String = text) {
        val app = context.applicationContext
        unlock(app, A_NEWBIE)
        if (CYRILLIC_REGEX.containsMatchIn(text)) unlock(app, A_BEARS)

        val n = prefs(app).getLong(KEY_MSG_COUNT, 0L) + 1
        prefs(app).edit().putLong(KEY_MSG_COUNT, n).apply()
        if (n >= 67) unlock(app, A_SIX_SEVEN)
        if (n >= 228) unlock(app, A_CLOSE_TO_TRUTH)
        if (n >= 666) unlock(app, A_CURSED)
        if (n >= 1488) unlock(app, A_FANS)

        if (text.isBlank() && raw.isBlank()) return
        if (looksEnglish(text)) unlock(app, A_EAGLES)
        if (RX_WHO.containsMatchIn(text)) unlock(app, A_WHO)
        if (RX_CAPS.containsMatchIn(text)) unlock(app, A_FREEDOM)
        if (RX_TIKTOK.containsMatchIn(text)) unlock(app, A_TIKTOK)
        if (RX_DELTARUNE.containsMatchIn(text)) unlock(app, A_DELTARUNE)
        if (RX_PRONOUNS.containsMatchIn(text)) unlock(app, A_PRONOUNS)
        if (RX_BURMALDA.containsMatchIn(text)) unlock(app, A_BURMALDA)
        if (RX_SYBAU.containsMatchIn(text)) unlock(app, A_SYBAU)
        if (text.contains("🤞") || text.contains("🥀")) unlock(app, A_SOON)
        if (RX_SPACES.containsMatchIn(raw)) unlock(app, A_SPACES)
        if (RX_ANGRY.containsMatchIn(text)) unlock(app, A_ANGRY)
    }

    private val RX_LATIN_WORD = Regex("[A-Za-z]{2,}")

    private fun looksEnglish(text: String): Boolean =
        !CYRILLIC_REGEX.containsMatchIn(text) && RX_LATIN_WORD.findAll(text).count() >= 2

    fun addForegroundTime(context: Context, deltaMs: Long) {
        if (deltaMs <= 0) return
        val app = context.applicationContext
        val total = prefs(app).getLong(KEY_FG_MS, 0L) + deltaMs
        prefs(app).edit().putLong(KEY_FG_MS, total).apply()
        if (total >= GRASS_MS) unlock(app, A_GRASS)
    }

    fun onErrorShown(context: Context, code: Int) {
        unlock(context, A_ASHIBKA)
        if (code == 22 || code == 1) reveal(context, A_FIRST_KEY)
        if (code == 1) unlock(context, A_DOOR_KEYS)
        if (code == 1) reveal(context, A_KEYS10)
        if (code == 22 || code == 1) {
            val app = context.applicationContext
            val n = prefs(app).getInt(KEY_KEY_ERRORS, 0) + 1
            prefs(app).edit().putInt(KEY_KEY_ERRORS, n).apply()
            if (n >= 10) unlock(app, A_KEYS10)
            refreshDerived(context)
        }
    }

    fun onTabOpened(context: Context, tab: String) {
        val app = context.applicationContext
        val visited = readSet(app, KEY_VISITED)
        if (!visited.contains(tab)) writeSet(app, KEY_VISITED, visited + tab)
        if (tab == "library") unlock(app, A_LIBRARIAN)
        if (readSet(app, KEY_VISITED).containsAll(REGULAR_TABS)) unlock(app, A_REGULAR)
    }

    fun onDevMenuOpened(context: Context) = unlock(context, A_CURIOUS)

    fun onTerminalCommand(context: Context) = unlock(context, A_TERMUX_LARP)

    fun onEconomyAllOn(context: Context) = unlock(context, A_ALL_SYSTEMS)

    fun refreshDerived(context: Context) {
        val app = context.applicationContext
        if (readSet(app, KEY_REVEALED).contains(A_FIRST_KEY.toString()) && hasAnyKey(app)) {
            unlock(app, A_FIRST_KEY)
        }
    }

    private fun hasAnyKey(context: Context): Boolean {
        val builtin = ApiKeyGate.BUILTIN_PROVIDERS
        if (builtin.any { ApiKeyGate.hasOwnKey(context, it) }) return true
        return ProvidersStore.load(context).any { it.apiKey.isNotBlank() }
    }

    private var cachedFont: Typeface? = null

    fun font(context: Context): Typeface {
        cachedFont?.let { return it }
        val tf = try {
            Typeface.createFromAsset(context.applicationContext.assets, "fonts/vcrosdmonorusbydaymarius.ttf")
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
        cachedFont = tf
        return tf
    }
}
