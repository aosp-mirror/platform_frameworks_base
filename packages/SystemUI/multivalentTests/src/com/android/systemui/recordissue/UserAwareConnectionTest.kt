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

package com.android.systemui.recordissue

import android.content.Context
import android.content.Intent
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserContextProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class UserAwareConnectionTest : SysuiTestCase() {

    @Mock private lateinit var userContextProvider: UserContextProvider
    @Mock private lateinit var mockContext: Context

    private lateinit var underTest: UserAwareConnection

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(userContextProvider.userContext).thenReturn(mockContext)
        whenever(mockContext.bindService(any(), any(), anyInt())).thenReturn(true)
        underTest = UserAwareConnection(userContextProvider, Intent())
    }

    @Test
    fun doBindService_requestToBindToTheService_viaTheCorrectUserContext() {
        underTest.doBind()

        verify(userContextProvider).userContext
    }

    @Test
    fun doBindService_DoesntRequestToBindToTheService_IfAlreadyRequested() {
        underTest.doBind()
        underTest.doBind()
        underTest.doBind()

        verify(userContextProvider, times(1)).userContext
    }
}
