package com.android.keyguard

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import android.testing.TestableLooper.RunWithLooper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.children
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
class KeyguardStatusViewTest : SysuiTestCase() {

    private lateinit var keyguardStatusView: KeyguardStatusView
    private val mediaView: View
        get() = keyguardStatusView.requireViewById(R.id.status_view_media_container)
    private val statusViewContainer: ViewGroup
        get() = keyguardStatusView.requireViewById(R.id.status_view_container)
    private val childrenExcludingMedia
        get() = statusViewContainer.children.filter { it != mediaView }

    @Before
    fun setUp() {
        keyguardStatusView =
            LayoutInflater.from(context).inflate(R.layout.keyguard_status_view, /* root= */ null)
                as KeyguardStatusView
    }

    @Test
    fun setChildrenTranslationYExcludingMediaView_mediaViewIsNotTranslated() {
        val translationY = 1234f

        keyguardStatusView.setChildrenTranslationY(translationY, /* excludeMedia= */ true)

        assertThat(mediaView.translationY).isEqualTo(0)

        childrenExcludingMedia.forEach { assertThat(it.translationY).isEqualTo(translationY) }
    }

    @Test
    fun setChildrenTranslationYIncludeMediaView() {
        val translationY = 1234f

        keyguardStatusView.setChildrenTranslationY(translationY, /* excludeMedia= */ false)

        statusViewContainer.children.forEach { assertThat(it.translationY).isEqualTo(translationY) }
    }
}
