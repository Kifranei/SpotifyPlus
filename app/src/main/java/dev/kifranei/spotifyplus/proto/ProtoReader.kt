package dev.kifranei.spotifyplus.proto

/**
 * Minimal protobuf wire reader (no schema, no Android deps → unit-testable).
 * Only what the Spotify color-lyrics / metadata responses need.
 */
class ProtoField(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray?) {
    fun string(): String = String(bytes ?: ByteArray(0), Charsets.UTF_8)
    fun int(): Int = varint.toInt()
    fun bool(): Boolean = varint != 0L
}

object ProtoReader {
    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LEN = 2
    const val WIRE_FIXED32 = 5

    /** Parses one message level. Throws [IllegalArgumentException] on malformed input. */
    fun parse(data: ByteArray): List<ProtoField> {
        val out = ArrayList<ProtoField>()
        var pos = 0
        fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                require(pos < data.size) { "truncated varint" }
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 70) { "varint too long" }
            }
        }
        while (pos < data.size) {
            val tag = readVarint().toInt()
            val number = tag ushr 3
            require(number > 0) { "bad field number" }
            when (val wt = tag and 7) {
                WIRE_VARINT -> out += ProtoField(number, wt, readVarint(), null)
                WIRE_FIXED64 -> {
                    require(pos + 8 <= data.size) { "truncated fixed64" }
                    out += ProtoField(number, wt, 0, data.copyOfRange(pos, pos + 8)); pos += 8
                }
                WIRE_FIXED32 -> {
                    require(pos + 4 <= data.size) { "truncated fixed32" }
                    out += ProtoField(number, wt, 0, data.copyOfRange(pos, pos + 4)); pos += 4
                }
                WIRE_LEN -> {
                    val len = readVarint()
                    require(len in 0..(data.size - pos).toLong()) { "truncated length-delimited" }
                    out += ProtoField(number, wt, 0, data.copyOfRange(pos, pos + len.toInt()))
                    pos += len.toInt()
                }
                else -> throw IllegalArgumentException("unsupported wire type $wt")
            }
        }
        return out
    }
}

/** Tiny encoder used by tests and by request builders. */
object ProtoWriter {
    fun varint(v: Long): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) { o.write(x.toInt()); break }
            o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
        }
        return o.toByteArray()
    }
    fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    fun int(field: Int, v: Long) = tag(field, 0) + varint(v)
    fun bytes(field: Int, b: ByteArray) = tag(field, 2) + varint(b.size.toLong()) + b
    fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))
}
