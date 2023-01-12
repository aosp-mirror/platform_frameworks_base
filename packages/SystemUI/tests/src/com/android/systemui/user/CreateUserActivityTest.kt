package com.android.systemui.user

import android.app.Dialog
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
@TestableLooper.RunWithLooper
class CreateUserActivityTest : SysuiTestCase() {
    open class CreateUserActivityTestable :
        CreateUserActivity(
            /* userCreator = */ mock(),
            /* editUserInfoController = */ mock {
                val dialog: Dialog = mock()
                whenever(
                        createDialog(
                            /* activity = */ nullable(),
                            /* activityStarter = */ nullable(),
                            /* oldUserIcon = */ nullable(),
                            /* defaultUserName = */ nullable(),
                            /* title = */ nullable(),
                            /* successCallback = */ nullable(),
                            /* cancelCallback = */ nullable()
                        )
                    )
                    .thenReturn(dialog)
            },
            /* activityManager = */ mock(),
            /* activityStarter = */ mock(),
        )

    @get:Rule val activityRule = ActivityScenarioRule(CreateUserActivityTestable::class.java)

    @Test
    fun onBackPressed_finishActivity() {
        activityRule.scenario.onActivity { activity ->
            assertThat(activity.isFinishing).isFalse()

            activity.onBackPressed()

            assertThat(activity.isFinishing).isTrue()
        }
    }
}
