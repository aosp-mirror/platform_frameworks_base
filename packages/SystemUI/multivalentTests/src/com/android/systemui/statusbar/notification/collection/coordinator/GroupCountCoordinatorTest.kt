/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.statusbar.notification.collection.coordinator

import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderGroupListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener
import com.android.systemui.statusbar.notification.collection.render.NotifGroupController
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.withArgCaptor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations.initMocks

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class GroupCountCoordinatorTest : SysuiTestCase() {
    private lateinit var coordinator: GroupCountCoordinator
    private lateinit var beforeFinalizeFilterListener: OnBeforeFinalizeFilterListener
    private lateinit var afterRenderGroupListener: OnAfterRenderGroupListener

    private lateinit var summaryEntry: NotificationEntry
    private lateinit var childEntry1: NotificationEntry
    private lateinit var childEntry2: NotificationEntry

    @Mock private lateinit var pipeline: NotifPipeline
    @Mock private lateinit var groupController: NotifGroupController

    @Before
    fun setUp() {
        initMocks(this)
        coordinator = GroupCountCoordinator()
        coordinator.attach(pipeline)
        beforeFinalizeFilterListener = withArgCaptor {
            verify(pipeline).addOnBeforeFinalizeFilterListener(capture())
        }
        afterRenderGroupListener = withArgCaptor {
            verify(pipeline).addOnAfterRenderGroupListener(capture())
        }
        summaryEntry = NotificationEntryBuilder().setId(0).build()
        childEntry1 = NotificationEntryBuilder().setId(1).build()
        childEntry2 = NotificationEntryBuilder().setId(2).build()
    }

    @Test
    fun testSetUntruncatedChildCount() {
        val groupEntry = GroupEntryBuilder()
            .setSummary(summaryEntry)
            .setChildren(listOf(childEntry1, childEntry2))
            .build()
        beforeFinalizeFilterListener.onBeforeFinalizeFilter(listOf(groupEntry))
        afterRenderGroupListener.onAfterRenderGroup(groupEntry, groupController)
        verify(groupController).setUntruncatedChildCount(eq(2))
    }
}
