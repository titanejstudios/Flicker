package com.titanejstudios.tuner.data

import java.util.Locale

/**
 * Works out which country or language a channel, film or category is for, from the tags
 * providers put in names ("UK | Sport", "|AR| MBC 1", "[FR] Films", "Arabic Movies"), so
 * non-English ones can be hidden. Anything without a tag counts as "no country" and stays.
 */
object Languages {
    class Tag(val code: String, val name: String, val english: Boolean)

    private val tags: Map<String, Tag> = listOf(
        Tag("UK", "UK", true), Tag("EN", "English", true), Tag("US", "USA", true), Tag("CA", "Canada", true),
        Tag("AU", "Australia", true), Tag("NZ", "New Zealand", true), Tag("IE", "Ireland", true),
        Tag("AR", "Arabic", false), Tag("FR", "French", false), Tag("DE", "German", false), Tag("ES", "Spanish", false),
        Tag("IT", "Italian", false), Tag("TR", "Turkish", false), Tag("PL", "Polish", false), Tag("PT", "Portuguese", false),
        Tag("NL", "Dutch", false), Tag("BE", "Belgium", false), Tag("IN", "India", false), Tag("PK", "Pakistan", false),
        Tag("BD", "Bangla", false), Tag("IR", "Persian", false), Tag("KU", "Kurdish", false), Tag("AL", "Albanian", false),
        Tag("GR", "Greek", false), Tag("RO", "Romanian", false), Tag("RU", "Russian", false), Tag("UA", "Ukrainian", false),
        Tag("SE", "Swedish", false), Tag("NO", "Norwegian", false), Tag("DK", "Danish", false), Tag("FI", "Finnish", false),
        Tag("BR", "Brazil", false), Tag("MX", "Mexico", false), Tag("LAT", "Latino", false), Tag("EXYU", "Ex-Yu", false),
        Tag("HR", "Croatian", false), Tag("RS", "Serbian", false), Tag("BG", "Bulgarian", false), Tag("HU", "Hungarian", false),
        Tag("CZ", "Czech", false), Tag("SK", "Slovak", false), Tag("IL", "Hebrew", false), Tag("CN", "Chinese", false),
        Tag("JP", "Japanese", false), Tag("KR", "Korean", false), Tag("PH", "Filipino", false), Tag("TH", "Thai", false),
        Tag("VN", "Vietnamese", false), Tag("AFR", "African", false), Tag("CARIB", "Caribbean", false), Tag("SO", "Somali", false),
        Tag("CH", "Swiss", false), Tag("AT", "Austrian", false), Tag("LT", "Lithuanian", false), Tag("LV", "Latvian", false),
        Tag("EE", "Estonian", false), Tag("MK", "Macedonian", false), Tag("BA", "Bosnian", false), Tag("SI", "Slovenian", false),
        Tag("AM", "Armenian", false), Tag("AZ", "Azerbaijani", false), Tag("GE", "Georgian", false), Tag("CY", "Cyprus", false),
    ).associateBy { it.code }

    private val aliases = mapOf(
        "GB" to "UK", "ENG" to "EN", "USA" to "US", "IRL" to "IE", "CAN" to "CA", "AUS" to "AU",
        "ARA" to "AR", "ARAB" to "AR", "FRA" to "FR", "GER" to "DE", "DEU" to "DE", "ESP" to "ES", "ITA" to "IT",
        "TUR" to "TR", "POL" to "PL", "POR" to "PT", "NLD" to "NL", "IND" to "IN", "PAK" to "PK",
        "LATINO" to "LAT", "LA" to "LAT", "YU" to "EXYU", "AF" to "AFR",
    )

