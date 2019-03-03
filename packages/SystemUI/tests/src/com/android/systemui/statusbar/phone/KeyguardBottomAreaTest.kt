package com.android.systemui.statusbar.phone

import androidx.test.filters.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater

import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.KeyguardIndicationController

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class KeyguardBottomAreaTest : SysuiTestCase() {

    @Mock
    private lateinit var mStatusBar: StatusBar
    @Mock
    private lateinit var mKeyguardIndicationController: KeyguardIndicationController
    private lateinit var mKeyguardBottomArea: KeyguardBottomAreaView

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        mKeyguardBottomArea = LayoutInflater.from(mContext).inflate(
                R.layout.keyguard_bottom_area, null, false) as KeyguardBottomAreaView
        mKeyguardBottomArea.setStatusBar(mStatusBar)
        mKeyguardBottomArea.setKeyguardIndicationController(mKeyguardIndicationController)
    }

    @Test
    fun initFrom_doesntCrash() {
        val other = LayoutInflater.from(mContext).inflate(
                R.layout.keyguard_bottom_area, null, false) as KeyguardBottomAreaView

        other.initFrom(mKeyguardBottomArea)
        other.launchVoiceAssist()
        other.onLongClick(null)
    }
}