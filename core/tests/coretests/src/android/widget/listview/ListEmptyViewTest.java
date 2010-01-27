/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.widget.listview;

import android.app.Instrumentation;
import android.content.Intent;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;

public class ListEmptyViewTest extends ActivityInstrumentationTestCase<ListWithEmptyView> {
    private ListWithEmptyView mActivity;
    private ListView mListView;


    public ListEmptyViewTest() {
        super("com.android.frameworks.coretests", ListWithEmptyView.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mActivity = getActivity();
        mListView = getActivity().getListView();
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mActivity);
        assertNotNull(mListView);
        
        assertTrue("Empty view not shown", mListView.getVisibility() == View.GONE);
    }
    
    @MediumTest
    public void testZeroToOne() {
        Instrumentation inst = getInstrumentation();
       
        inst.invokeMenuActionSync(mActivity, mActivity.MENU_ADD, 0);
        inst.waitForIdleSync();
        assertTrue("Empty view still shown", mActivity.getEmptyView().getVisibility() == View.GONE);
        assertTrue("List not shown", mActivity.getListView().getVisibility() == View.VISIBLE);
    }
    
    @MediumTest
    public void testZeroToOneForwardBack() {
        Instrumentation inst = getInstrumentation();
       
        inst.invokeMenuActionSync(mActivity, mActivity.MENU_ADD, 0);
        inst.waitForIdleSync();
        assertTrue("Empty view still shown", mActivity.getEmptyView().getVisibility() == View.GONE);
        assertTrue("List not shown", mActivity.getListView().getVisibility() == View.VISIBLE);
        
        // Navigate forward
        Intent intent = new Intent();
        intent.setClass(mActivity, ListWithEmptyView.class);
        mActivity.startActivity(intent);
        
        // Navigate backward
        inst.sendCharacterSync(KeyEvent.KEYCODE_BACK);
        inst.waitForIdleSync();
        assertTrue("Empty view still shown", mActivity.getEmptyView().getVisibility() == View.GONE);
        assertTrue("List not shown", mActivity.getListView().getVisibility() == View.VISIBLE);

    }
    
    @LargeTest
    public void testZeroToManyToZero() {
        Instrumentation inst = getInstrumentation();
       
        int i;

        for (i = 0; i < 10; i++) {
            inst.invokeMenuActionSync(mActivity, mActivity.MENU_ADD, 0);
            inst.waitForIdleSync();
            assertTrue("Empty view still shown",
                    mActivity.getEmptyView().getVisibility() == View.GONE);
            assertTrue("List not shown", mActivity.getListView().getVisibility() == View.VISIBLE);
        }

        for (i = 0; i < 10; i++) {
            inst.invokeMenuActionSync(mActivity, mActivity.MENU_REMOVE, 0);
            inst.waitForIdleSync();
            if (i < 9) {
                assertTrue("Empty view still shown",
                        mActivity.getEmptyView().getVisibility() == View.GONE);
                assertTrue("List not shown",
                        mActivity.getListView().getVisibility() == View.VISIBLE);
            } else {
                assertTrue("Empty view not shown",
                        mActivity.getEmptyView().getVisibility() == View.VISIBLE);
                assertTrue("List still shown",
                        mActivity.getListView().getVisibility() == View.GONE);
            }
        }
        
        // Navigate forward
        Intent intent = new Intent();
        intent.setClass(mActivity, ListWithEmptyView.class);
        mActivity.startActivity(intent);
        
        // Navigate backward
        inst.sendCharacterSync(KeyEvent.KEYCODE_BACK);
        inst.waitForIdleSync();
        assertTrue("Empty view not shown", mActivity.getEmptyView().getVisibility() == View.VISIBLE);
        assertTrue("List still shown", mActivity.getListView().getVisibility() == View.GONE);
    }
    
    
}
