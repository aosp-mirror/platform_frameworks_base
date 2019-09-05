/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.view.menu;

import android.test.ActivityInstrumentationTestCase;
import android.util.KeyUtils;
import android.view.KeyEvent;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

public class MenuWith1ItemTest extends ActivityInstrumentationTestCase<MenuWith1Item> {
    private MenuWith1Item mActivity;
    
    public MenuWith1ItemTest() {
        super("com.android.frameworks.coretests", MenuWith1Item.class);
    }
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mActivity = getActivity();
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mActivity);
        assertFalse(mActivity.getButton().isInTouchMode());
    }

    @MediumTest
    public void testItemClick() {

        // Open menu, click on an item
        KeyUtils.tapMenuKey(this);
        getInstrumentation().waitForIdleSync();
        assertFalse("Item seems to have been clicked before we clicked on it", mActivity
                .wasItemClicked(0));
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue("Item doesn't seem to have registered our click", mActivity.wasItemClicked(0));
    }

    @LargeTest
    public void testTouchModeTransfersRemovesFocus() throws Exception {
        /* TODO These need to be rewritten to account for presenters that an activity
         * does not have access to.

        // open menu, move around to give it focus
        sendKeys(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT);
        final View menuItem = mActivity.getItemView(MenuBuilder.TYPE_ICON, 0);
        assertTrue("menuItem.isFocused()", menuItem.isFocused());

        // close the menu
        sendKeys(KeyEvent.KEYCODE_MENU);
        Thread.sleep(500);

        // touch the screen
        TouchUtils.clickView(this, mActivity.getButton());
        assertTrue("should be in touch mode after touching button",
                mActivity.getButton().isInTouchMode());

        // open the menu, menu item shouldn't be focused, because we are not
        // in touch mode
        sendKeys(KeyEvent.KEYCODE_MENU);
        assertTrue("menuItem.isInTouchMode()", menuItem.isInTouchMode());
        assertFalse("menuItem.isFocused()", menuItem.isFocused());
         */
    }
}
