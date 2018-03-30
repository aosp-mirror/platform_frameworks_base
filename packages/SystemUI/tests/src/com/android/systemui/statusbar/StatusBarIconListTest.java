package com.android.systemui.statusbar;

import static com.android.systemui.statusbar.phone.StatusBarIconController.TAG_PRIMARY;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.StatusBarIconHolder;
import com.android.systemui.statusbar.phone.StatusBarIconList;
import com.android.systemui.statusbar.phone.StatusBarIconList.Slot;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class StatusBarIconListTest extends SysuiTestCase {

    private final static String[] STATUS_BAR_SLOTS = {"aaa", "bbb", "ccc"};

    @Test
    public void testGetExistingSlot() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        assertEquals(1, statusBarIconList.getSlotIndex("bbb"));
        assertEquals(2, statusBarIconList.getSlotIndex("ccc"));
    }

    @Test
    public void testGetNonexistingSlot() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        assertEquals(0, statusBarIconList.getSlotIndex("aaa"));
        assertEquals(3, statusBarIconList.size());
        assertEquals(0, statusBarIconList.getSlotIndex("zzz")); // new content added in front
        assertEquals(1, statusBarIconList.getSlotIndex("aaa")); // slid back
        assertEquals(4, statusBarIconList.size());
    }

    @Test
    public void testAddSlotSlidesIcons() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIconHolder sbHolder = mock(StatusBarIconHolder.class);
        statusBarIconList.setIcon(0, sbHolder);
        statusBarIconList.getSlotIndex("zzz"); // new content added in front
        assertNull(statusBarIconList.getIcon(0, TAG_PRIMARY));
        assertEquals(sbHolder, statusBarIconList.getIcon(1, TAG_PRIMARY));
    }

    @Test
    public void testGetAndSetIcon() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIconHolder sbHolderA = mock(StatusBarIconHolder.class);
        StatusBarIconHolder sbHolderB = mock(StatusBarIconHolder.class);
        statusBarIconList.setIcon(0, sbHolderA);
        statusBarIconList.setIcon(1, sbHolderB);
        assertEquals(sbHolderA, statusBarIconList.getIcon(0, TAG_PRIMARY));
        assertEquals(sbHolderB, statusBarIconList.getIcon(1, TAG_PRIMARY));
        assertNull(statusBarIconList.getIcon(2, TAG_PRIMARY)); // icon not set
    }

    @Test
    public void testRemoveIcon() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIconHolder sbHolderA = mock(StatusBarIconHolder.class);
        StatusBarIconHolder sbHolderB = mock(StatusBarIconHolder.class);
        statusBarIconList.setIcon(0, sbHolderA);
        statusBarIconList.setIcon(1, sbHolderB);
        statusBarIconList.removeIcon(0, TAG_PRIMARY);
        assertNull(statusBarIconList.getIcon(0, TAG_PRIMARY)); // icon not set
    }

    @Test
    public void testGetViewIndex_NoMultiples() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIconHolder sbHolder = mock(StatusBarIconHolder.class);
        statusBarIconList.setIcon(2, sbHolder);
        // Icon for item 2 is 0th child view.
        assertEquals(0, statusBarIconList.getViewIndex(2, TAG_PRIMARY));
        statusBarIconList.setIcon(0, sbHolder);
        // Icon for item 0 is 0th child view,
        assertEquals(0, statusBarIconList.getViewIndex(0, TAG_PRIMARY));
        // and item 2 is now 1st child view.
        assertEquals(1, statusBarIconList.getViewIndex(2, TAG_PRIMARY));
    }

    @Test
    public void testGetViewIndex_MultipleIconsPerSlot() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIconHolder sbHolder = mock(StatusBarIconHolder.class);

        statusBarIconList.setIcon(2, sbHolder); // item 2, one icon 0th child

        // All of these can be added to the same slot
        // no tag bc it defaults to 0
        StatusBarIconHolder sbHolder2 = mock(StatusBarIconHolder.class);
        StatusBarIconHolder sbHolder3 = mock(StatusBarIconHolder.class);
        int sb3Tag = 1;
        when(sbHolder3.getTag()).thenReturn(sb3Tag);
        StatusBarIconHolder sbHolder4 = mock(StatusBarIconHolder.class);
        int sb4Tag = 2;
        when(sbHolder4.getTag()).thenReturn(sb4Tag);

        // Put a holder at slot 1, verify that it is first
        statusBarIconList.setIcon(1, sbHolder2);
        assertEquals(0, statusBarIconList.getViewIndex(1, TAG_PRIMARY));

        // Put another holder at slot 1, verify it's index 0 and the rest come after
        statusBarIconList.setIcon(1, sbHolder3);
        assertEquals(0, statusBarIconList.getViewIndex(1, sb3Tag));
        assertEquals(1, statusBarIconList.getViewIndex(1, TAG_PRIMARY));
        // First icon should be at the end
        assertEquals(2, statusBarIconList.getViewIndex(2, TAG_PRIMARY));

        // Put another one in there just for good measure
        statusBarIconList.setIcon(1, sbHolder4);
        assertEquals(0, statusBarIconList.getViewIndex(1, sb4Tag));
        assertEquals(1, statusBarIconList.getViewIndex(1, sb3Tag));
        assertEquals(2, statusBarIconList.getViewIndex(1, TAG_PRIMARY));
        assertEquals(3, statusBarIconList.getViewIndex(2, TAG_PRIMARY));
    }

    /**
     * StatusBarIconList.Slot tests
     */

    @Test
    public void testSlot_OrderIsPreserved() {
        Slot testSlot = new Slot("test_name", null);

        // no tag bc it defaults to 0
        StatusBarIconHolder sbHolder1 = mock(StatusBarIconHolder.class);
        StatusBarIconHolder sbHolder2 = mock(StatusBarIconHolder.class);
        int sb2Tag = 1;
        when(sbHolder2.getTag()).thenReturn(sb2Tag);
        StatusBarIconHolder sbHolder3 = mock(StatusBarIconHolder.class);
        int sb3Tag = 2;
        when(sbHolder3.getTag()).thenReturn(sb3Tag);

        ArrayList<StatusBarIconHolder> expected = new ArrayList<>();
        expected.add(sbHolder1);
        expected.add(sbHolder2);
        expected.add(sbHolder3);


        // Add 3 icons in the same slot, and verify that the list we get is equal to what we gave
        for (StatusBarIconHolder holder : expected) {
            testSlot.addHolder(holder);
        }
        assertTrue(listsEqual(expected, testSlot.getHolderList()));
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