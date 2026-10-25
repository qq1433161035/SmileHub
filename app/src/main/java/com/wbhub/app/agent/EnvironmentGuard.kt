package com.wbhub.app.agent

import android.content.Context
import com.wbhub.app.storage.AppStorage

/**
 * 环境变更拦截。
 *
 * 「允许所有」的本意是省掉逐条命令的确认，但装工具链、往 PATH 里塞可执行文件、
 * 改写工具目录这类操作会改变整个运行环境，出问题不是一次任务失败那么简单。
 * 所以它们单独走一道闸：只要 [enabled]，无论审批模式是什么都要问一次。
 *
 * 设置页的开关语义是「允许改动运行环境」：打开才放行，关闭（默认）就拦截。
 * 判断基于命令文本与目标路径的启发式规则，宁可多问，不可放过。
 */
object EnvironmentGuard {

    /** 守卫是否生效：用户没有明确打开「允许改动运行环境」时就拦截。 */
    fun enabled(context: Context): Boolean =
        !AppStorage.prefs(context, "tooling").getBoolean("allow_env_change", false)

    fun isEnvironmentChange(name: String, args: Map<String, Any?>): Boolean = when (name) {
        // 下载本身就是引入外部二进制的最短路径。
        "download" -> true
        "bash" -> {
            val command = string(args, "command")
            SHELL_PATTERNS.any { it.containsMatchIn(command) }
        }
        "python" -> {
            val code = string(args, "code")
            val path = string(args, "path")
            PYTHON_PATTERNS.any { it.containsMatchIn(code) } || TOOL_PATH.containsMatchIn(path)
        }
        "write", "edit" -> TOOL_PATH.containsMatchIn(string(args, "path"))
        else -> false
    }

    private fun string(args: Map<String, Any?>, key: String): String =
        (args[key] as? String).orEmpty()

    /** 包管理器、下载器、编译器、解包与提权安装。 */
    private val SHELL_PATTERNS = listOf(
        Regex("\\b(curl|wget)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bgit\\s+clone\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(apt|apt-get|dpkg)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpip3?\\s+install\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(npm|yarn|pnpm)\\s+(install|add)\\b", RegexOption.IGNORE_CASE),
        // 编译器不止 gcc/clang：模型已经用过 tcc，别名和冷门编译器也要算进来。
        Regex(
            "\\b(tcc|cc|gcc|g\\+\\+|clang|clang\\+\\+|zig|rustc|javac|kotlinc|" +
                "nasm|yasm|ninja|meson|bazel)\\b",
            RegexOption.IGNORE_CASE,
        ),
        Regex("\\b(make|cmake|ndk-build|gradle|configure)\\b", RegexOption.IGNORE_CASE),
        Regex("\\btar\\s+-?[a-z]*x[a-z]*\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(unzip|unxz)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bchmod\\s+([0-7]{3,4}|\\+x)\\b", RegexOption.IGNORE_CASE),
        // 往系统目录/临时可执行目录搬文件，或把输出重定向到那里。
        Regex(
            "\\b(install|cp|mv|ln|mkdir|chown)\\b[^\\n]*(/data/local/tmp|/data/adb|/data/data/com\\.termux)",
            RegexOption.IGNORE_CASE,
        ),
        Regex(">>?[^\\n]*/data/(local/tmp|adb)", RegexOption.IGNORE_CASE),
        // 工作区 tools/ 与私有 bin/ 同样是“能被 PATH 直接执行”的位置。
        Regex(
            "\\b(install|cp|mv|ln|mkdir|chown|cat|tee|dd)\\b[^\\n]*" +
                "(/storage/emulated/0/WBHub/tools|/files/WBHub/(bin|tools|py_modules|site-packages))",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            // 重定向写入同样算改动环境。
            ">>?[^\\n]*(/storage/emulated/0/WBHub/tools|/files/WBHub/(bin|tools|py_modules|site-packages))",
            RegexOption.IGNORE_CASE,
        ),
    )

    /** Python 里等价的下载、解包、调用子进程与写权限。 */
    private val PYTHON_PATTERNS = listOf(
        Regex(
            "\\b(urllib|requests|httpx|socket|subprocess|os\\.system|os\\.exec\\w*|" +
                "shutil|tarfile|zipfile|lzma|gzip|bz2|chmod|chown)\\b",
        ),
    )

    /** 工具目录与系统可执行目录。 */
    private val TOOL_PATH = Regex(
        "(^|/)(tools|bin|py_modules|site-packages|libs)/|/data/local/tmp(/|$)|" +
            "^/?(system|vendor|data/adb)(/|$)",
        RegexOption.IGNORE_CASE,
    )
}
