package com.android.systemui.statusbar.notification.stack

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for {@link MediaContainView}.
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaContainerViewTest : SysuiTestCase() {

    lateinit var mediaContainerView : MediaContainerView

    @Before
    fun setUp() {
        mediaContainerView = LayoutInflater.from(context).inflate(
                R.layout.keyguard_media_container, null, false) as MediaContainerView
    }

    @Test
    fun testUpdateClipping_updatesClipHeight() {
        assertTrue(mediaContainerView.clipHeight == 0)

        mediaContainerView.actualHeight = 10
        mediaContainerView.updateClipping()
        assertTrue(mediaContainerView.clipHeight == 10)
    }
}