"""Agent 的 python 工具在进程内执行脚本的实现。

Chaquopy 把 sys.stdout 重定向到 logcat，所以这里显式收集输出，
让结果能作为工具返回值回到对话里。

ctypes / cffi 被禁用：它们能在 App 进程里加载任意 .so，参数传错就是原生段错误，
整个应用会被系统直接杀掉（例如 cs_version 传空指针）。这类崩溃 Python 层拦不住，
只能在导入阶段挡掉。
"""

import builtins
import contextlib
import io
import json
import os
import sys
import traceback

_BLOCKED_MODULES = {"ctypes", "cffi", "_ctypes"}
_real_import = builtins.__import__


def _guarded_import(name, globals=None, locals=None, fromlist=(), level=0):
    root = name.split(".")[0]
    if root in _BLOCKED_MODULES:
        raise ImportError(
            "ctypes/cffi 已禁用：在 App 进程内加载原生库一旦崩溃会直接杀死整个应用。"
            "请改用标准库，或通过 bash / 专用工具完成。"
        )
    return _real_import(name, globals, locals, fromlist, level)


builtins.__import__ = _guarded_import


def run(code, workdir="", filename="<wbhub>", argv_json="[]", sys_path_json="[]"):
    """执行一段 Python 代码或脚本文件，返回合并后的 stdout/stderr。

    argv_json 是 JSON 数组字符串：工具调用里的参数会成为 sys.argv[1:]，
    __file__ 指向脚本路径，方便把工作区里的 .py 当插件反复调用。
    sys_path_json 是额外的 import 目录（工作区与私有工具目录）。
    """
    if workdir:
        try:
            os.chdir(workdir)
        except OSError:
            pass
    try:
        extra_paths = json.loads(sys_path_json) if sys_path_json else []
    except ValueError:
        extra_paths = []
    for directory in reversed(extra_paths):
        if directory and os.path.isdir(directory) and directory not in sys.path:
            sys.path.insert(0, directory)
    try:
        argv = json.loads(argv_json) if argv_json else []
    except ValueError:
        argv = []

    buffer = io.StringIO()
    previous_argv = sys.argv
    sys.argv = [filename] + [str(item) for item in argv]
    namespace = {
        "__name__": "__main__",
        "__file__": filename,
    }
    try:
        with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            exec(compile(code, filename, "exec"), namespace)
        return buffer.getvalue()
    except BaseException:
        buffer.write(traceback.format_exc())
        return buffer.getvalue()
    finally:
        sys.argv = previous_argv
