package com.android.systemui.statusbar;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import static org.mockito.Mockito.mock;

import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.StatusBarIconList;

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
        StatusBarIcon sbIcon = mock(StatusBarIcon.class);
        statusBarIconList.setIcon(0, sbIcon);
        statusBarIconList.getSlotIndex("zzz"); // new content added in front
        assertNull(statusBarIconList.getIcon(0));
        assertEquals(sbIcon, statusBarIconList.getIcon(1));
    }

    @Test
    public void testGetAndSetIcon() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIcon sbIconA = mock(StatusBarIcon.class);
        StatusBarIcon sbIconB = mock(StatusBarIcon.class);
        statusBarIconList.setIcon(0, sbIconA);
        statusBarIconList.setIcon(1, sbIconB);
        assertEquals(sbIconA, statusBarIconList.getIcon(0));
        assertEquals(sbIconB, statusBarIconList.getIcon(1));
        assertNull(statusBarIconList.getIcon(2)); // icon not set
    }

    @Test
    public void testRemoveIcon() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIcon sbIconA = mock(StatusBarIcon.class);
        StatusBarIcon sbIconB = mock(StatusBarIcon.class);
        statusBarIconList.setIcon(0, sbIconA);
        statusBarIconList.setIcon(1, sbIconB);
        statusBarIconList.removeIcon(0);
        assertNull(statusBarIconList.getIcon(0)); // icon not set
    }

    @Test
    public void testGetViewIndex() {
        StatusBarIconList statusBarIconList = new StatusBarIconList(STATUS_BAR_SLOTS);
        StatusBarIcon sbIcon = mock(StatusBarIcon.class);
        statusBarIconList.setIcon(2, sbIcon);
        assertEquals(0, statusBarIconList.getViewIndex(2)); // Icon for item 2 is 0th child view.
        statusBarIconList.setIcon(0, sbIcon);
        assertEquals(0, statusBarIconList.getViewIndex(0)); // Icon for item 0 is 0th child view,
        assertEquals(1, statusBarIconList.getViewIndex(2)); // and item 2 is now 1st child view.
    }

}