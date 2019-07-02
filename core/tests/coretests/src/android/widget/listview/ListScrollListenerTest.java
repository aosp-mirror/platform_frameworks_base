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
import android.test.TouchUtils;
import android.view.KeyEvent;
import android.widget.AbsListView;
import android.widget.ListView;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

public class ListScrollListenerTest extends ActivityInstrumentationTestCase<ListScrollListener> implements
        AbsListView.OnScrollListener {
    private ListScrollListener mActivity;
    private ListView mListView;
    private int mFirstVisibleItem = -1;
    private int mVisibleItemCount = -1;
    private int mTotalItemCount = -1;

    public ListScrollListenerTest() {
        super("com.android.frameworks.coretests", ListScrollListener.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mActivity = getActivity();
        mListView = getActivity().getListView();
        mListView.setOnScrollListener(this);
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mActivity);
        assertNotNull(mListView);

        assertEquals(0, mFirstVisibleItem);
    }

    @LargeTest
    public void testKeyScrolling() {
        Instrumentation inst = getInstrumentation();
        // focus the listview
        mActivity.runOnUiThread(() -> mListView.requestFocus());
        inst.waitForIdleSync();

        int firstVisibleItem = mFirstVisibleItem;
        for (int i = 0; i < mVisibleItemCount * 2; i++) {
            inst.sendCharacterSync(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        inst.waitForIdleSync();
        assertTrue("Arrow scroll did not happen", mFirstVisibleItem > firstVisibleItem);

        firstVisibleItem = mFirstVisibleItem;
        KeyEvent upDown = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_UP, 0, KeyEvent.META_ALT_ON);
        KeyEvent upUp = new KeyEvent(0, 0, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_DPAD_UP, 0, KeyEvent.META_ALT_ON);
        inst.sendKeySync(upDown);
        inst.sendKeySync(upUp);
        inst.waitForIdleSync();
        assertTrue("Page scroll did not happen", mFirstVisibleItem < firstVisibleItem);

        firstVisibleItem = mFirstVisibleItem;
        KeyEvent down = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN, 0, KeyEvent.META_ALT_ON);
        KeyEvent up = new KeyEvent(0, 0, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, 0, KeyEvent.META_ALT_ON);
        inst.sendKeySync(down);
        inst.sendKeySync(up);
        inst.waitForIdleSync();

        assertTrue("Full scroll did not happen", mFirstVisibleItem > firstVisibleItem);
        assertEquals("Full scroll did not happen", mTotalItemCount,
                mFirstVisibleItem + mVisibleItemCount);
    }

    @LargeTest
    public void testTouchScrolling() {
        int firstVisibleItem = mFirstVisibleItem;
        TouchUtils.dragQuarterScreenUp(this);
        TouchUtils.dragQuarterScreenUp(this);
        assertTrue("Touch scroll did not happen", mFirstVisibleItem > firstVisibleItem);
    }


    public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        mFirstVisibleItem = firstVisibleItem;
        mVisibleItemCount = visibleItemCount;
        mTotalItemCount = totalItemCount;
    }

    public void onScrollStateChanged(AbsListView view, int scrollState) {
    }
}
