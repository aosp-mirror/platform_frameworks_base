package com.android.systemui.sensorprivacy

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
@TestableLooper.RunWithLooper
class SensorUseStartedActivityTest : SysuiTestCase() {
    open class SensorUseStartedActivityTestable :
        SensorUseStartedActivity(
            sensorPrivacyController = mock(),
            keyguardStateController = mock(),
            keyguardDismissUtil = mock(),
            bgHandler = mock(),
        )

    @get:Rule val activityRule = ActivityScenarioRule(SensorUseStartedActivityTestable::class.java)

    @Test
    fun onBackPressed_doNothing() {
        activityRule.scenario.onActivity { activity ->
            assertThat(activity.isFinishing).isFalse()

            activity.onBackPressed()

            assertThat(activity.isFinishing).isFalse()
        }
    }
}
