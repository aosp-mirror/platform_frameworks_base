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

import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase;
import android.app.Activity;
import android.test.suitebuilder.annotation.MediumTest;
import android.widget.ListView;

public class ListUnspecifiedMeasure<T extends Activity> extends ActivityInstrumentationTestCase<T> {
    private T mActivity;
    private ListView mListView;

    protected ListUnspecifiedMeasure(Class<T> klass) {
        super("com.android.frameworks.coretests", klass);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mActivity = getActivity();
        mListView = (ListView) mActivity.findViewById(R.id.list);
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mActivity);
        assertNotNull(mListView);
    }

    @MediumTest
    public void testWasMeasured() {
        assertTrue(mListView.getMeasuredWidth() > 0);
        assertTrue(mListView.getWidth() > 0);
        assertTrue(mListView.getMeasuredHeight() > 0);
        assertTrue(mListView.getHeight() > 0);
    }
}
