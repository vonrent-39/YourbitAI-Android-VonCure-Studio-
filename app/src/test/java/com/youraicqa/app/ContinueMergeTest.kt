package com.youraicqa.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContinueMergeTest {
    @Test fun plainContinuationIsAppended() {
        val prev = "Первый абзац закончен.\n\nВторой абзац начинается и обрывается на сло"
        assertEquals("$prev во", ContinueMerge.merge(prev, "во"))
    }

    @Test fun repeatedTailIsNotDuplicated() {
        val prev = "Шаг 1. Установите пакет и настройте окружение. Шаг 2. Запустите сервер и проверьте логи, чтобы"
        val cont = "Запустите сервер и проверьте логи, чтобы убедиться, что всё работает."
        val merged = ContinueMerge.merge(prev, cont)
        assertEquals(1, Regex("Запустите сервер").findAll(merged).count())
        assertEquals(prev + " убедиться, что всё работает.", merged)
    }

    @Test fun repeatedLastLineIsNotDuplicated() {
        val prev = "Список:\n- первый пункт\n- второй пункт про то как это рабо"
        val cont = "- второй пункт про то как это работает\n- третий пункт"
        val merged = ContinueMerge.merge(prev, cont)
        assertEquals(1, Regex("второй пункт").findAll(merged).count())
        assertEquals(true, merged.endsWith("- третий пункт"))
    }

    @Test fun reopenedCodeFenceIsDropped() {
        val prev = "Вот код:\n```kotlin\nfun main() {\n    println(\"hi\")"
        val cont = "```kotlin\n    println(\"bye\")\n}\n```"
        val merged = ContinueMerge.merge(prev, cont)
        assertEquals(2, Regex("```").findAll(merged).count())
    }

    @Test fun restartFromBeginningIsNotDuplicated() {
        val prev = "Москва — столица России и крупнейший город страны, расположенный на реке Москве, основан в 1147 году"
        val cont = prev + " князем Юрием Долгоруким."
        val merged = ContinueMerge.merge(prev, cont)
        assertEquals(1, Regex("Москва — столица").findAll(merged).count())
        assertEquals(true, merged.endsWith("Долгоруким."))
    }

    @Test fun emptyContinuationKeepsPrevious() {
        assertEquals("abc", ContinueMerge.merge("abc", "  \n"))
    }

    @Test fun punctuationIsGluedWithoutSpace() {
        assertFalse(ContinueMerge.merge("конец предложения", ", и дальше").contains(" ,"))
    }

    private val studio = "VonCure Studio — инди-студия, основанная 13 октября 2025 года. Это команда энтузиастов, которые делают всё из чистого энтузиазма и sheer boredom, а также помогают подобным студиям. Студия создаёт игры, программы и Telegram-боты. Ссылка: https://t.me/VonCure_Studio"

    @Test fun answerRestatedWithParagraphsIsNotDuplicated() {
        val cont = "VonCure Studio — инди-студия, основанная 13 октября 2025 года. Это команда энтузиастов, которые делают всё из чистого энтузиазма и sheer boredom, а также помогают подобным студиям.\n\nСтудия создаёт игры, программы и Telegram-боты.\n\nСсылка: https://t.me/VonCure_Studio"
        assertEquals(studio, ContinueMerge.merge(studio, cont))
    }

    @Test fun restatedAnswerWithExtraTextKeepsOnlyNewPart() {
        val cont = "**VonCure Studio** — инди-студия, основанная 13 октября 2025 года. Это команда энтузиастов, которые делают всё из чистого энтузиазма и sheer boredom, а также помогают подобным студиям.\n\nСтудия создаёт игры, программы и Telegram-боты.\n\nСсылка: https://t.me/VonCure_Studio\n\nУ студии есть партнёры."
        val merged = ContinueMerge.merge(studio, cont)
        assertEquals(1, Regex("инди-студия").findAll(merged).count())
        assertEquals(true, merged.startsWith(studio))
        assertEquals(true, merged.endsWith("У студии есть партнёры."))
    }

    @Test fun overlapWithDifferentWhitespaceIsDropped() {
        val merged = ContinueMerge.merge(studio, "Ссылка: https://t.me/VonCure_Studio\n\nУ студии есть партнёры.")
        assertEquals(1, Regex("Ссылка").findAll(merged).count())
        assertEquals(true, merged.endsWith("партнёры."))
    }
}
