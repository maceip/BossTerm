package ai.rever.bossterm.terminal.util

/**
 * Unicode constants for grapheme cluster handling.
 * Pure Kotlin — no platform dependencies.
 */
object UnicodeConstants {
    // === Variation Selectors ===
    const val VARIATION_SELECTOR_TEXT = 0xFE0E
    const val VARIATION_SELECTOR_EMOJI = 0xFE0F

    // === Zero-Width Joiner ===
    const val ZWJ = 0x200D

    // === Skin Tone Modifiers (Fitzpatrick scale) ===
    val SKIN_TONE_RANGE = 0x1F3FB..0x1F3FF

    // === Gender Symbols ===
    const val FEMALE_SIGN = 0x2640
    const val MALE_SIGN = 0x2642

    // === Regional Indicators (flag emoji) ===
    val REGIONAL_INDICATOR_RANGE = 0x1F1E6..0x1F1FF
    const val REGIONAL_INDICATOR_HIGH_SURROGATE = '\uD83C'
    val REGIONAL_INDICATOR_LOW_SURROGATE_RANGE = 0xDDE6..0xDDFF

    // === Emoji Blocks (Supplementary Plane) ===
    val ENCLOSED_ALPHANUMERIC_SUPPLEMENT_RANGE = 0x1F100..0x1F1FF
    val MISC_SYMBOLS_PICTOGRAPHS_RANGE = 0x1F300..0x1F5FF
    val EMOTICONS_RANGE = 0x1F600..0x1F64F
    val TRANSPORT_MAP_SYMBOLS_RANGE = 0x1F680..0x1F6FF
    val SUPPLEMENTAL_SYMBOLS_RANGE = 0x1F900..0x1F9FF
    val SYMBOLS_PICTOGRAPHS_EXTENDED_A_RANGE = 0x1FA70..0x1FAFF
    val CHESS_SYMBOLS_RANGE = 0x1FA00..0x1FA6F

    // === BMP Emoji ===
    const val WATCH = 0x231A
    const val HOURGLASS = 0x231B
    const val UMBRELLA_RAIN = 0x2614
    const val HOT_BEVERAGE = 0x2615
    val ZODIAC_RANGE = 0x2648..0x2653
    const val WHEELCHAIR = 0x267F
    const val ANCHOR = 0x2693
    const val HIGH_VOLTAGE = 0x26A1
    const val WHITE_CIRCLE = 0x26AA
    const val BLACK_CIRCLE = 0x26AB
    const val SOCCER_BALL = 0x26BD
    const val BASEBALL = 0x26BE
    const val SNOWMAN = 0x26C4
    const val SUN_CLOUD = 0x26C5
    const val OPHIUCHUS = 0x26CE
    const val NO_ENTRY = 0x26D4
    const val CHURCH = 0x26EA
    const val FOUNTAIN = 0x26F2
    const val GOLF = 0x26F3
    const val SAILBOAT = 0x26F5
    const val TENT = 0x26FA
    const val FUEL_PUMP = 0x26FD
    const val CHECK_MARK_BUTTON = 0x2705
    const val SPARKLES = 0x2728
    const val CROSS_MARK = 0x274C
    const val CROSS_MARK_BUTTON = 0x274E
    val QUESTION_EXCLAMATION_RANGE = 0x2753..0x2755
    const val EXCLAMATION_MARK = 0x2757
    val MATH_OPERATORS_EMOJI_RANGE = 0x2795..0x2797
    const val CURLY_LOOP = 0x27B0
    const val DOUBLE_CURLY_LOOP = 0x27BF
    const val CURVED_ARROW_UP = 0x2934
    const val CURVED_ARROW_DOWN = 0x2935
    val DIRECTIONAL_ARROWS_RANGE = 0x2B05..0x2B07
    const val BLACK_LARGE_SQUARE = 0x2B1B
    const val WHITE_LARGE_SQUARE = 0x2B1C
    const val STAR = 0x2B50
    const val HEAVY_CIRCLE = 0x2B55
    const val WAVY_DASH = 0x3030
    const val PART_ALTERNATION = 0x303D
    const val CIRCLED_CONGRATULATION = 0x3297
    const val CIRCLED_SECRET = 0x3299

