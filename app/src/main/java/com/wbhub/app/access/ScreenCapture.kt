package com.wbhub.app.access

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍截图。
 *
 * 只负责落盘并返回路径：看图交给已有的 `view_image`，两处不必各做一套压缩。
 * `takeScreenshot` 是 API 30+；更低版本没有这条路径，调用方应引导用户走 adb 或 root。
 */
object ScreenCapture {

    /**
     * [code] 让调用方区分失败原因，避免把超时或编码失败都报成“受保护窗口”。
     *
     * 取值：`UNSUPPORTED` / `SECURE` / `TIMEOUT` / `IO` / `FAILED`。
     */
    data class Result(
        val ok: Boolean,
        val file: File?,
        val message: String,
        val code: String = "FAILED",
    )

    private const val TIMEOUT_SECONDS = 8L

    fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun capture(service: AccessibilityService, dir: File): Result {
        if (!available()) {
            return Result(false, null, "系统低于 Android 11，无障碍截图不可用", "UNSUPPORTED")
        }
        dir.mkdirs()
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var failure: String? = null
        var failureCode = "FAILED"

        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        runCatching {
                            bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }
                        runCatching { buffer.close() }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        failureCode = classify(errorCode)
                        failure = "截图失败：$failureCode（code $errorCode）"
                        latch.countDown()
                    }
                },
            )
        }.onFailure {
            return Result(false, null, "发起截图失败：${it.message}", "FAILED")
        }

        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return Result(false, null, "截图超时（${TIMEOUT_SECONDS}s）", "TIMEOUT")
        }
        val shot = bitmap ?: return Result(false, null, failure ?: "截图失败", failureCode)
        val file = File(dir, "ui-${System.currentTimeMillis()}.png")
        return runCatching {
            file.outputStream().use { output ->
                if (!shot.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    return Result(false, null, "编码 PNG 失败", "IO")
                }
            }
            Result(true, file, "已保存 ${shot.width}x${shot.height} 截图")
        }.getOrElse {
            Result(false, null, "保存截图失败：${it.message}", "IO")
        }
    }

    /** 把系统错误码映射成可判断的类别。 */
    private fun classify(errorCode: Int): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "SECURE"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESS"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
        else -> "FAILED"
    }
}
