package com.android.systemui.user

import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import android.testing.TestableLooper
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.time.FakeSystemClock
import java.util.function.Consumer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class UserCreatorTest : SysuiTestCase() {
    companion object {
        const val USER_NAME = "abc"
    }

    @Mock
    private lateinit var userCreator: UserCreator
    @Mock
    private lateinit var userManager: UserManager
    private lateinit var mainExecutor: FakeExecutor
    private lateinit var bgExecutor: FakeExecutor
    private lateinit var user: UserInfo

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mainExecutor = FakeExecutor(FakeSystemClock())
        bgExecutor = FakeExecutor(FakeSystemClock())
        userCreator = UserCreator(context, userManager, mainExecutor, bgExecutor)
        user = Mockito.mock(UserInfo::class.java)
        Mockito.`when`(userManager.createUser(USER_NAME, UserManager.USER_TYPE_FULL_SECONDARY, 0))
            .thenReturn(user)
    }

    @Test
    fun testCreateUser_threadingOrder() {
        val successCallback = Mockito.mock(Consumer::class.java)
        val errorCallback = Mockito.mock(Runnable::class.java)

        userCreator.createUser(
            USER_NAME,
            null,
            successCallback as Consumer<UserInfo?>,
            errorCallback)

        verify(userManager, never()).createUser(USER_NAME, UserManager.USER_TYPE_FULL_SECONDARY, 0)
        bgExecutor.runAllReady()
        verify(successCallback, never()).accept(user)
        mainExecutor.runAllReady()
        verify(userManager, never()).setUserIcon(anyInt(), any(Bitmap::class.java))
        bgExecutor.runAllReady()

        verify(userManager).createUser(USER_NAME, UserManager.USER_TYPE_FULL_SECONDARY, 0)
        verify(userManager).setUserIcon(anyInt(), any(Bitmap::class.java))
        verify(successCallback).accept(user)
    }
}
