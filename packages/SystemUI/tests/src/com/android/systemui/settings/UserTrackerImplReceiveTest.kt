package com.android.systemui.settings

import android.app.IActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.UserInfo
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import androidx.concurrent.futures.DirectExecutor
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(Parameterized::class)
class UserTrackerImplReceiveTest : SysuiTestCase() {

    companion object {

        @JvmStatic
        @Parameterized.Parameters
        fun data(): Iterable<String> =
            listOf(
                Intent.ACTION_LOCALE_CHANGED,
                Intent.ACTION_USER_INFO_CHANGED,
                Intent.ACTION_MANAGED_PROFILE_AVAILABLE,
                Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE,
                Intent.ACTION_MANAGED_PROFILE_ADDED,
                Intent.ACTION_MANAGED_PROFILE_REMOVED,
                Intent.ACTION_MANAGED_PROFILE_UNLOCKED
            )
    }

    private val executor: Executor = DirectExecutor.INSTANCE

    @Mock private lateinit var context: Context
    @Mock private lateinit var userManager: UserManager
    @Mock private lateinit var iActivityManager: IActivityManager
    @Mock(stubOnly = true) private lateinit var dumpManager: DumpManager
    @Mock(stubOnly = true) private lateinit var handler: Handler

    @Parameterized.Parameter lateinit var intentAction: String
    @Mock private lateinit var callback: UserTracker.Callback
    @Captor private lateinit var captor: ArgumentCaptor<List<UserInfo>>

    private val fakeFeatures = FakeFeatureFlagsClassic()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var tracker: UserTrackerImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(context.user).thenReturn(UserHandle.SYSTEM)
        `when`(context.createContextAsUser(ArgumentMatchers.any(), anyInt())).thenReturn(context)
    }

    @Test
    fun callsCallbackAndUpdatesProfilesWhenAnIntentReceived() = runTest {
        `when`(userManager.getProfiles(anyInt())).thenAnswer { invocation ->
            val id = invocation.getArgument<Int>(0)
            val info = UserInfo(id, "", UserInfo.FLAG_FULL)
            val infoProfile =
                UserInfo(
                    id + 10,
                    "",
                    "",
                    UserInfo.FLAG_MANAGED_PROFILE,
                    UserManager.USER_TYPE_PROFILE_MANAGED
                )
            infoProfile.profileGroupId = id
            listOf(info, infoProfile)
        }

        tracker =
            UserTrackerImpl(
                context,
                { fakeFeatures },
                userManager,
                iActivityManager,
                dumpManager,
                this,
                testDispatcher,
                handler
            )
        tracker.initialize(0)
        tracker.addCallback(callback, executor)
        val profileID = tracker.userId + 10

        tracker.onReceive(context, Intent(intentAction))

        verify(callback, times(0)).onUserChanged(anyInt(), any())
        verify(callback, times(1)).onProfilesChanged(capture(captor))
        assertThat(captor.value.map { it.id }).containsExactly(0, profileID)
    }
}
