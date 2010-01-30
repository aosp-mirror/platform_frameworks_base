/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import com.android.internal.view.menu.MenuBuilder;

import junit.framework.Assert;

import android.test.AndroidTestCase;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;

import com.android.frameworks.coretests.R;

public class MenuTest extends AndroidTestCase {

    private MenuBuilder mMenu;

    public void setUp() throws Exception {
        super.setUp();
        mMenu = new MenuBuilder(super.getContext());
    }

    @SmallTest
    public void testItemId() {
        final int id = 512;
        final MenuItem item = mMenu.add(0, id, 0, "test");
        
        Assert.assertEquals(id, item.getItemId());
        Assert.assertEquals(item, mMenu.findItem(id));
        Assert.assertEquals(0, mMenu.findItemIndex(id));
    }
    
    @SmallTest
    public void testGroupId() {
        final int groupId = 541;
        final int item1Index = 1;
        final int item2Index = 3;
        
        mMenu.add(0, 0, item1Index - 1, "ignore");
        final MenuItem item = mMenu.add(groupId, 0, item1Index, "test");
        mMenu.add(0, 0, item2Index - 1, "ignore");
        final MenuItem item2 = mMenu.add(groupId, 0, item2Index, "test2");
        
        Assert.assertEquals(groupId, item.getGroupId());
        Assert.assertEquals(groupId, item2.getGroupId());
        Assert.assertEquals(item1Index, mMenu.findGroupIndex(groupId));
        Assert.assertEquals(item2Index, mMenu.findGroupIndex(groupId, item1Index + 1));
    }
    
    @SmallTest
    public void testGroup() {
        // This test does the following
        //  1. Create a grouped item in the menu
        //  2. Check that findGroupIndex() finds the grouped item.
        //  3. Check that findGroupIndex() doesn't find a non-existent group.

        final int GROUP_ONE = Menu.FIRST;
        final int GROUP_TWO = Menu.FIRST + 1;

        mMenu.add(GROUP_ONE, 0, 0, "Menu text");
        Assert.assertEquals(mMenu.findGroupIndex(GROUP_ONE), 0);
        Assert.assertEquals(mMenu.findGroupIndex(GROUP_TWO), -1);
        //TODO: expand this test case to do multiple groups,
        //adding and removing, hiding and showing, etc.
    }

    @SmallTest
    public void testIsShortcutWithAlpha() throws Exception {
        mMenu.setQwertyMode(true);
        mMenu.add(0, 0, 0, "test").setShortcut('2', 'a');
        Assert.assertTrue(mMenu.isShortcutKey(KeyEvent.KEYCODE_A,
                                              makeKeyEvent(KeyEvent.KEYCODE_A,  0)));
        Assert.assertFalse(mMenu.isShortcutKey(KeyEvent.KEYCODE_B,
                                               makeKeyEvent(KeyEvent.KEYCODE_B,  0)));
    }

    @SmallTest
    public void testIsShortcutWithNumeric() throws Exception {
        mMenu.setQwertyMode(false);
        mMenu.add(0, 0, 0, "test").setShortcut('2', 'a');
        Assert.assertTrue(mMenu.isShortcutKey(KeyEvent.KEYCODE_2,
                                              makeKeyEvent(KeyEvent.KEYCODE_2,  0)));
        Assert.assertFalse(mMenu.isShortcutKey(KeyEvent.KEYCODE_A,
                                               makeKeyEvent(KeyEvent.KEYCODE_A,  0)));
    }

    @SmallTest
    public void testIsShortcutWithAlt() throws Exception {
        mMenu.setQwertyMode(true);
        mMenu.add(0, 0, 0, "test").setShortcut('2', 'a');
        Assert.assertTrue(mMenu.isShortcutKey(KeyEvent.KEYCODE_A,
                                              makeKeyEvent(KeyEvent.KEYCODE_A,
                                                           KeyEvent.META_ALT_ON)));
        Assert.assertFalse(mMenu.isShortcutKey(KeyEvent.KEYCODE_A,
                                              makeKeyEvent(KeyEvent.KEYCODE_A,
                                                           KeyEvent.META_SYM_ON)));
    }

