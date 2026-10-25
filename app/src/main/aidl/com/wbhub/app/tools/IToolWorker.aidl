package com.wbhub.app.tools;

import com.wbhub.app.tools.IToolCallback;

/**
 * 工具执行进程的接口。
 *
 * 除了无障碍相关的五个工具，其余工具都在 :tools 进程里跑；主进程只负责
 * 审批与展示，通过这里发起调用。
 */
interface IToolWorker {

    /** 工具清单，JSON 数组，元素为 {name, risk, timeoutSeconds, definition}。 */
    String specs();

    /**
     * 同步执行一次工具调用，返回结果信封 JSON。
     *
     * 参数与结果都可能超过 Binder 的 1MB 事务上限，所以两边都有落文件的分支：
     * argsRef / textFile / imageFile 是私有目录里的文件路径，调用方读完即删。
     */
    String call(
            String callId,
            String name,
            String argsJson,
            String argsRef,
            String sessionId,
            String approval,
            String workDir,
            boolean useRoot,
            IToolCallback callback);

    /** 请求停止一次调用；执行线程通过轮询标志位协作退出。 */
    void cancel(String callId);

    /** 释放该次调用留下的临时文件。 */
    void release(String callId);

    /** 无线调试状态，JSON {connected, endpoint, message}。 */
    String adbState();

    /** 用配对码配对，JSON {ok, text}。 */
    String adbPair(String code);

    /** 连接已配对过的设备，JSON {ok, text}。 */
    String adbConnect();

    /** 删除本机 ADB 密钥。 */
    void adbResetKeys();
}
