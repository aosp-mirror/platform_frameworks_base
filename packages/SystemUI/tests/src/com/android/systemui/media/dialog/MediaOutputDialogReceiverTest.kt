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

package com.android.systemui.media.dialog

import android.content.Intent
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest

import com.android.settingslib.media.MediaOutputConstants
import com.android.systemui.SysuiTestCase

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class MediaOutputDialogReceiverTest : SysuiTestCase() {

    @Mock
    private lateinit var mediaOutputDialogFactory: MediaOutputDialogFactory
    @Mock
    private lateinit var mediaOutputBroadcastDialogFactory: MediaOutputBroadcastDialogFactory

    private lateinit var receiver: MediaOutputDialogReceiver
    private lateinit var intent: Intent

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        receiver = MediaOutputDialogReceiver(
                mediaOutputDialogFactory, mediaOutputBroadcastDialogFactory)
    }

    @Test
    fun onReceive_intentWithPackageName_LaunchBroadcastDialog() {
        intent = Intent(MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_BROADCAST_DIALOG).apply {
            putExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME, "test_pkg") }

        receiver.onReceive(mContext, intent)

        verify(mediaOutputBroadcastDialogFactory, times(1)).create(anyString(), anyBoolean(), any())
    }

    @Test
    fun onReceive_intentWithoutPackageName_doNotLaunchBroadcastDialog() {
        intent = Intent(MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_BROADCAST_DIALOG).apply {
            putExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME, "") }

        receiver.onReceive(mContext, intent)

        verify(mediaOutputBroadcastDialogFactory, never()).create(anyString(), anyBoolean(), any())
    }
}