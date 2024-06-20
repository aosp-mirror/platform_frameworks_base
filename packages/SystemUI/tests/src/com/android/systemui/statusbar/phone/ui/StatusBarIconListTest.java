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

package com.android.systemui.statusbar.phone.ui;

import static com.android.systemui.statusbar.phone.ui.StatusBarIconController.TAG_PRIMARY;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.StatusBarIconHolder;
import com.android.systemui.statusbar.phone.ui.StatusBarIconList;
import com.android.systemui.statusbar.phone.ui.StatusBarIconList.Slot;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class StatusBarIconListTest extends SysuiTestCase {

    private final static String[] STATUS_BAR_SLOTS = {"aaa", "bbb", "ccc"};

    @Test
    public void testGetExistingSlot() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);

        List<Slot> slots = statusBarIconList.getSlots();
        assertEquals(3, slots.size());
        assertEquals("aaa", slots.get(0).getName());
        assertEquals("bbb", slots.get(1).getName());
        assertEquals("ccc", slots.get(2).getName());
    }

    @Test
    public void testGetNonexistingSlot() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);

        statusBarIconList.getSlot("zzz");

        List<Slot> slots = statusBarIconList.getSlots();
        assertEquals(4, slots.size());
        // new content added in front, so zzz should be first and aaa should slide back to second
        assertEquals("zzz", slots.get(0).getName());
        assertEquals("aaa", slots.get(1).getName());
    }

    @Test
    public void testAddSlotSlidesIcons() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIconHolder sbHolder = mock(StatusBarIconHolder.class);
        statusBarIconList.setIcon("aaa", sbHolder);

        statusBarIconList.getSlot("zzz");

        List<Slot> slots = statusBarIconList.getSlots();
        // new content added in front, so the holder we set on "aaa" should show up at index 1
        assertNull(slots.get(0).getHolderForTag(TAG_PRIMARY));
        assertEquals(sbHolder, slots.get(1).getHolderForTag(TAG_PRIMARY));
    }

    @Test
    public void testGetAndSetIcon() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIconHolder sbHolderA = mock(StatusBarIconHolder.class);
        StatusBarIconHolder sbHolderB = mock(StatusBarIconHolder.class);

        statusBarIconList.setIcon("aaa", sbHolderA);
        statusBarIconList.setIcon("bbb", sbHolderB);

        assertEquals(sbHolderA, statusBarIconList.getIconHolder("aaa", TAG_PRIMARY));
        assertEquals(sbHolderB, statusBarIconList.getIconHolder("bbb", TAG_PRIMARY));
        assertNull(statusBarIconList.getIconHolder("ccc", TAG_PRIMARY)); // icon not set
    }

    @Test
    public void testRemoveIcon() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIconHolder sbHolderA = mock(StatusBarIconHolder.class);
        StatusBarIconHolder sbHolderB = mock(StatusBarIconHolder.class);

        statusBarIconList.setIcon("aaa", sbHolderA);
        statusBarIconList.setIcon("bbb", sbHolderB);

        statusBarIconList.removeIcon("aaa", TAG_PRIMARY);

        assertNull(statusBarIconList.getIconHolder("aaa", TAG_PRIMARY)); // icon not set
    }

    @Test
    public void testGetViewIndex_NoMultiples() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIconHolder sbHolder = mock(StatusBarIconHolder.class);

        statusBarIconList.setIcon("ccc", sbHolder);

        // Since only "ccc" has a holder set, it should be first
        assertEquals(0, statusBarIconList.getViewIndex("ccc", TAG_PRIMARY));

        // Now, also set a holder for "aaa"
        statusBarIconList.setIcon("aaa", sbHolder);

        // Then "aaa" gets the first view index and "ccc" gets the second
        assertEquals(0, statusBarIconList.getViewIndex("aaa", TAG_PRIMARY));
        assertEquals(1, statusBarIconList.getViewIndex("ccc", TAG_PRIMARY));
    }

    @Test
    public void testGetViewIndex_MultipleIconsPerSlot() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIconHolder sbHolder = mock(StatusBarIconHolder.class);

        statusBarIconList.setIcon("ccc", sbHolder);

        // All of these can be added to the same slot
        // no tag bc it defaults to 0
        StatusBarIconHolder sbHolder2 = mock(StatusBarIconHolder.class);
        StatusBarIconHolder sbHolder3 = mock(StatusBarIconHolder.class);
        int sb3Tag = 1;
        when(sbHolder3.getTag()).thenReturn(sb3Tag);
        StatusBarIconHolder sbHolder4 = mock(StatusBarIconHolder.class);
        int sb4Tag = 2;
        when(sbHolder4.getTag()).thenReturn(sb4Tag);

        // Put a holder for "bbb", verify that it is first
        statusBarIconList.setIcon("bbb", sbHolder2);
        assertEquals(0, statusBarIconList.getViewIndex("bbb", TAG_PRIMARY));

        // Put another holder for "bbb" at slot 1, verify its index 0 and the rest come after
        statusBarIconList.setIcon("bbb", sbHolder3);
        assertEquals(0, statusBarIconList.getViewIndex("bbb", sb3Tag));
        assertEquals(1, statusBarIconList.getViewIndex("bbb", TAG_PRIMARY));
        // "ccc" should appear at the end
        assertEquals(2, statusBarIconList.getViewIndex("ccc", TAG_PRIMARY));

        // Put another one in "bbb" just for good measure
        statusBarIconList.setIcon("bbb", sbHolder4);
        assertEquals(0, statusBarIconList.getViewIndex("bbb", sb4Tag));
        assertEquals(1, statusBarIconList.getViewIndex("bbb", sb3Tag));
        assertEquals(2, statusBarIconList.getViewIndex("bbb", TAG_PRIMARY));
        assertEquals(3, statusBarIconList.getViewIndex("ccc", TAG_PRIMARY));
    }

    /**
     * StatusBarIconList.Slot tests
     */

    @Test
    public void testSlot_ViewOrder() {
        Slot testSlot = new Slot("test_name", null);

        // no tag bc it defaults to 0
        StatusBarIconHolder sbHolder1 = mock(StatusBarIconHolder.class);
        StatusBarIconHolder sbHolder2 = mock(StatusBarIconHolder.class);
        int sb2Tag = 1;
        when(sbHolder2.getTag()).thenReturn(sb2Tag);
        StatusBarIconHolder sbHolder3 = mock(StatusBarIconHolder.class);
        int sb3Tag = 2;
        when(sbHolder3.getTag()).thenReturn(sb3Tag);

        // Add 3 icons in the same slot, and verify that the list we get is equal to what we gave
        testSlot.addHolder(sbHolder1);
        testSlot.addHolder(sbHolder2);
        testSlot.addHolder(sbHolder3);

        // View order is reverse of the order added
        ArrayList<StatusBarIconHolder> expected = new ArrayList<>();
        expected.add(sbHolder3);
        expected.add(sbHolder2);
        expected.add(sbHolder1);

        assertTrue(listsEqual(expected, testSlot.getHolderListInViewOrder()));
    }

    private boolean listsEqual(List<StatusBarIconHolder> list1, List<StatusBarIconHolder> list2) {
        if (list1.size() != list2.size())  return false;

        for (int i = 0; i < list1.size(); i++) {
            if (!list1.get(i).equals(list2.get(i))) {
                return false;
            }
        }

        return true;
    }
}