    private val words = listOf(
        "arabic" to "AR", "french" to "FR", "german" to "DE", "deutsch" to "DE", "spanish" to "ES", "español" to "ES",
        "italian" to "IT", "turkish" to "TR", "türk" to "TR", "polish" to "PL", "polska" to "PL", "portuguese" to "PT",
        "dutch" to "NL", "hindi" to "IN", "tamil" to "IN", "telugu" to "IN", "malayalam" to "IN", "punjabi" to "IN",
        "urdu" to "PK", "bangla" to "BD", "bengali" to "BD", "persian" to "IR", "farsi" to "IR", "kurdish" to "KU",
        "albania" to "AL", "greek" to "GR", "romania" to "RO", "russian" to "RU", "ukrain" to "UA", "swedish" to "SE",
        "norwegian" to "NO", "danish" to "DK", "finnish" to "FI", "brazil" to "BR", "latino" to "LAT", "ex-yu" to "EXYU",
        "croatia" to "HR", "serbia" to "RS", "bulgaria" to "BG", "hungar" to "HU", "czech" to "CZ", "hebrew" to "IL",
        "chinese" to "CN", "japan" to "JP", "korean" to "KR", "filipino" to "PH", "pinoy" to "PH", "thai" to "TH",
        "vietnam" to "VN", "africa" to "AFR", "caribbean" to "CARIB", "somali" to "SO",
    )

    /** "UK | Sport", "|AR| MBC", "[FR] Films", "DE ▎ Sport", "VIP | UK Sport" → the leading tags. */
    private val leading = Regex("^[\\s\\[(|▎┃#*★]*([A-Za-z]{2,6})[\\s\\])]*[|:\\-–▎┃]")
    private val bracketed = Regex("[\\[(|]\\s*([A-Za-z]{2,6})\\s*[\\])|]")

    /** The country/language tag in [text], or null if there isn't one. */
    fun tagOf(text: String, useWords: Boolean = true): Tag? {
        val t = text.trim()
        fun known(raw: String): Tag? {
            val c = raw.uppercase(Locale.ROOT)
            return tags[aliases[c] ?: c]
        }
        leading.find(t)?.let { m ->
            known(m.groupValues[1])?.let { return it }
            // "VIP | UK Sport": look once more after the first tag.
            val rest = t.substring(m.range.last + 1)
            leading.find(rest)?.let { m2 -> known(m2.groupValues[1])?.let { return it } }
            Regex("^\\s*([A-Za-z]{2,3})\\b").find(rest)?.let { m3 -> known(m3.groupValues[1])?.let { return it } }
        }
        bracketed.find(t)?.let { m -> known(m.groupValues[1])?.let { return it } }
        if (!useWords) return null
        val lower = t.lowercase(Locale.ROOT)
        for ((w, code) in words) if (lower.contains(w)) return tags[code]
        return null
    }

    /** A channel's tag: from its category, else its own name. */
    fun tagOf(channel: Channel): Tag? = cached(channel.group, true) ?: cached(channel.name, false)

    // Remembered per category / name: worked out for every channel each time the list changes.
    private val NONE = Tag("", "", false)
    private val seenWords = java.util.concurrent.ConcurrentHashMap<String, Tag>()
    private val seenTags = java.util.concurrent.ConcurrentHashMap<String, Tag>()

    private fun cached(text: String, useWords: Boolean): Tag? {
        val map = if (useWords) seenWords else seenTags
        val t = map.getOrPut(text) { tagOf(text, useWords) ?: NONE }
        return if (t === NONE) null else t
    }

    /** Works out every channel's language ahead (call off the main thread). */
    fun warm(channels: Collection<Channel>) {
        for (c in channels) tagOf(c)
    }

    /** A film or series: from its category, else a tag at the start of its title (not words in it – "The African Queen" is English). */
    fun tagOfItem(category: String, name: String): Tag? = tagOf(category) ?: tagOf(name, useWords = false)

    /** Flag for two-letter country codes (🇬🇧 for UK). */
    fun flag(code: String): String {
        val c = if (code == "UK") "GB" else code
        if (c.length != 2) return "🌐"
        return c.uppercase(Locale.ROOT).map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    const val NONE = "-"

    /** Every tag Tuner knows that isn't English. */
    val nonEnglish: Set<String> get() = tags.values.filter { !it.english }.map { it.code }.toSet()
}
