/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os

import android.content.Intent
import android.platform.test.annotations.DisabledOnRavenwood
import android.platform.test.annotations.Presubmit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import com.android.frameworks.coretests.aidl.ITestInterface
import com.android.frameworks.coretests.methodcallerhelperapp.*

import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.intThat
import org.mockito.Mockito.after
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit
import org.mockito.quality.Strictness.STRICT_STUBS

private const val TIMEOUT_DURATION_MS = 2000L
private const val FALSE_NEG_DURATION_MS = 500L
private const val FLAG_ONEWAY = 1
// From ITestInterface.Stub class, these values are package private
private const val TRANSACTION_foo = 1
private const val TRANSACTION_onewayFoo = 2
private const val TRANSACTION_bar = 3

/** Tests functionality of {@link android.os.Binder.onUnhandledException}. */
@DisabledOnRavenwood(reason = "multi-app")
@Presubmit
@RunWith(AndroidJUnit4::class)
class BinderUncaughtExceptionHandlerTest {

    val mContext = InstrumentationRegistry.getInstrumentation().getTargetContext()

    @Rule @JvmField val rule = MockitoJUnit.rule().strictness(STRICT_STUBS)

    @Spy var mInterfaceImpl: ITestImpl = ITestImpl()

    // This subclass is needed for visibility issues (via protected), since the method we are
    // verifying lives on the boot classpath, it is not enough to be in the same package.
    open class ITestImpl : ITestInterface.Stub() {
        override fun onUnhandledException(code: Int, flags: Int, e: Exception?) =
            onUnhandledExceptionVisible(code, flags, e)

        public open fun onUnhandledExceptionVisible(code: Int, flags: Int, e: Exception?) {}

        @Throws(RemoteException::class)
        override open fun foo(x: Int): Int = throw UnsupportedOperationException()

        @Throws(RemoteException::class)
        override open fun onewayFoo(x: Int): Unit = throw UnsupportedOperationException()

        @Throws(RemoteException::class)
        override open fun bar(x: Int): Unit = throw UnsupportedOperationException()
    }

    class OnewayMatcher(private val isOneway: Boolean) : ArgumentMatcher<Int> {
        override fun matches(argument: Int?) =
            (argument!! and FLAG_ONEWAY) == if (isOneway) 1 else 0

        override fun toString() = "Expected oneway: $isOneway"
    }

    @Test
    fun testRegularMethod_ifThrowsRuntimeException_HandlerCalled() {
        val myException = RuntimeException("Test exception")
        doThrow(myException).`when`(mInterfaceImpl).foo(anyInt())

        dispatchActionCall("foo")

        verify(mInterfaceImpl, timeout(TIMEOUT_DURATION_MS))
            .onUnhandledExceptionVisible(
                /* transactionCode = */ eq(TRANSACTION_foo),
                /* flags= */ intThat(OnewayMatcher(false)),
                /* exception= */ eq(myException),
            )
        // No unexpected calls
        verify(mInterfaceImpl).onUnhandledExceptionVisible(anyInt(), anyInt(), any())
    }

    @Test
    fun testRegularMethod_ifThrowsRemoteException_HandlerCalled() {
        val myException = RemoteException("Test exception")
        doThrow(myException).`when`(mInterfaceImpl).foo(anyInt())

        dispatchActionCall("foo")

        verify(mInterfaceImpl, timeout(TIMEOUT_DURATION_MS))
            .onUnhandledExceptionVisible(
                /* transactionCode = */ eq(TRANSACTION_foo),
                /* flags= */ intThat(OnewayMatcher(false)),
                /* exception= */ eq(myException),
            )
        // No unexpected calls
        verify(mInterfaceImpl).onUnhandledExceptionVisible(anyInt(), anyInt(), any())
    }

    @Test
    fun testRegularMethod_ifThrowsSecurityException_HandlerNotCalled() {
        val myException = SecurityException("Test exception")
        doThrow(myException).`when`(mInterfaceImpl).foo(anyInt())

        dispatchActionCall("foo")

        // No unexpected calls
        verify(mInterfaceImpl, after(FALSE_NEG_DURATION_MS).never())
            .onUnhandledExceptionVisible(anyInt(), anyInt(), any())
    }

