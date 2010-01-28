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

package android.widget.listview;

import android.app.Instrumentation;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.widget.Button;
import android.widget.ListView;

import com.android.frameworks.coretests.R;
import android.widget.listview.ListViewHeight;

public class ListViewHeightTest extends ActivityInstrumentationTestCase<ListViewHeight> {
    private ListViewHeight mActivity;


    public ListViewHeightTest() {
        super("com.android.frameworks.coretests", ListViewHeight.class);
    }
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mActivity = getActivity();
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mActivity);
    }

    @MediumTest
    public void testButtons() {
        Instrumentation inst = getInstrumentation();
        final Button button1 = (Button) mActivity.findViewById(R.id.button1);
        final Button button2 = (Button) mActivity.findViewById(R.id.button2);
        final Button button3 = (Button) mActivity.findViewById(R.id.button3);
        

        ListView list = (ListView) mActivity.findViewById(R.id.inner_list);
        assertEquals("Unexpected items in adapter", 0, list.getCount());
        assertEquals("Unexpected children in list view", 0, list.getChildCount());
        
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                button1.performClick();
            }
        });
        inst.waitForIdleSync();
        assertTrue("List not be visible after clicking button1", list.isShown());
        assertTrue("List incorrect height", list.getHeight() == 200);
        
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                button2.performClick();
            }
        });
        inst.waitForIdleSync();
        assertTrue("List not be visible after clicking button2", list.isShown());

        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                button3.performClick();
            }
        });
        inst.waitForIdleSync();
        assertFalse("List should not be visible clicking button3", list.isShown());
    }
    
}
