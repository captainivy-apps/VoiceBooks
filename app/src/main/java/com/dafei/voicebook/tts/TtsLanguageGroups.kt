package com.dafei.voicebook.tts

import com.dafei.voicebook.R
import com.dafei.voicebook.data.db.TtsModelEntity

data class TtsLanguageGroup(
    val id: String,
    val labelRes: Int,
    val sortOrder: Int,
)

object TtsLanguageGroups {
    private val groupsByLanguage = mapOf(
        "zh" to TtsLanguageGroup("zh", R.string.tts_language_zh, 1),
        "zh+en" to TtsLanguageGroup("zh_en", R.string.tts_language_zh_en, 2),
        "yue" to TtsLanguageGroup("yue", R.string.tts_language_yue, 3),
        "en" to TtsLanguageGroup("en", R.string.tts_language_en, 4),
        "de" to TtsLanguageGroup("de", R.string.tts_language_de, 10),
        "fr" to TtsLanguageGroup("fr", R.string.tts_language_fr, 11),
        "es" to TtsLanguageGroup("es", R.string.tts_language_es, 12),
        "pt" to TtsLanguageGroup("pt", R.string.tts_language_pt, 13),
        "ru" to TtsLanguageGroup("ru", R.string.tts_language_ru, 14),
        "ko" to TtsLanguageGroup("ko", R.string.tts_language_ko, 15),
        "vi" to TtsLanguageGroup("vi", R.string.tts_language_vi, 16),
        "th" to TtsLanguageGroup("th", R.string.tts_language_th, 17),
        "tr" to TtsLanguageGroup("tr", R.string.tts_language_tr, 18),
        "fa" to TtsLanguageGroup("fa", R.string.tts_language_fa, 19),
        "bn" to TtsLanguageGroup("bn", R.string.tts_language_bn, 20),
    )

    private val otherGroup = TtsLanguageGroup("other", R.string.tts_language_other, 99)

    fun groupFor(language: String): TtsLanguageGroup =
        groupsByLanguage[language.lowercase()] ?: otherGroup

    fun groupModels(models: List<TtsModelEntity>): List<Pair<TtsLanguageGroup, List<TtsModelEntity>>> =
        models
            .groupBy { groupFor(it.language) }
            .entries
            .sortedBy { it.key.sortOrder }
            .map { (group, groupModels) ->
                group to groupModels.sortedBy { it.name }
            }

    fun distinctGroups(models: List<TtsModelEntity>): List<TtsLanguageGroup> =
        models.map { groupFor(it.language) }
            .distinctBy { it.id }
            .sortedBy { it.sortOrder }
}
