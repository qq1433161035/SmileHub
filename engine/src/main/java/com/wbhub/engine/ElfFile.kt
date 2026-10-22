package com.wbhub.engine

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal ELF reader for Android shared objects.
 *
 * Only what the analysis tools need is implemented: the header, loadable
 * segments (virtual address to file offset), sections, dynamic symbols and the
 * DT_NEEDED list. Both 32-bit and 64-bit little-endian images are supported.
 */
class ElfFile(val file: File) : Closeable {

    data class Section(
        val index: Int,
        val name: String,
        val type: Int,
        val flags: Long,
        val address: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val entrySize: Long,
    )

    data class Segment(
        val type: Int,
        val flags: Long,
        val offset: Long,
        val address: Long,
        val fileSize: Long,
        val memorySize: Long,
    )

    data class Symbol(
        val name: String,
        val value: Long,
        val size: Long,
        val bind: Int,
        val type: Int,
        val sectionIndex: Int,
    ) {
        val defined: Boolean get() = sectionIndex != 0
    }

    private val raf = RandomAccessFile(file, "r")

    val is64: Boolean
    val littleEndian: Boolean
    val machine: Int
    val elfType: Int
    val entry: Long

    private val sections: List<Section>
    private val segments: List<Segment>

    val symbols: List<Symbol> by lazy { readSymbols() }

    val neededLibraries: List<String> by lazy { readNeeded() }

    init {
        val ident = ByteArray(16)
        raf.readFully(ident)
        require(ident[0] == 0x7f.toByte() && ident[1] == 'E'.code.toByte() &&
            ident[2] == 'L'.code.toByte() && ident[3] == 'F'.code.toByte()) {
            "${file.name} 不是 ELF 文件"
        }
        is64 = ident[4].toInt() == 2
        littleEndian = ident[5].toInt() != 2
        val header = read(0, if (is64) 64 else 52).order(order())
        elfType = header.getShort(16).toInt() and 0xffff
        machine = header.getShort(18).toInt() and 0xffff
        entry = if (is64) header.getLong(24) else header.getInt(24).toLong() and 0xffffffffL
        val programOffset = if (is64) header.getLong(32) else header.getInt(28).toLong() and 0xffffffffL
        val sectionOffset = if (is64) header.getLong(40) else header.getInt(32).toLong() and 0xffffffffL
        val programEntrySize = header.getShort(if (is64) 54 else 42).toInt() and 0xffff
        val programCount = header.getShort(if (is64) 56 else 44).toInt() and 0xffff
        val sectionEntrySize = header.getShort(if (is64) 58 else 46).toInt() and 0xffff
        val sectionCount = header.getShort(if (is64) 60 else 48).toInt() and 0xffff
        val stringSectionIndex = header.getShort(if (is64) 62 else 50).toInt() and 0xffff

        segments = readSegments(programOffset, programEntrySize, programCount)
        sections = readSections(sectionOffset, sectionEntrySize, sectionCount, stringSectionIndex)
    }

    fun sectionAt(address: Long): Section? = sections.firstOrNull {
        it.type != SHT_NOBITS && address >= it.address && address < it.address + it.size
    }

    fun vaddrToOffset(address: Long): Long? {
        val segment = segments.firstOrNull {
            it.type == PT_LOAD && address >= it.address && address < it.address + it.fileSize
        }
        if (segment != null) {
            return segment.offset + (address - segment.address)
        }
        val section = sectionAt(address) ?: return null
        return section.offset + (address - section.address)
    }

    /** Bytes available from [address] inside its segment/section. */
    fun availableAt(address: Long): Long {
        segments.firstOrNull {
            it.type == PT_LOAD && address >= it.address && address < it.address + it.fileSize
        }?.let { return it.address + it.fileSize - address }
        sectionAt(address)?.let { return it.address + it.size - address }
        return 0
    }

    fun readAt(offset: Long, length: Int): ByteArray {
        val safeLength = length.coerceAtLeast(0)
        val buffer = ByteArray(safeLength)
        raf.seek(offset)
        raf.readFully(buffer)
        return buffer
    }

    fun symbol(name: String): Symbol? =
        symbols.firstOrNull { it.defined && it.name == name && it.type == STT_FUNC }
            ?: symbols.firstOrNull { it.defined && it.name == name }
            ?: symbols.firstOrNull { it.name == name }

