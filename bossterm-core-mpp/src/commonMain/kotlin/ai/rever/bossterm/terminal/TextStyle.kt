package ai.rever.bossterm.terminal

open class TextStyle(
    val foreground: TerminalColor? = null,
    val background: TerminalColor? = null,
    options: Set<Option> = NO_OPTIONS
) {
    // Immutable options set
    private val myOptions: Set<Option>

    init {
        myOptions = if (options.isEmpty()) NO_OPTIONS else options.toSet()
    }

    fun createEmptyWithColors(): TextStyle {
        return TextStyle(this.foreground, this.background)
    }

    fun hasOption(option: Option?): Boolean {
        return myOptions.contains(option)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is TextStyle) return false
        return this.foreground == other.foreground &&
                this.background == other.background &&
                myOptions == other.myOptions
    }

    override fun hashCode(): Int {
        var result = foreground?.hashCode() ?: 0
        result = 31 * result + (background?.hashCode() ?: 0)
        result = 31 * result + myOptions.hashCode()
        return result
    }

    open fun toBuilder(): Builder {
        return Builder(this)
    }

    enum class Option {
        BOLD,
        ITALIC,
        SLOW_BLINK,
        RAPID_BLINK,
        DIM,
        INVERSE,
        UNDERLINED,
        HIDDEN,
        PROTECTED;

        fun set(options: MutableSet<Option>, `val`: Boolean) {
            if (`val`) {
                options.add(this)
            } else {
                options.remove(this)
            }
        }
    }

    open class Builder {
        private var myForeground: TerminalColor?
        private var myBackground: TerminalColor?
        private val myOptions: MutableSet<Option>

        constructor(textStyle: TextStyle) {
            myForeground = textStyle.foreground
            myBackground = textStyle.background
            myOptions = textStyle.myOptions.toMutableSet()
        }

        constructor() {
            myForeground = null
            myBackground = null
            myOptions = mutableSetOf()
        }

        fun setForeground(foreground: TerminalColor?): Builder {
            myForeground = foreground
            return this
        }

        fun setBackground(background: TerminalColor?): Builder {
            myBackground = background
            return this
        }

        fun setOption(option: Option, `val`: Boolean): Builder {
            option.set(myOptions, `val`)
            return this
        }

        open fun build(): TextStyle {
            return getOrCreate(myForeground, myBackground, myOptions)
        }
    }

    companion object {
        private val NO_OPTIONS: Set<Option> = emptySet()

        val EMPTY: TextStyle = TextStyle()

        /**
         * Thread-safe style interning via platform expect/actual.
         * Only caches styles with indexed colors (0-255) to bound memory.
         */
        private val COMMON_STYLES = HashMap<Int, TextStyle>(128)

        private fun computeKey(fgIndex: Int, bgIndex: Int, optionsBitmask: Int): Int {
            return ((fgIndex + 1) and 0x1FF) or
                   (((bgIndex + 1) and 0x1FF) shl 9) or
                   ((optionsBitmask and 0x1FF) shl 18)
        }

        fun getOrCreate(fg: TerminalColor?, bg: TerminalColor?, options: Set<Option>): TextStyle {
            val fgIndex = if (fg?.isIndexed == true) fg.colorIndex else -1
            val bgIndex = if (bg?.isIndexed == true) bg.colorIndex else -1

            if ((fg != null && !fg.isIndexed) || (bg != null && !bg.isIndexed)) {
                return TextStyle(fg, bg, options)
            }

            if (fgIndex > 255 || bgIndex > 255) {
                return TextStyle(fg, bg, options)
            }

            val optionsBitmask = options.fold(0) { acc, opt -> acc or (1 shl opt.ordinal) }
            val key = computeKey(fgIndex, bgIndex, optionsBitmask)

            return COMMON_STYLES.getOrPut(key) { TextStyle(fg, bg, options) }
        }
    }
}
