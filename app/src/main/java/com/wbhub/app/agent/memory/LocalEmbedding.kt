package com.wbhub.app.agent.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/**
 * 本地向量化：字符级特征哈希，不依赖模型文件、网络或 native 库。
 *
 * 上游只有 Chat 接口，没有 `/embeddings`，所以默认方案必须在设备上完成。
 * 对包名、路径、类名、错误码这类标识符，保留 `/ . _ -` 的 ASCII 分词比
 * 通用分词器更可靠；中文用相邻两字 bigram。
 */
object LocalEmbedding {

    const val DIM = 512
    const val MODEL = "local-hash-v1"

    private val whitespace = Regex("\\s+")

    fun embed(text: String): FloatArray {
        val tokens = tokens(text)
        if (tokens.isEmpty()) return FloatArray(DIM)
        val counts = HashMap<String, Int>(tokens.size)
        tokens.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val vector = FloatArray(DIM)
        for ((token, count) in counts) {
            val hash = token.hashCode()
            val index = (hash and 0x7fffffff) % DIM
            // 符号用第二个混合值决定，避免与桶下标共用同一位导致成对抵消。
            val secondary = (hash * 0x9E3779B1.toInt()) xor (hash ushr 16)
            val sign = if ((secondary and 1) == 0) 1f else -1f
            vector[index] += sign * (1.0 + ln(count.toDouble())).toFloat()
        }
        var norm = 0.0
        for (value in vector) norm += value * value
        if (norm <= 0.0) return vector
        val scale = (1.0 / kotlin.math.sqrt(norm)).toFloat()
        for (i in vector.indices) vector[i] *= scale
        return vector
    }

    /** 归一化后的分词结果，检索时用于词法覆盖率。 */
    fun tokens(text: String): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()
        val result = ArrayList<String>(normalized.length / 3)
        var i = 0
        while (i < normalized.length) {
            val ch = normalized[i]
            if (isCjk(ch)) {
                var end = i + 1
                while (end < normalized.length && isCjk(normalized[end])) end++
                if (end - i == 1) {
                    result += normalized.substring(i, end)
                } else {
                    for (position in i until end - 1) {
                        result += normalized.substring(position, position + 2)
                    }
                }
                i = end
            } else if (isTokenChar(ch)) {
                var end = i + 1
                while (end < normalized.length && isTokenChar(normalized[end])) end++
                val token = normalized.substring(i, end).trim('.', '/', '-', '_')
                if (token.isNotEmpty()) result += token
                i = end
            } else {
                i++
            }
        }
        return result
    }

    fun cosine(left: FloatArray?, right: FloatArray?): Double {
        if (left == null || right == null || left.size != right.size) return 0.0
        var sum = 0.0
        for (i in left.indices) sum += left[i] * right[i]
        return sum.coerceIn(-1.0, 1.0)
    }

    fun serialize(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    fun deserialize(bytes: ByteArray?): FloatArray? {
        if (bytes == null || bytes.size < 4 || bytes.size % 4 != 0) return null
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val vector = FloatArray(floats.remaining())
        floats.get(vector)
        return vector
    }

    /** 与哈希共用的规范化：小写、全角转半角、压缩空白。 */
    fun normalize(text: String): String {
        val builder = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\u3000' -> builder.append(' ')
                ch.code in 0xFF01..0xFF5E -> builder.append((ch.code - 0xFEE0).toChar())
                else -> builder.append(ch)
            }
        }
        return whitespace.replace(builder.toString().lowercase(), " ").trim()
    }

    private fun isCjk(ch: Char): Boolean =
        ch.code in 0x3400..0x4DBF || ch.code in 0x4E00..0x9FFF || ch.code in 0xF900..0xFAFF

    private fun isTokenChar(ch: Char): Boolean =
        ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '.' || ch == '$' || ch == '/' || ch == '-'
}