    @SmallTest
    public void testIsNotShortcutWithShift() throws Exception {
        mMenu.setQwertyMode(true);
        mMenu.add(0, 0, 0, "test").setShortcut('2', 'a');
        Assert.assertFalse(mMenu.isShortcutKey(KeyEvent.KEYCODE_A,
                                              makeKeyEvent(KeyEvent.KEYCODE_A,
                                                           KeyEvent.META_SHIFT_ON)));
    }

    @SmallTest
    public void testIsNotShortcutWithSym() throws Exception {
        mMenu.setQwertyMode(true);
        mMenu.add(0, 0, 0, "test").setShortcut('2', 'a');
        Assert.assertFalse(mMenu.isShortcutKey(KeyEvent.KEYCODE_A,
                                              makeKeyEvent(KeyEvent.KEYCODE_A,
                                                           KeyEvent.META_SYM_ON)));
    }
    
    @SmallTest
    public void testIsShortcutWithUpperCaseAlpha() throws Exception {
        mMenu.setQwertyMode(true);
        mMenu.add(0, 0, 0, "test").setShortcut('2', 'A');
        Assert.assertTrue(mMenu.isShortcutKey(KeyEvent.KEYCODE_A,
                                              makeKeyEvent(KeyEvent.KEYCODE_A,  0)));
    }

    @SmallTest
    public void testIsShortcutWithBackspace() throws Exception {
        mMenu.setQwertyMode(true);
        mMenu.add(0, 0, 0, "test").setShortcut('2', '\b');
        Assert.assertTrue(mMenu.isShortcutKey(KeyEvent.KEYCODE_DEL,
                                              makeKeyEvent(KeyEvent.KEYCODE_DEL,  0)));
    }

    @SmallTest
    public void testIsShortcutWithNewline() throws Exception {
        mMenu.setQwertyMode(true);
        mMenu.add(0, 0, 0, "test").setShortcut('2', '\n');
        Assert.assertTrue(mMenu.isShortcutKey(KeyEvent.KEYCODE_ENTER,
                                              makeKeyEvent(KeyEvent.KEYCODE_ENTER,  0)));
    }
    
    @SmallTest
    public void testOrder() {
        final String a = "a", b = "b", c = "c";
        final int firstOrder = 7, midOrder = 8, lastOrder = 9;
        
        mMenu.add(0, 0, lastOrder, c);
        mMenu.add(0, 0, firstOrder, a);
        mMenu.add(0, 0, midOrder, b);
        
        Assert.assertEquals(firstOrder, mMenu.getItem(0).getOrder());
        Assert.assertEquals(a, mMenu.getItem(0).getTitle());
        Assert.assertEquals(midOrder, mMenu.getItem(1).getOrder());
        Assert.assertEquals(b, mMenu.getItem(1).getTitle());
        Assert.assertEquals(lastOrder, mMenu.getItem(2).getOrder());
        Assert.assertEquals(c, mMenu.getItem(2).getTitle());
    }

    @SmallTest
    public void testTitle() {
        final String title = "test";
        final MenuItem stringItem = mMenu.add(title);
        final MenuItem resItem = mMenu.add(R.string.menu_test);
        
        Assert.assertEquals(title, stringItem.getTitle());
        Assert.assertEquals(getContext().getResources().getString(R.string.menu_test), resItem
                .getTitle());
    }

