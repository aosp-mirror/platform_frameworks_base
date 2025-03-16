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
package com.android.systemui.accessibility.extradim

import android.os.Handler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.plugins.ActivityStarter
import javax.inject.Provider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.verify

/** Tests for [ExtraDimDialogManager]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class ExtraDimDialogManagerTest : SysuiTestCase() {
    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    private lateinit var extraDimDialogManager: ExtraDimDialogManager

    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var dialogProvider: Provider<ExtraDimDialogDelegate>
    @Mock private lateinit var dialogTransitionAnimator: DialogTransitionAnimator
    @Mock private lateinit var mainHandler: Handler
    @Captor private lateinit var runnableCaptor: ArgumentCaptor<Runnable>

    @Before
    fun setUp() {
        extraDimDialogManager =
            ExtraDimDialogManager(
                dialogProvider,
                activityStarter,
                dialogTransitionAnimator,
                mainHandler,
            )
    }

    @Test
    fun dismissKeyguardIfNeededAndShowDialog_executeRunnableDismissingKeyguard() {
        extraDimDialogManager.dismissKeyguardIfNeededAndShowDialog()
        verify(mainHandler).post(runnableCaptor.capture())
        runnableCaptor.value.run()
        verify(activityStarter)
            .executeRunnableDismissingKeyguard(
                any(),
                /* cancelAction= */ eq(null),
                /* dismissShade= */ eq(true),
                /* afterKeyguardGone= */ eq(true),
                /* deferred= */ eq(false),
            )
    }
}
