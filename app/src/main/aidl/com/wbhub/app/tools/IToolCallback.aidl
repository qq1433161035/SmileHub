package com.wbhub.app.tools;

/** 工具执行过程中回报进度；由主进程实现，:tools 进程调用。 */
interface IToolCallback {
    void onProgress(String callId, String message);
}
