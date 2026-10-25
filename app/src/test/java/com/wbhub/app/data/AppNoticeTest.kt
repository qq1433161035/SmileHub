package com.wbhub.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNoticeTest {

    @Test
    fun parsesPlainNoticeAndAction() {
        val notice = AppNoticeClient.parseNotice(
            """
            {
              "title": "SmileHub 公告",
              "content": "内容",
              "time": "2026-10-25",
              "function": "3#https://example.com/detail",
              "url": ""
            }
            """.trimIndent(),
        )
        assertEquals("SmileHub 公告", notice?.title)
        assertEquals("内容", notice?.content)
        assertEquals("2026-10-25", notice?.time)
        assertEquals("查看详情", notice?.actionText)
        assertEquals("https://example.com/detail", notice?.actionUrl)
    }

    @Test
    fun ignoresUnsupportedFunctionTypes() {
        val notice = AppNoticeClient.parseNotice(
            """{"title":"t","content":"c","time":"2026-10-25","function":"1#12345"}""",
        )
        assertEquals("", notice?.actionText)
        assertEquals("", notice?.actionUrl)
    }

    @Test
    fun rejectsBlankTitle() {
        assertNull(AppNoticeClient.parseNotice("""{"title":"  ","content":"c"}"""))
    }
}
