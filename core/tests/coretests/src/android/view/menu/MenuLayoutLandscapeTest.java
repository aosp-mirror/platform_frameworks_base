/**
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.view.menu;

import android.util.KeyUtils;
import com.android.internal.view.menu.IconMenuView;
import com.android.internal.view.menu.MenuBuilder;

import android.content.pm.ActivityInfo;
import android.test.ActivityInstrumentationTestCase;

public class MenuLayoutLandscapeTest extends ActivityInstrumentationTestCase<MenuLayoutLandscape> {
    private static final String LONG_TITLE = "Really really really really really really really really really really long title";
    private static final String SHORT_TITLE = "Item";

    private MenuLayout mActivity;
    
    public MenuLayoutLandscapeTest() {
        super("com.android.frameworks.coretests", MenuLayoutLandscape.class);
    }
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mActivity = getActivity();
    }

    public void testPreconditions() {
        assertNotNull(mActivity);
    }

    private void toggleMenu() {
        getInstrumentation().waitForIdleSync();
        KeyUtils.tapMenuKey(this);
        getInstrumentation().waitForIdleSync();
    }

    /**
     * Asserts the layout of the menu.
     * 
     * @param expectedLayout The number of parameters is the number of rows, and
     *            each parameter is how many items on that row (the first
     *            parameter is the top-most row).
     */
    private void assertLayout(Integer... expectedLayout) {
        toggleMenu();
        
        /* TODO These need to be rewritten to account for presenters that an activity
         * does not have access to.
        IconMenuView iconMenuView = ((IconMenuView) mActivity.getMenuView(MenuBuilder.TYPE_ICON));
        int[] layout = iconMenuView.getLayout();
        int layoutNumRows = iconMenuView.getLayoutNumRows(); 
        
        int expectedRows = expectedLayout.length;
        assertEquals("Row mismatch", expectedRows, layoutNumRows);
        
        for (int row = 0; row < expectedRows; row++) {
            assertEquals("Col mismatch on row " + row, expectedLayout[row].intValue(),
                    layout[row]);
        }
         */
    }
    
    public void test1ShortItem() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(1)
                .setItemTitle(0, SHORT_TITLE));
        assertLayout(1);
    }

    public void test1LongItem() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(1)
                .setItemTitle(0, LONG_TITLE));
        assertLayout(1);
    }

    public void test2LongItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(2)
                .setItemTitle(0, LONG_TITLE)
                .setItemTitle(1, LONG_TITLE));
        assertLayout(1, 1);
    }

    public void test2ShortItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(2)
                .setItemTitle(0, SHORT_TITLE)
                .setItemTitle(1, SHORT_TITLE));
        assertLayout(2);
    }

    public void test3ShortItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(3)
                .setItemTitle(0, SHORT_TITLE)
                .setItemTitle(1, SHORT_TITLE)
                .setItemTitle(2, SHORT_TITLE));
        assertLayout(3);
    }
    
    public void test3VarietyItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(3)
                .setItemTitle(0, SHORT_TITLE)
                .setItemTitle(1, LONG_TITLE)
                .setItemTitle(2, SHORT_TITLE));
        assertLayout(1, 2);
    }

    public void test3VarietyItems2() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(3)
                .setItemTitle(0, LONG_TITLE)
                .setItemTitle(1, SHORT_TITLE)
                .setItemTitle(2, SHORT_TITLE));
        assertLayout(1, 2);
    }

    public void test4LongItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(4)
                .setItemTitle(0, LONG_TITLE)
                .setItemTitle(1, LONG_TITLE)
                .setItemTitle(2, LONG_TITLE)
                .setItemTitle(3, LONG_TITLE));
        assertLayout(2, 2);
    }

    public void test4ShortItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(4)
                .setItemTitle(0, SHORT_TITLE)
                .setItemTitle(1, SHORT_TITLE)
                .setItemTitle(2, SHORT_TITLE)
                .setItemTitle(3, SHORT_TITLE));
        assertLayout(4);
    }
    
    public void test4VarietyItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(4)
                .setItemTitle(0, LONG_TITLE)
                .setItemTitle(1, SHORT_TITLE)
                .setItemTitle(2, SHORT_TITLE)
                .setItemTitle(3, SHORT_TITLE));
        assertLayout(2, 2);
    }

    public void test5ShortItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(5)
                .setItemTitle(0, SHORT_TITLE)
                .setItemTitle(1, SHORT_TITLE)
                .setItemTitle(2, SHORT_TITLE)
                .setItemTitle(3, SHORT_TITLE)
                .setItemTitle(4, SHORT_TITLE));
        assertLayout(5);
    }

    public void test5LongItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(5)
                .setItemTitle(0, LONG_TITLE)
                .setItemTitle(1, LONG_TITLE)
                .setItemTitle(2, LONG_TITLE)
                .setItemTitle(3, LONG_TITLE)
                .setItemTitle(4, LONG_TITLE));
        assertLayout(2, 3);
    }

    public void test5VarietyItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(5)
                .setItemTitle(0, LONG_TITLE)
                .setItemTitle(1, SHORT_TITLE)
                .setItemTitle(2, LONG_TITLE)
                .setItemTitle(3, SHORT_TITLE)
                .setItemTitle(4, LONG_TITLE));
        assertLayout(2, 3);
    }

    public void test6LongItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(6)
                .setItemTitle(0, LONG_TITLE)
                .setItemTitle(1, LONG_TITLE)
                .setItemTitle(2, LONG_TITLE)
                .setItemTitle(3, LONG_TITLE)
                .setItemTitle(4, LONG_TITLE)
                .setItemTitle(5, LONG_TITLE));
        assertLayout(3, 3);
    }

    public void test6ShortItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(6)
                .setItemTitle(0, SHORT_TITLE)
                .setItemTitle(1, SHORT_TITLE)
                .setItemTitle(2, SHORT_TITLE)
                .setItemTitle(3, SHORT_TITLE)
                .setItemTitle(4, SHORT_TITLE)
                .setItemTitle(5, SHORT_TITLE));
        assertLayout(6);
    }

    public void test6VarietyItems() {
        mActivity.setParams(new MenuScenario.Params()
                .setNumItems(6)
                .setItemTitle(0, SHORT_TITLE)
                .setItemTitle(1, LONG_TITLE)
                .setItemTitle(2, SHORT_TITLE)
                .setItemTitle(3, LONG_TITLE)
                .setItemTitle(4, SHORT_TITLE)
                .setItemTitle(5, SHORT_TITLE));
        assertLayout(3, 3);
    }
}
