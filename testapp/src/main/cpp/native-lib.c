#include <jni.h>
#include <stdio.h>

/*
 * 测试样本的 C 部分。
 *
 * nativeReport / nativeSum 由 Java 调用；wb_test_marker 不是 JNI 入口，
 * 保留它可以直接用 so_disassemble 按符号名反汇编。
 */
static int sum_to(int n) {
    int sum = 0;
    for (int i = 1; i <= n; i++) {
        sum += i;
    }
    return sum;
}

__attribute__((visibility("default")))
int wb_test_marker(int seed) {
    return sum_to(seed) * 2;
}

JNIEXPORT jint JNICALL
Java_com_wbhub_testapp_MainActivity_nativeSum(JNIEnv *env, jobject thiz, jint n) {
    (void) env;
    (void) thiz;
    return sum_to(n);
}

JNIEXPORT jstring JNICALL
Java_com_wbhub_testapp_MainActivity_nativeReport(JNIEnv *env, jobject thiz) {
    (void) thiz;
    char buffer[192];
    snprintf(buffer, sizeof(buffer),
             "C 计算：sum(1..100) = %d\nABI: %s\nmarker(10) = %d",
             sum_to(100),
#if defined(__aarch64__)
             "arm64-v8a",
#else
             "unknown",
#endif
             wb_test_marker(10));
    return (*env)->NewStringUTF(env, buffer);
}
