/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class NotifPipelineTest : SysuiTestCase() {

    @Mock private lateinit var notifCollection: NotifCollection
    @Mock private lateinit var shadeListBuilder: ShadeListBuilder
    private lateinit var notifPipeline: NotifPipeline

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        notifPipeline = NotifPipeline(notifCollection, shadeListBuilder)
        whenever(shadeListBuilder.shadeList).thenReturn(listOf(
                NotificationEntryBuilder().setPkg("foo").setId(1).build(),
                NotificationEntryBuilder().setPkg("foo").setId(2).build(),
                group(
                        NotificationEntryBuilder().setPkg("bar").setId(1).build(),
                        NotificationEntryBuilder().setPkg("bar").setId(2).build(),
                        NotificationEntryBuilder().setPkg("bar").setId(3).build(),
                        NotificationEntryBuilder().setPkg("bar").setId(4).build()
                ),
                NotificationEntryBuilder().setPkg("baz").setId(1).build()
        ))
    }

    private fun group(summary: NotificationEntry, vararg children: NotificationEntry): GroupEntry {
        return GroupEntry(summary.key, summary.creationTime).also { group ->
            group.summary = summary
            for (it in children) {
                group.addChild(it)
            }
        }
    }

    @Test
    fun testGetShadeListCount() {
        assertThat(notifPipeline.getShadeListCount()).isEqualTo(7)
    }

    @Test
    fun testGetFlatShadeList() {
        assertThat(notifPipeline.getFlatShadeList().map { it.key }).containsExactly(
                "0|foo|1|null|0",
                "0|foo|2|null|0",
                "0|bar|1|null|0",
                "0|bar|2|null|0",
                "0|bar|3|null|0",
                "0|bar|4|null|0",
                "0|baz|1|null|0"
        ).inOrder()
    }
}