    fun archName(): String = when (machine) {
        EM_AARCH64 -> "arm64-v8a (AArch64)"
        EM_ARM -> "armeabi-v7a (ARM)"
        EM_X86 -> "x86"
        EM_X86_64 -> "x86_64"
        else -> "machine=$machine"
    }

    fun capstoneArch(): Int = when (machine) {
        EM_AARCH64 -> 0
        EM_ARM -> 1
        EM_X86 -> 2
        EM_X86_64 -> 3
        else -> throw IllegalArgumentException("Capstone 不支持该架构：${archName()}")
    }

    fun describe(): String = buildString {
        appendLine("file: ${file.name} (${file.length()} bytes)")
        appendLine("arch: ${archName()}, ${if (is64) "64-bit" else "32-bit"}, ${if (littleEndian) "little-endian" else "big-endian"}")
        appendLine("type: ${when (elfType) { 1 -> "relocatable"; 2 -> "executable"; 3 -> "shared object"; 4 -> "core"; else -> elfType.toString() }}")
        appendLine("entry: 0x${entry.toString(16)}")
        if (neededLibraries.isNotEmpty()) {
            appendLine("needed: ${neededLibraries.joinToString(", ")}")
        }
        val functions = symbols.filter { it.defined && it.type == STT_FUNC }
        val imports = symbols.filter { !it.defined && it.name.isNotEmpty() }
        appendLine("functions: ${functions.size}, imports: ${imports.size}, sections: ${sections.size}, segments: ${segments.size}")
        appendLine()
        appendLine("# sections")
        sections.filter { it.size > 0 && it.name.isNotEmpty() }.take(64).forEach {
            appendLine("  ${it.name.padEnd(20)} addr=0x${it.address.toString(16)} size=0x${it.size.toString(16)}")
        }
        if (functions.isNotEmpty()) {
            appendLine()
            appendLine("# exports (first 80)")
            functions.sortedBy { it.value }.take(80).forEach {
                appendLine("  0x${it.value.toString(16)} ${it.name}")
            }
        }
        if (imports.isNotEmpty()) {
            appendLine()
            appendLine("# imports (first 80)")
            imports.map { it.name }.distinct().sorted().take(80).forEach { appendLine("  $it") }
        }
    }

    override fun close() {
        runCatching { raf.close() }
    }

    // ------------------------------------------------------------------ //

    private fun readSegments(offset: Long, entrySize: Int, count: Int): List<Segment> {
        if (offset <= 0 || entrySize <= 0 || count <= 0) return emptyList()
        return (0 until count).map { i ->
            val header = read(offset + i.toLong() * entrySize, entrySize).order(order())
            if (is64) {
                Segment(
                    type = header.getInt(0),
                    flags = header.getInt(4).toLong() and 0xffffffffL,
                    offset = header.getLong(8),
                    address = header.getLong(16),
                    fileSize = header.getLong(32),
                    memorySize = header.getLong(40),
                )
            } else {
                Segment(
                    type = header.getInt(0),
                    flags = header.getInt(24).toLong() and 0xffffffffL,
                    offset = header.getInt(4).toLong() and 0xffffffffL,
                    address = header.getInt(8).toLong() and 0xffffffffL,
                    fileSize = header.getInt(16).toLong() and 0xffffffffL,
                    memorySize = header.getInt(20).toLong() and 0xffffffffL,
                )
            }
        }
    }

    private fun readSections(
        offset: Long,
        entrySize: Int,
        count: Int,
        stringSectionIndex: Int,
    ): List<Section> {
        if (offset <= 0 || entrySize <= 0 || count <= 0) return emptyList()
        val raw = (0 until count).map { i ->
            val header = read(offset + i.toLong() * entrySize, entrySize).order(order())
            if (is64) {
                Section(
                    index = i,
                    name = "",
                    type = header.getInt(4),
                    flags = header.getLong(8),
                    address = header.getLong(16),
                    offset = header.getLong(24),
                    size = header.getLong(32),
                    link = header.getInt(40),
                    entrySize = header.getLong(56),
                )
            } else {
                Section(
                    index = i,
                    name = "",
                    type = header.getInt(4),
                    flags = header.getInt(8).toLong() and 0xffffffffL,
                    address = header.getInt(12).toLong() and 0xffffffffL,
                    offset = header.getInt(16).toLong() and 0xffffffffL,
                    size = header.getInt(20).toLong() and 0xffffffffL,
                    link = header.getInt(24),
                    entrySize = header.getInt(36).toLong() and 0xffffffffL,
                )
            }
        }
        val names = raw.getOrNull(stringSectionIndex)?.let { stringTable(it) } ?: ByteArray(0)
        return raw.map { section ->
            section.copy(name = readCString(names, sectionNameIndex(offset, entrySize, section.index)))
        }
    }

