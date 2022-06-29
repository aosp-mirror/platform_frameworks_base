package com.android.systemui.statusbar.phone

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.assist.AssistManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.policy.AccessibilityController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.tuner.TunerService
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import java.util.concurrent.Executor

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class KeyguardBottomAreaTest : SysuiTestCase() {

    @Mock
    private lateinit var mCentralSurfaces: CentralSurfaces
    private lateinit var mKeyguardBottomArea: KeyguardBottomAreaView

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        // Mocked dependencies
        mDependency.injectMockDependency(AccessibilityController::class.java)
        mDependency.injectMockDependency(ActivityStarter::class.java)
        mDependency.injectMockDependency(AssistManager::class.java)
        mDependency.injectTestDependency(Executor::class.java, Executor { it.run() })
        mDependency.injectMockDependency(FlashlightController::class.java)
        mDependency.injectMockDependency(KeyguardStateController::class.java)
        mDependency.injectMockDependency(TunerService::class.java)

        mKeyguardBottomArea = LayoutInflater.from(mContext).inflate(
                R.layout.keyguard_bottom_area, null, false) as KeyguardBottomAreaView
        mKeyguardBottomArea.setCentralSurfaces(mCentralSurfaces)
    }

    @Test
    fun initFrom_doesntCrash() {
        val other = LayoutInflater.from(mContext).inflate(R.layout.keyguard_bottom_area,
                null, false) as KeyguardBottomAreaView

        other.initFrom(mKeyguardBottomArea)
        other.launchVoiceAssist()
    }
}