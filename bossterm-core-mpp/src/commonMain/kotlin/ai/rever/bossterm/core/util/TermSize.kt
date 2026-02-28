package ai.rever.bossterm.core.util

class TermSize(columns: Int, rows: Int) {
    val columns: Int
    val rows: Int

    init {
        require(columns >= 0) { "negative column count: $columns" }
        require(rows >= 0) { "negative row count: $rows" }
        this.columns = columns
        this.rows = rows
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is TermSize) return false
        return this.columns == other.columns && this.rows == other.rows
    }

    override fun hashCode(): Int {
        return 31 * columns + rows
    }

    override fun toString(): String {
        return "columns=${this.columns}, rows=${this.rows}"
    }
}
