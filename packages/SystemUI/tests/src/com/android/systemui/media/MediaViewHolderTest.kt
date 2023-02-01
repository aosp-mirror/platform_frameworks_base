package com.android.systemui.media

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaViewHolderTest : SysuiTestCase() {

    @Test
    fun create_succeeds() {
        val inflater = LayoutInflater.from(context)
        val parent = FrameLayout(context)

        MediaViewHolder.create(inflater, parent)
    }
}
