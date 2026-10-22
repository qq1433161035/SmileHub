/*
 * Thin JNI bridge to Capstone.
 *
 * The engine module never talks to Capstone directly; it passes raw bytes plus
 * a virtual address and receives one text line per instruction:
 *
 *   0x<address>\t<hex bytes>\t<mnemonic> <operands>
 *
 * Keeping the bridge minimal means the native side has no knowledge of ELF or
 * of the app's data model.
 */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "capstone/capstone.h"

/* Kotlin order: 0 = arm64, 1 = arm, 2 = x86, 3 = x86_64. */
static cs_arch arch_for(jint arch, cs_mode *mode) {
    switch (arch) {
        case 0:
            *mode = CS_MODE_ARM;
            return CS_ARCH_ARM64;
        case 1:
            *mode = CS_MODE_ARM;
            return CS_ARCH_ARM;
        case 2:
            *mode = CS_MODE_32;
            return CS_ARCH_X86;
        default:
            *mode = CS_MODE_64;
            return CS_ARCH_X86;
    }
}

static void append(char **out, size_t *len, size_t *cap, const char *text) {
    size_t need = strlen(text);
    if (*len + need + 1 > *cap) {
        size_t next = (*cap ? *cap * 2 : 4096);
        while (next < *len + need + 1) {
            next *= 2;
        }
        char *grown = (char *) realloc(*out, next);
        if (!grown) {
            return;
        }
        *out = grown;
        *cap = next;
    }
    memcpy(*out + *len, text, need);
    *len += need;
    (*out)[*len] = '\0';
}

JNIEXPORT jstring JNICALL
Java_com_wbhub_engine_NativeDisasm_disasm(JNIEnv *env, jclass clazz,
                                          jbyteArray code, jlong address,
                                          jint count, jint arch) {
    (void) clazz;
    if (!code || count <= 0) {
        return (*env)->NewStringUTF(env, "");
    }
    jsize size = (*env)->GetArrayLength(env, code);
    if (size <= 0) {
        return (*env)->NewStringUTF(env, "");
    }

    jbyte *buffer = (*env)->GetByteArrayElements(env, code, NULL);
    if (!buffer) {
        return NULL;
    }

    cs_mode mode;
    cs_arch cs_arch = arch_for(arch, &mode);
    csh handle;
    if (cs_open(cs_arch, mode, &handle) != CS_ERR_OK) {
        (*env)->ReleaseByteArrayElements(env, code, buffer, JNI_ABORT);
        return NULL;
    }

    cs_insn *insn = NULL;
    size_t found = cs_disasm(handle, (const uint8_t *) buffer, (size_t) size,
                             (uint64_t) address, (size_t) count, &insn);

    char *out = NULL;
    size_t len = 0;
    size_t cap = 0;
    char line[512];
    for (size_t i = 0; i < found; i++) {
        char hex[32] = {0};
        size_t used = 0;
        for (int b = 0; b < insn[i].size && used + 3 < sizeof(hex); b++) {
            used += (size_t) snprintf(hex + used, sizeof(hex) - used, "%02x",
                                      insn[i].bytes[b]);
        }
        snprintf(line, sizeof(line), "0x%llx\t%s\t%s%s%s\n",
                 (unsigned long long) insn[i].address, hex, insn[i].mnemonic,
                 insn[i].op_str[0] ? " " : "", insn[i].op_str);
        append(&out, &len, &cap, line);
    }

    cs_free(insn, found);
    cs_close(&handle);
    (*env)->ReleaseByteArrayElements(env, code, buffer, JNI_ABORT);

    jstring result = (*env)->NewStringUTF(env, out ? out : "");
    free(out);
    return result;
}
