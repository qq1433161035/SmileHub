package com.wbhub.testapp;

/**
 * 纯 Java 部分：循环、递归和字符串拼接，供 jadx 反编译验证。
 */
public final class JavaPart {

    private JavaPart() {
    }

    public static String report() {
        int sum = 0;
        for (int i = 1; i <= 100; i++) {
            sum += i;
        }
        return "Java 计算：sum(1..100) = " + sum
                + "\nfib(20) = " + fib(20)
                + "\n当前线程：" + Thread.currentThread().getName()
                + "\nJava 部分版本：" + version();
    }

    static long fib(int n) {
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }

    private static String version() {
        return "java-" + System.getProperty("java.vm.version", "unknown");
    }
}