    private fun sectionNameIndex(offset: Long, entrySize: Int, index: Int): Int {
        val header = read(offset + index.toLong() * entrySize, 4).order(order())
        return header.getInt(0)
    }

    private fun readSymbols(): List<Symbol> {
        val result = mutableListOf<Symbol>()
        for (section in sections) {
            if (section.type != SHT_SYMTAB && section.type != SHT_DYNSYM) continue
            val names = sections.getOrNull(section.link)?.let { stringTable(it) } ?: continue
            val entrySize = if (section.entrySize > 0) {
                section.entrySize.toInt()
            } else if (is64) 24 else 16
            if (entrySize <= 0) continue
            val count = (section.size / entrySize).toInt()
            for (i in 0 until count) {
                val entry = read(section.offset + i.toLong() * entrySize, entrySize).order(order())
                val nameIndex = entry.getInt(0)
                val info = entry.get(4).toInt() and 0xff
                val sectionIndex = entry.getShort(6).toInt() and 0xffff
                val value: Long
                val size: Long
                if (is64) {
                    value = entry.getLong(8)
                    size = entry.getLong(16)
                } else {
                    value = entry.getInt(4).toLong() and 0xffffffffL
                    size = entry.getInt(8).toLong() and 0xffffffffL
                }
                val name = readCString(names, nameIndex)
                if (name.isEmpty()) continue
                result += Symbol(
                    name = name,
                    value = value,
                    size = size,
                    bind = info ushr 4,
                    type = info and 0xf,
                    sectionIndex = sectionIndex,
                )
            }
        }
        return result
    }

    private fun readNeeded(): List<String> {
        val dynamic = sections.firstOrNull { it.type == SHT_DYNAMIC } ?: return emptyList()
        val names = sections.getOrNull(dynamic.link)?.let { stringTable(it) } ?: return emptyList()
        val entrySize = if (dynamic.entrySize > 0) dynamic.entrySize.toInt() else if (is64) 16 else 8
        val count = (dynamic.size / entrySize).toInt()
        val result = mutableListOf<String>()
        for (i in 0 until count) {
            val entry = read(dynamic.offset + i.toLong() * entrySize, entrySize).order(order())
            val tag = if (is64) entry.getLong(0) else entry.getInt(0).toLong() and 0xffffffffL
            val value = if (is64) entry.getLong(8) else entry.getInt(4).toLong() and 0xffffffffL
            if (tag == 0L) break
            if (tag == DT_NEEDED) {
                readCString(names, value.toInt()).takeIf { it.isNotEmpty() }?.let { result += it }
            }
        }
        return result
    }

    private fun stringTable(section: Section): ByteArray {
        if (section.type == SHT_NOBITS || section.size <= 0) return ByteArray(0)
        return readAt(section.offset, section.size.coerceAtMost(MAX_STRING_TABLE).toInt())
    }

    private fun readCString(table: ByteArray, offset: Int): String {
        if (offset < 0 || offset >= table.size) return ""
        var end = offset
        while (end < table.size && table[end] != 0.toByte()) end++
        return String(table, offset, end - offset, Charsets.UTF_8)
    }

    private fun read(offset: Long, length: Int): ByteBuffer {
        val bytes = ByteArray(length)
        raf.seek(offset)
        raf.readFully(bytes)
        return ByteBuffer.wrap(bytes)
    }

    private fun order(): ByteOrder = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

    companion object {
        private const val PT_LOAD = 1
        private const val SHT_SYMTAB = 2
        private const val SHT_DYNAMIC = 6
        private const val SHT_NOBITS = 8
        private const val SHT_DYNSYM = 11
        private const val DT_NEEDED = 1L
        private const val STT_FUNC = 2

        private const val EM_ARM = 40
        private const val EM_X86 = 3
        private const val EM_X86_64 = 62
        private const val EM_AARCH64 = 183

        private const val MAX_STRING_TABLE = 4L * 1024 * 1024
    }
}
