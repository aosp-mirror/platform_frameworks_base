/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.controller

import android.content.ComponentName
import android.os.Binder
import android.service.controls.Control
import android.service.controls.IControlsSubscription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class StatefulControlSubscriberTest : SysuiTestCase() {

    @Mock
    private lateinit var controller: ControlsController

    @Mock
    private lateinit var subscription: IControlsSubscription

    @Mock
    private lateinit var provider: ControlsProviderLifecycleManager

    @Mock
    private lateinit var control: Control

    private val executor = FakeExecutor(FakeSystemClock())
    private val token = Binder()
    private val badToken = Binder()

    private val TEST_COMPONENT = ComponentName("TEST_PKG", "TEST_CLS_1")

    private lateinit var scs: StatefulControlSubscriber

    private val REQUEST_LIMIT = 5L

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(provider.componentName).thenReturn(TEST_COMPONENT)
        `when`(provider.token).thenReturn(token)
        scs = StatefulControlSubscriber(controller, provider, executor, REQUEST_LIMIT)
    }

    @Test
    fun testOnSubscribe() {
        scs.onSubscribe(token, subscription)

        executor.runAllReady()
        verify(provider).startSubscription(subscription, REQUEST_LIMIT)
    }

    @Test
    fun testOnSubscribe_badToken() {
        scs.onSubscribe(badToken, subscription)

        executor.runAllReady()
        verify(provider, never()).startSubscription(subscription, REQUEST_LIMIT)
    }

    @Test
    fun testOnNext() {
        scs.onSubscribe(token, subscription)
        scs.onNext(token, control)

        executor.runAllReady()
        verify(controller).refreshStatus(TEST_COMPONENT, control)
    }

    @Test
    fun testOnNext_multiple() {
        scs.onSubscribe(token, subscription)
        scs.onNext(token, control)
        scs.onNext(token, control)
        scs.onNext(token, control)

        executor.runAllReady()
        verify(controller, times(3)).refreshStatus(TEST_COMPONENT, control)
    }

    @Test
    fun testOnNext_noRefreshBeforeSubscribe() {
        scs.onNext(token, control)

        executor.runAllReady()
        verify(controller, never()).refreshStatus(TEST_COMPONENT, control)
    }

    @Test
    fun testOnNext_noRefreshAfterCancel() {
        scs.onSubscribe(token, subscription)
        executor.runAllReady()

        scs.cancel()
        scs.onNext(token, control)

        executor.runAllReady()
        verify(controller, never()).refreshStatus(TEST_COMPONENT, control)
    }

    @Test
    fun testOnNext_noRefreshAfterError() {
        scs.onSubscribe(token, subscription)
        scs.onError(token, "Error")
        scs.onNext(token, control)

        executor.runAllReady()
        verify(controller, never()).refreshStatus(TEST_COMPONENT, control)
    }

    @Test
    fun testOnNext_noRefreshAfterComplete() {
        scs.onSubscribe(token, subscription)
        scs.onComplete(token)
        scs.onNext(token, control)

        executor.runAllReady()
        verify(controller, never()).refreshStatus(TEST_COMPONENT, control)
    }
}
