/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import android.os.UserHandle
import androidx.test.filters.SmallTest
import com.android.internal.statusbar.StatusBarIcon
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.StatusBarIconController.TAG_PRIMARY
import com.android.systemui.statusbar.phone.StatusBarIconControllerImpl.EXTERNAL_SLOT_SUFFIX
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.verify

@SmallTest
class StatusBarIconControllerImplTest : SysuiTestCase() {

    private lateinit var underTest: StatusBarIconControllerImpl

    private lateinit var iconList: StatusBarIconList
    private val iconGroup: StatusBarIconController.IconManager = mock()

    @Before
    fun setUp() {
        iconList = StatusBarIconList(arrayOf())
        underTest =
            StatusBarIconControllerImpl(
                context,
                mock(),
                mock(),
                mock(),
                mock(),
                mock(),
                iconList,
                mock(),
            )
        underTest.addIconGroup(iconGroup)
    }

    /** Regression test for b/255428281. */
    @Test
    fun internalAndExternalIconWithSameName_bothDisplayed() {
        val slotName = "mute"

        // Internal
        underTest.setIcon(slotName, /* resourceId= */ 10, "contentDescription")

        // External
        val externalIcon =
            StatusBarIcon(
                "external.package",
                UserHandle.ALL,
                /* iconId= */ 2,
                /* iconLevel= */ 0,
                /* number= */ 0,
                "contentDescription",
            )
        underTest.setIcon(slotName, externalIcon)

        assertThat(iconList.slots).hasSize(2)
        // Whichever was added last comes first
        assertThat(iconList.slots[0].name).isEqualTo(slotName + EXTERNAL_SLOT_SUFFIX)
        assertThat(iconList.slots[1].name).isEqualTo(slotName)
        assertThat(iconList.slots[0].hasIconsInSlot()).isTrue()
        assertThat(iconList.slots[1].hasIconsInSlot()).isTrue()
    }

    /** Regression test for b/255428281. */
    @Test
    fun internalAndExternalIconWithSameName_externalRemoved_viaRemoveIcon_internalStays() {
        val slotName = "mute"

        // Internal
        underTest.setIcon(slotName, /* resourceId= */ 10, "contentDescription")

        // External
        underTest.setIcon(slotName, createExternalIcon())

        // WHEN the external icon is removed via #removeIcon
        underTest.removeIcon(slotName)

        // THEN the external icon is removed but the internal icon remains
        // Note: [StatusBarIconList] never removes slots from its list, it just sets the holder for
        // the slot to null when an icon is removed.
        assertThat(iconList.slots).hasSize(2)
        assertThat(iconList.slots[0].name).isEqualTo(slotName + EXTERNAL_SLOT_SUFFIX)
        assertThat(iconList.slots[1].name).isEqualTo(slotName)
        assertThat(iconList.slots[0].hasIconsInSlot()).isFalse() // Indicates removal
        assertThat(iconList.slots[1].hasIconsInSlot()).isTrue()

        verify(iconGroup).onRemoveIcon(0)
    }

    /** Regression test for b/255428281. */
    @Test
    fun internalAndExternalIconWithSameName_externalRemoved_viaRemoveAll_internalStays() {
        val slotName = "mute"

        // Internal
        underTest.setIcon(slotName, /* resourceId= */ 10, "contentDescription")

        // External
        underTest.setIcon(slotName, createExternalIcon())

        // WHEN the external icon is removed via #removeAllIconsForExternalSlot
        underTest.removeAllIconsForExternalSlot(slotName)

        // THEN the external icon is removed but the internal icon remains
        assertThat(iconList.slots).hasSize(2)
        assertThat(iconList.slots[0].name).isEqualTo(slotName + EXTERNAL_SLOT_SUFFIX)
        assertThat(iconList.slots[1].name).isEqualTo(slotName)
        assertThat(iconList.slots[0].hasIconsInSlot()).isFalse() // Indicates removal
        assertThat(iconList.slots[1].hasIconsInSlot()).isTrue()

        verify(iconGroup).onRemoveIcon(0)
    }

    /** Regression test for b/255428281. */
    @Test
    fun internalAndExternalIconWithSameName_externalRemoved_viaSetNull_internalStays() {
        val slotName = "mute"

        // Internal
        underTest.setIcon(slotName, /* resourceId= */ 10, "contentDescription")

        // External
        underTest.setIcon(slotName, createExternalIcon())

        // WHEN the external icon is removed via a #setIcon(null)
        underTest.setIcon(slotName, /* icon= */ null)

        // THEN the external icon is removed but the internal icon remains
        assertThat(iconList.slots).hasSize(2)
        assertThat(iconList.slots[0].name).isEqualTo(slotName + EXTERNAL_SLOT_SUFFIX)
        assertThat(iconList.slots[1].name).isEqualTo(slotName)
        assertThat(iconList.slots[0].hasIconsInSlot()).isFalse() // Indicates removal
        assertThat(iconList.slots[1].hasIconsInSlot()).isTrue()

        verify(iconGroup).onRemoveIcon(0)
    }

