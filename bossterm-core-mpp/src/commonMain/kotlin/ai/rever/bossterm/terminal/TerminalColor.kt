package ai.rever.bossterm.terminal

import ai.rever.bossterm.core.Color

/**
 * @author traff
 */
class TerminalColor private constructor(colorIndex: Int, color: Color?, colorSupplier: (() -> Color?)?) {
    val colorIndex: Int
    private val myColor: Color?
    private val myColorSupplier: (() -> Color?)?

    constructor(colorIndex: Int) : this(colorIndex, null, null)

    constructor(r: Int, g: Int, b: Int) : this(-1, Color(r, g, b), null)

    constructor(colorSupplier: () -> Color?) : this(-1, null, colorSupplier)

    init {
        if (colorIndex != -1) {
            assert(color == null)
            assert(colorSupplier == null)
        } else if (color != null) {
            assert(colorSupplier == null)
        } else {
            checkNotNull(colorSupplier)
        }
        this.colorIndex = colorIndex
        myColor = color
        myColorSupplier = colorSupplier
    }

    val isIndexed: Boolean
        get() = this.colorIndex != -1

    fun toColor(): Color {
        require(!this.isIndexed) { "Color is indexed color so a palette is needed" }
        return myColor ?: myColorSupplier?.invoke() ?: throw IllegalStateException("Color must be non-null")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is TerminalColor) return false
        return this.colorIndex == other.colorIndex && myColor == other.myColor
    }

    override fun hashCode(): Int {
        return 31 * colorIndex + (myColor?.hashCode() ?: 0)
    }

    companion object {
        /**
         * Pre-allocated cache for indexed colors 0-255.
         * Eliminates object allocation for every color lookup.
         */
        private val INDEXED_COLOR_CACHE: Array<TerminalColor> = Array(256) { TerminalColor(it) }

        val BLACK: TerminalColor = INDEXED_COLOR_CACHE[0]
        val WHITE: TerminalColor = INDEXED_COLOR_CACHE[15]

        fun index(colorIndex: Int): TerminalColor {
            return if (colorIndex in 0..255) {
                INDEXED_COLOR_CACHE[colorIndex]
            } else {
                TerminalColor(colorIndex)
            }
        }

        fun rgb(r: Int, g: Int, b: Int): TerminalColor {
            return TerminalColor(r, g, b)
        }

        fun fromColor(color: Color?): TerminalColor? {
            if (color == null) return null
            return rgb(color.red, color.green, color.blue)
        }
    }
}