    @Test
    fun testVoidMethod_ifThrowsRuntimeException_HandlerCalled() {
        val myException = RuntimeException("Test exception")
        doThrow(myException).`when`(mInterfaceImpl).bar(anyInt())

        dispatchActionCall("bar")

        verify(mInterfaceImpl, timeout(TIMEOUT_DURATION_MS))
            .onUnhandledExceptionVisible(
                /* transactionCode = */ eq(TRANSACTION_bar),
                /* flags= */ intThat(OnewayMatcher(false)),
                /* exception= */ eq(myException),
            )
        // No unexpected calls
        verify(mInterfaceImpl).onUnhandledExceptionVisible(anyInt(), anyInt(), any())
    }

    @Test
    fun testVoidMethod_ifThrowsRemoteException_HandlerCalled() {
        val myException = RemoteException("Test exception")
        doThrow(myException).`when`(mInterfaceImpl).bar(anyInt())

        dispatchActionCall("bar")

        verify(mInterfaceImpl, timeout(TIMEOUT_DURATION_MS))
            .onUnhandledExceptionVisible(
                /* transactionCode = */ eq(TRANSACTION_bar),
                /* flags= */ intThat(OnewayMatcher(false)),
                /* exception= */ eq(myException),
            )
        // No unexpected calls
        verify(mInterfaceImpl).onUnhandledExceptionVisible(anyInt(), anyInt(), any())
    }

    @Test
    fun testVoidMethod_ifThrowsSecurityException_HandlerNotCalled() {
        val myException = SecurityException("Test exception")
        doThrow(myException).`when`(mInterfaceImpl).bar(anyInt())

        dispatchActionCall("bar")

        // No unexpected calls
        verify(mInterfaceImpl, after(FALSE_NEG_DURATION_MS).never())
            .onUnhandledExceptionVisible(anyInt(), anyInt(), any())
    }

    @Test
    fun testOnewayMethod_ifThrowsRuntimeException_HandlerCalled() {
        val myException = RuntimeException("Test exception")
        doThrow(myException).doNothing().`when`(mInterfaceImpl).onewayFoo(anyInt())

        dispatchActionCall("onewayFoo")

        verify(mInterfaceImpl, timeout(TIMEOUT_DURATION_MS))
            .onUnhandledExceptionVisible(
                /* transactionCode = */ eq(TRANSACTION_onewayFoo),
                /* flags= */ intThat(OnewayMatcher(true)),
                /* exception= */ eq(myException),
            )
        // No unexpected calls
        verify(mInterfaceImpl).onUnhandledExceptionVisible(anyInt(), anyInt(), any())
    }

    @Test
    fun testOnewayMethod_ifThrowsRemoteException_HandlerCalled() {
        val myException = RemoteException("Test exception")
        doThrow(myException).`when`(mInterfaceImpl).onewayFoo(anyInt())

        dispatchActionCall("onewayFoo")

        verify(mInterfaceImpl, timeout(TIMEOUT_DURATION_MS))
            .onUnhandledExceptionVisible(
                /* transactionCode = */ eq(TRANSACTION_onewayFoo),
                /* flags= */ intThat(OnewayMatcher(true)),
                /* exception= */ eq(myException),
            )
        // No unexpected calls
        verify(mInterfaceImpl).onUnhandledExceptionVisible(anyInt(), anyInt(), any())
    }

    // All exceptions are uncaught for oneway
    @Test
    fun testOnewayMethod_ifThrowsSecurityException_HandlerCalled() {
        val myException = SecurityException("Test exception")
        doThrow(myException).`when`(mInterfaceImpl).onewayFoo(anyInt())

        dispatchActionCall("onewayFoo")

        verify(mInterfaceImpl, timeout(TIMEOUT_DURATION_MS))
            .onUnhandledExceptionVisible(
                /* transactionCode = */ eq(TRANSACTION_onewayFoo),
                /* flags= */ intThat(OnewayMatcher(true)),
                /* exception= */ eq(myException),
            )
        // No unexpected calls
        verify(mInterfaceImpl).onUnhandledExceptionVisible(anyInt(), anyInt(), any())
    }

    private fun dispatchActionCall(methodName: String) =
        Intent(ACTION_CALL_METHOD).apply {
            putExtras(
                Bundle().apply {
                    putBinder(EXTRA_BINDER, mInterfaceImpl as IBinder)
                    putString(EXTRA_METHOD_NAME, methodName)
                }
            )
            setClassName(PACKAGE_NAME, CallMethodsReceiver::class.java.getName())
        }.let { mContext.sendBroadcast(it) }
}