    /** Regression test for b/255428281. */
    @Test
    fun internalAndExternalIconWithSameName_internalRemoved_viaRemove_externalStays() {
        val slotName = "mute"

        // Internal
        underTest.setIcon(slotName, /* resourceId= */ 10, "contentDescription")

        // External
        underTest.setIcon(slotName, createExternalIcon())

        // WHEN the internal icon is removed via #removeIcon
        underTest.removeIcon(slotName, /* tag= */ 0)

        // THEN the external icon is removed but the internal icon remains
        assertThat(iconList.slots).hasSize(2)
        assertThat(iconList.slots[0].name).isEqualTo(slotName + EXTERNAL_SLOT_SUFFIX)
        assertThat(iconList.slots[1].name).isEqualTo(slotName)
        assertThat(iconList.slots[0].hasIconsInSlot()).isTrue()
        assertThat(iconList.slots[1].hasIconsInSlot()).isFalse() // Indicates removal

        verify(iconGroup).onRemoveIcon(1)
    }

    /** Regression test for b/255428281. */
    @Test
    fun internalAndExternalIconWithSameName_internalRemoved_viaRemoveAll_externalStays() {
        val slotName = "mute"

        // Internal
        underTest.setIcon(slotName, /* resourceId= */ 10, "contentDescription")

        // External
        underTest.setIcon(slotName, createExternalIcon())

        // WHEN the internal icon is removed via #removeAllIconsForSlot
        underTest.removeAllIconsForSlot(slotName)

        // THEN the external icon is removed but the internal icon remains
        assertThat(iconList.slots).hasSize(2)
        assertThat(iconList.slots[0].name).isEqualTo(slotName + EXTERNAL_SLOT_SUFFIX)
        assertThat(iconList.slots[1].name).isEqualTo(slotName)
        assertThat(iconList.slots[0].hasIconsInSlot()).isTrue()
        assertThat(iconList.slots[1].hasIconsInSlot()).isFalse() // Indicates removal

        verify(iconGroup).onRemoveIcon(1)
    }

    /** Regression test for b/255428281. */
    @Test
    fun internalAndExternalIconWithSameName_internalUpdatedIndependently() {
        val slotName = "mute"

        // Internal
        underTest.setIcon(slotName, /* resourceId= */ 10, "contentDescription")

        // External
        val startingExternalIcon =
            StatusBarIcon(
                "external.package",
                UserHandle.ALL,
                /* iconId= */ 20,
                /* iconLevel= */ 0,
                /* number= */ 0,
                "externalDescription",
            )
        underTest.setIcon(slotName, startingExternalIcon)

        // WHEN the internal icon is updated
        underTest.setIcon(slotName, /* resourceId= */ 11, "newContentDescription")

        // THEN only the internal slot gets the updates
        val internalSlot = iconList.slots[1]
        val internalHolder = internalSlot.getHolderForTag(TAG_PRIMARY)!!
        assertThat(internalSlot.name).isEqualTo(slotName)
        assertThat(internalHolder.icon!!.contentDescription).isEqualTo("newContentDescription")
        assertThat(internalHolder.icon!!.icon.resId).isEqualTo(11)

        // And the external slot has its own values
        val externalSlot = iconList.slots[0]
        val externalHolder = externalSlot.getHolderForTag(TAG_PRIMARY)!!
        assertThat(externalSlot.name).isEqualTo(slotName + EXTERNAL_SLOT_SUFFIX)
        assertThat(externalHolder.icon!!.contentDescription).isEqualTo("externalDescription")
        assertThat(externalHolder.icon!!.icon.resId).isEqualTo(20)
    }

    /** Regression test for b/255428281. */
    @Test
    fun internalAndExternalIconWithSameName_externalUpdatedIndependently() {
        val slotName = "mute"

        // Internal
        underTest.setIcon(slotName, /* resourceId= */ 10, "contentDescription")

        // External
        val startingExternalIcon =
            StatusBarIcon(
                "external.package",
                UserHandle.ALL,
                /* iconId= */ 20,
                /* iconLevel= */ 0,
                /* number= */ 0,
                "externalDescription",
            )
        underTest.setIcon(slotName, startingExternalIcon)

        // WHEN the external icon is updated
        val newExternalIcon =
            StatusBarIcon(
                "external.package",
                UserHandle.ALL,
                /* iconId= */ 21,
                /* iconLevel= */ 0,
                /* number= */ 0,
                "newExternalDescription",
            )
        underTest.setIcon(slotName, newExternalIcon)

        // THEN only the external slot gets the updates
        val externalSlot = iconList.slots[0]
        val externalHolder = externalSlot.getHolderForTag(TAG_PRIMARY)!!
        assertThat(externalSlot.name).isEqualTo(slotName + EXTERNAL_SLOT_SUFFIX)
        assertThat(externalHolder.icon!!.contentDescription).isEqualTo("newExternalDescription")
        assertThat(externalHolder.icon!!.icon.resId).isEqualTo(21)

        // And the internal slot has its own values
        val internalSlot = iconList.slots[1]
        val internalHolder = internalSlot.getHolderForTag(TAG_PRIMARY)!!
        assertThat(internalSlot.name).isEqualTo(slotName)
        assertThat(internalHolder.icon!!.contentDescription).isEqualTo("contentDescription")
        assertThat(internalHolder.icon!!.icon.resId).isEqualTo(10)
    }

    @Test
    fun externalSlot_alreadyEndsWithSuffix_suffixNotAddedTwice() {
        underTest.setIcon("myslot$EXTERNAL_SLOT_SUFFIX", createExternalIcon())

        assertThat(iconList.slots).hasSize(1)
        assertThat(iconList.slots[0].name).isEqualTo("myslot$EXTERNAL_SLOT_SUFFIX")
    }

    private fun createExternalIcon(): StatusBarIcon {
        return StatusBarIcon(
            "external.package",
            UserHandle.ALL,
            /* iconId= */ 2,
            /* iconLevel= */ 0,
            /* number= */ 0,
            "contentDescription",
        )
    }
}
