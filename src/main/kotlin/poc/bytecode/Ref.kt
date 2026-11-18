package poc.bytecode

/**
 * A region of the current frame's byte memory.
 *
 * - int: `Ref(o, 4)`, little-endian two's complement.
 * - str: `Ref(o, 4 + maxStrLen)`, a 4-byte length header followed by `maxStrLen` data bytes.
 */
data class Ref(val offset: Int, val size: Int) {
    override fun toString() = "@$offset"
}

const val INT_SIZE = 4
const val STR_HEADER_SIZE = 4
