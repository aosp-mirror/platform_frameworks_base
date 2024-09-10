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
package com.android.systemui.statusbar.notification.collection

import android.app.Flags
import android.os.UserHandle
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.stack.BUCKET_ALERTING
import com.android.systemui.statusbar.notification.stack.BUCKET_FOREGROUND_SERVICE
import com.android.systemui.statusbar.notification.stack.BUCKET_PEOPLE
import com.android.systemui.statusbar.notification.stack.BUCKET_SILENT
import com.android.systemui.statusbar.notification.stack.BUCKET_UNKNOWN
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class SectionStyleProviderTest : SysuiTestCase() {

    @Rule @JvmField public val setFlagsRule = SetFlagsRule()

    @Mock private lateinit var highPriorityProvider: HighPriorityProvider

    @Mock private lateinit var peopleMixedSectioner : NotifSectioner
    @Mock private lateinit var allSilentSectioner : NotifSectioner
    @Mock private lateinit var allAlertingSectioner : NotifSectioner

    private lateinit var sectionStyleProvider: SectionStyleProvider

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        sectionStyleProvider = SectionStyleProvider(highPriorityProvider)

        whenever(peopleMixedSectioner.bucket).thenReturn(BUCKET_PEOPLE);
        whenever(allSilentSectioner.bucket).thenReturn(BUCKET_SILENT);
        whenever(allAlertingSectioner.bucket).thenReturn(BUCKET_ALERTING);

        sectionStyleProvider.setSilentSections(ImmutableList.of(allSilentSectioner))
    }

    @Test
    fun testIsSilent_silentSection() {
        assertThat(sectionStyleProvider.isSilent(fakeNotification(allSilentSectioner))).isTrue()
    }

    @Test
    fun testIsSilent_alertingSection() {
        val listEntry = fakeNotification(allAlertingSectioner)
        // this line should not matter for any non-people sections
        whenever(highPriorityProvider.isHighPriorityConversation(listEntry)).thenReturn(true)

        assertThat(sectionStyleProvider.isSilent(fakeNotification(allAlertingSectioner))).isFalse()
    }

    @Test
    @EnableFlags(Flags.FLAG_SORT_SECTION_BY_TIME)
    fun testIsSilent_silentPeople() {
        val listEntry = fakeNotification(peopleMixedSectioner)
        whenever(highPriorityProvider.isHighPriorityConversation(listEntry)).thenReturn(false)
        assertThat(sectionStyleProvider.isSilent(listEntry)).isTrue()
    }

    @Test
    fun testIsSilent_alertingPeople() {
        val listEntry = fakeNotification(peopleMixedSectioner)
        whenever(highPriorityProvider.isHighPriorityConversation(listEntry)).thenReturn(true)

        assertThat(sectionStyleProvider.isSilent(listEntry)).isFalse()
    }

    private fun fakeNotification(inputSectioner: NotifSectioner): ListEntry {
        val mockUserHandle =
                mock<UserHandle>().apply { whenever(identifier).thenReturn(0) }
        val mockSbn: StatusBarNotification =
                mock<StatusBarNotification>().apply { whenever(user).thenReturn(mockUserHandle) }
        val mockRow: ExpandableNotificationRow = mock<ExpandableNotificationRow>()
        val mockEntry = mock<NotificationEntry>().apply {
            whenever(sbn).thenReturn(mockSbn)
            whenever(row).thenReturn(mockRow)
        }
        whenever(mockEntry.rowExists()).thenReturn(true)
        return object : ListEntry("key", 0) {
            override fun getRepresentativeEntry(): NotificationEntry = mockEntry
            override fun getSection(): NotifSection? = NotifSection(inputSectioner, 1)
        }
    }
}
