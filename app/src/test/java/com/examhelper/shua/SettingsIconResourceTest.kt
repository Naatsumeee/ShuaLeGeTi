package com.examhelper.shua

import android.content.res.XmlResourceParser
import com.examhelper.shua.ui.settings.ABOUT_ICON_RES
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsIconResourceTest {

    @Test
    fun `关于页图标是 Compose 可加载的 vector`() {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(ABOUT_ICON_RES)
        while (parser.eventType != XmlResourceParser.START_TAG) parser.next()

        assertEquals("vector", parser.name)
    }
}