    @SmallTest
    public void testCheckable() {
        final int groupId = 1;
        final MenuItem item1 = mMenu.add(groupId, 1, 0, "item1");
        final MenuItem item2 = mMenu.add(groupId, 2, 0, "item2");
        
        // Set to exclusive
        mMenu.setGroupCheckable(groupId, true, true);
        Assert.assertTrue("Item was not set to checkable", item1.isCheckable());
        item1.setChecked(true);
        Assert.assertTrue("Item did not get checked", item1.isChecked());
        Assert.assertFalse("Item was not unchecked due to exclusive checkable", item2.isChecked());
        mMenu.findItem(2).setChecked(true);
        Assert.assertTrue("Item did not get checked", item2.isChecked());
        Assert.assertFalse("Item was not unchecked due to exclusive checkable", item1.isChecked());
        
        // Multiple non-exlusive checkable items
        mMenu.setGroupCheckable(groupId, true, false);
        Assert.assertTrue("Item was not set to checkable", item1.isCheckable());
        item1.setChecked(false);
        Assert.assertFalse("Item did not get unchecked", item1.isChecked());
        item1.setChecked(true);
        Assert.assertTrue("Item did not get checked", item1.isChecked());
        mMenu.findItem(2).setChecked(true);
        Assert.assertTrue("Item did not get checked", item2.isChecked());
        Assert.assertTrue("Item was unchecked when it shouldnt have been", item1.isChecked());
    }
    
    @SmallTest
    public void testVisibility() {
        final MenuItem item1 = mMenu.add(0, 1, 0, "item1");
        final MenuItem item2 = mMenu.add(0, 2, 0, "item2");
        
        // Should start as visible
        Assert.assertTrue("Item did not start as visible", item1.isVisible());
        Assert.assertTrue("Item did not start as visible", item2.isVisible());

        // Hide
        item1.setVisible(false);
        Assert.assertFalse("Item did not become invisible", item1.isVisible());
        mMenu.findItem(2).setVisible(false);
        Assert.assertFalse("Item did not become invisible", item2.isVisible());
    }
    
    @SmallTest
    public void testSubMenu() {
        final SubMenu subMenu = mMenu.addSubMenu(0, 0, 0, "submenu");
        final MenuItem subMenuItem = subMenu.getItem();
        final MenuItem item1 = subMenu.add(0, 1, 0, "item1");
        final MenuItem item2 = subMenu.add(0, 2, 0, "item2");
        
        // findItem should recurse into submenus
        Assert.assertEquals(item1, mMenu.findItem(1));
        Assert.assertEquals(item2, mMenu.findItem(2));
    }
    
    @SmallTest
    public void testRemove() {
        final int groupId = 1;
        final MenuItem item1 = mMenu.add(groupId, 1, 0, "item1");
        final MenuItem item2 = mMenu.add(groupId, 2, 0, "item2");
        final MenuItem item3 = mMenu.add(groupId, 3, 0, "item3");
        final MenuItem item4 = mMenu.add(groupId, 4, 0, "item4");
        final MenuItem item5 = mMenu.add(groupId, 5, 0, "item5");
        final MenuItem item6 = mMenu.add(0, 6, 0, "item6");
        
        Assert.assertEquals(item1, mMenu.findItem(1));
        mMenu.removeItemAt(0);
        Assert.assertNull(mMenu.findItem(1));
        
        Assert.assertEquals(item2, mMenu.findItem(2));
        mMenu.removeItem(2);
        Assert.assertNull(mMenu.findItem(2));
        
        Assert.assertEquals(item3, mMenu.findItem(3));
        Assert.assertEquals(item4, mMenu.findItem(4));
        Assert.assertEquals(item5, mMenu.findItem(5));
        mMenu.removeGroup(groupId);
        Assert.assertNull(mMenu.findItem(3));
        Assert.assertNull(mMenu.findItem(4));
        Assert.assertNull(mMenu.findItem(5));
        
        Assert.assertEquals(item6, mMenu.findItem(6));
        mMenu.clear();
        Assert.assertNull(mMenu.findItem(6));
    }
    
    private KeyEvent makeKeyEvent(int keyCode, int metaState) {
        return new KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, metaState);
    }
}
