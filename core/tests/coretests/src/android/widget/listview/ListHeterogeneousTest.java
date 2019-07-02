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
import android.view.KeyEvent;
import android.widget.ListView;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

public class ListHeterogeneousTest extends ActivityInstrumentationTestCase<ListHeterogeneous> {
    private ListHeterogeneous mActivity;
    private ListView mListView;


    public ListHeterogeneousTest() {
        super("com.android.frameworks.coretests", ListHeterogeneous.class);
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
    }
    
    @LargeTest
    public void testKeyScrolling() {
        Instrumentation inst = getInstrumentation();
        
        int count = mListView.getAdapter().getCount();

        
        for (int i = 0; i < count - 1; i++) {
            inst.sendCharacterSync(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        inst.waitForIdleSync();
        int convertMissesBefore = mActivity.getConvertMisses();
        
        assertEquals("Unexpected convert misses", 0, convertMissesBefore);
        
        for (int i = 0; i < count - 1; i++) {
            inst.sendCharacterSync(KeyEvent.KEYCODE_DPAD_UP);
        }
        inst.waitForIdleSync();
        int convertMissesAfter = mActivity.getConvertMisses();
        
        assertEquals("Unexpected convert misses", 0, convertMissesAfter);
    }
}
