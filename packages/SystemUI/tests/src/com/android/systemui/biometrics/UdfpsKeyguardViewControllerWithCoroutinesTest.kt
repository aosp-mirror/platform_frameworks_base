/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityModel
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.BouncerView
import com.android.systemui.keyguard.data.repository.KeyguardBouncerRepository
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerCallbackInteractor
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.phone.KeyguardBouncer
import com.android.systemui.statusbar.phone.KeyguardBypassController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@TestableLooper.RunWithLooper
class UdfpsKeyguardViewControllerWithCoroutinesTest : UdfpsKeyguardViewControllerBaseTest() {
    lateinit var keyguardBouncerRepository: KeyguardBouncerRepository
    @Mock private lateinit var bouncerLogger: TableLogBuffer

    @Before
    override fun setUp() {
        allowTestableLooperAsMainThread() // repeatWhenAttached requires the main thread
        MockitoAnnotations.initMocks(this)
        keyguardBouncerRepository =
            KeyguardBouncerRepository(
                mock(com.android.keyguard.ViewMediatorCallback::class.java),
                TestCoroutineScope(),
                bouncerLogger,
            )
        super.setUp()
    }

    override fun createUdfpsKeyguardViewController(): UdfpsKeyguardViewController? {
        mPrimaryBouncerInteractor =
            PrimaryBouncerInteractor(
                keyguardBouncerRepository,
                mock(BouncerView::class.java),
                mock(Handler::class.java),
                mKeyguardStateController,
                mock(KeyguardSecurityModel::class.java),
                mock(PrimaryBouncerCallbackInteractor::class.java),
                mock(FalsingCollector::class.java),
                mock(DismissCallbackRegistry::class.java),
                mock(KeyguardBypassController::class.java),
                mKeyguardUpdateMonitor
            )
        return createUdfpsKeyguardViewController(
            /* useModernBouncer */ true, /* useExpandedOverlay */
            false
        )
    }

    /** After migration, replaces LockIconViewControllerTest version */
    @Test
    fun testShouldPauseAuthBouncerShowing() =
        runBlocking(IMMEDIATE) {
            // GIVEN view attached and we're on the keyguard
            mController.onViewAttached()
            captureStatusBarStateListeners()
            sendStatusBarStateChanged(StatusBarState.KEYGUARD)

            // WHEN the bouncer expansion is VISIBLE
            val job = mController.listenForBouncerExpansion(this)
            keyguardBouncerRepository.setPrimaryVisible(true)
            keyguardBouncerRepository.setPanelExpansion(KeyguardBouncer.EXPANSION_VISIBLE)
            yield()

            // THEN UDFPS shouldPauseAuth == true
            assertTrue(mController.shouldPauseAuth())

            job.cancel()
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