    // === Combining Characters ===
    val COMBINING_DIACRITICS_RANGE = 0x0300..0x036F
    val COMBINING_MARKS_FOR_SYMBOLS_RANGE = 0x20D0..0x20FF
    val HEBREW_COMBINING_MARKS_RANGE = 0x0591..0x05BD
    val ARABIC_COMBINING_MARKS_RANGE = 0x0610..0x061A

    fun isVariationSelector(codePoint: Int): Boolean =
        codePoint == VARIATION_SELECTOR_TEXT || codePoint == VARIATION_SELECTOR_EMOJI

    fun isVariationSelector(char: Char): Boolean = isVariationSelector(char.code)

    fun isSkinToneModifier(codePoint: Int): Boolean = codePoint in SKIN_TONE_RANGE

    fun isGenderSymbol(codePoint: Int): Boolean =
        codePoint == FEMALE_SIGN || codePoint == MALE_SIGN

    fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in REGIONAL_INDICATOR_RANGE

    fun isRegionalIndicatorHighSurrogate(char: Char): Boolean =
        char == REGIONAL_INDICATOR_HIGH_SURROGATE

    fun isRegionalIndicatorLowSurrogate(charCode: Int): Boolean =
        charCode in REGIONAL_INDICATOR_LOW_SURROGATE_RANGE

    fun isCombiningDiacritic(codePoint: Int): Boolean = codePoint in COMBINING_DIACRITICS_RANGE

    fun isCombiningCharacter(codePoint: Int): Boolean =
        codePoint in COMBINING_DIACRITICS_RANGE ||
        codePoint in COMBINING_MARKS_FOR_SYMBOLS_RANGE ||
        codePoint in HEBREW_COMBINING_MARKS_RANGE ||
        codePoint in ARABIC_COMBINING_MARKS_RANGE

    fun isSupplementaryPlaneEmoji(codePoint: Int): Boolean =
        codePoint in ENCLOSED_ALPHANUMERIC_SUPPLEMENT_RANGE ||
        codePoint in MISC_SYMBOLS_PICTOGRAPHS_RANGE ||
        codePoint in EMOTICONS_RANGE ||
        codePoint in TRANSPORT_MAP_SYMBOLS_RANGE ||
        codePoint in SUPPLEMENTAL_SYMBOLS_RANGE ||
        codePoint in SYMBOLS_PICTOGRAPHS_EXTENDED_A_RANGE ||
        codePoint in CHESS_SYMBOLS_RANGE

    fun isBmpEmoji(codePoint: Int): Boolean = when (codePoint) {
        WATCH, HOURGLASS -> true
        UMBRELLA_RAIN, HOT_BEVERAGE -> true
        in ZODIAC_RANGE -> true
        WHEELCHAIR, ANCHOR, HIGH_VOLTAGE -> true
        WHITE_CIRCLE, BLACK_CIRCLE -> true
        SOCCER_BALL, BASEBALL -> true
        SNOWMAN, SUN_CLOUD -> true
        OPHIUCHUS, NO_ENTRY, CHURCH -> true
        FOUNTAIN, GOLF, SAILBOAT, TENT, FUEL_PUMP -> true
        CHECK_MARK_BUTTON, SPARKLES -> true
        CROSS_MARK, CROSS_MARK_BUTTON -> true
        in QUESTION_EXCLAMATION_RANGE -> true
        EXCLAMATION_MARK -> true
        in MATH_OPERATORS_EMOJI_RANGE -> true
        CURLY_LOOP, DOUBLE_CURLY_LOOP -> true
        CURVED_ARROW_UP, CURVED_ARROW_DOWN -> true
        in DIRECTIONAL_ARROWS_RANGE -> true
        BLACK_LARGE_SQUARE, WHITE_LARGE_SQUARE -> true
        STAR, HEAVY_CIRCLE -> true
        WAVY_DASH, PART_ALTERNATION -> true
        CIRCLED_CONGRATULATION, CIRCLED_SECRET -> true
        else -> false
    }
}
