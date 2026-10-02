package org.telegram.tasks.localization

/**
 * NagramX: maps the language tags read from `values-*` folders onto the tags the runtime looks up.
 *
 * The runtime picks an asset by `Locale.toLanguageTag()` and only falls back to a bare language for tags
 * without a region. The fork ships its translations in region folders (`values-ru-rRU`, `values-in-rID`),
 * and a language pack selects `Locale("ru")`, which would match none of them. So a region folder is folded
 * onto its bare language when that is unambiguous, and legacy language codes are renamed to the ones
 * `Locale.toLanguageTag()` emits.
 */
internal object LanguageTagAliases {

    private val LEGACY_LANGUAGES = mapOf(
        "in" to "id",
        "iw" to "he",
        "ji" to "yi"
    )

    private val REGION = Regex("[A-Za-z]{2}|[0-9]{3}")

    fun canonicalize(rawTags: Set<String>): Map<String, String> {
        val withoutRegion = rawTags
            .filter { !it.contains('-') }
            .map { legacy(it) }
            .toSet()

        val regionsByLanguage = rawTags
            .filter { it.contains('-') }
            .groupBy({ legacy(it.substringBefore('-')) }, { it.substringAfter('-') })

        val result = LinkedHashMap<String, String>()

        for (tag in rawTags) {
            if (!tag.contains('-')) {
                result[tag] = legacy(tag)
                continue
            }

            val language = legacy(tag.substringBefore('-'))
            val region = tag.substringAfter('-')

            result[tag] = when {
                !REGION.matches(region) -> "$language-$region"
                withoutRegion.contains(language) -> language
                regionsByLanguage.getValue(language).size == 1 -> language
                else -> "$language-$region"
            }
        }

        return result
    }

    /**
     * A bare language that has only region variants (zh-CN and zh-TW) still has to resolve, because a language
     * pack builds Locale("zh") and the lookup would otherwise find nothing. Prefer the listed region, else the first.
     */
    fun languageFallbacks(tags: Set<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for ((language, variants) in tags.filter { it.contains('-') }.sorted().groupBy { it.substringBefore('-') }) {
            if (tags.contains(language)) continue
            val preferred = PREFERRED_REGION[language]?.let { "$language-$it" }
            result[language] = if (preferred != null && variants.contains(preferred)) preferred else variants.first()
        }
        return result
    }

    private val PREFERRED_REGION = mapOf("zh" to "CN")

    private fun legacy(language: String): String = LEGACY_LANGUAGES[language] ?: language
}
