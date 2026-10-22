package com.wbhub.engine

/**
 * JNI bridge to the bundled Capstone build (`libwbcapstone.so`).
 *
 * Returns one instruction per line: `0x<address>\t<bytes>\t<mnemonic> <ops>`.
 */
internal object NativeDisasm {

    init {
        System.loadLibrary("wbcapstone")
    }

    /**
     * @param arch 0 = arm64, 1 = arm, 2 = x86, 3 = x86_64
     */
    @JvmStatic
    external fun disasm(code: ByteArray, address: Long, count: Int, arch: Int): String?
}
