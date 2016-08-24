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
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.widget.ListView;
import android.test.TouchUtils;

/**
 * Tests restoring the scroll position in a list with a managed cursor.
 */
public class ListManagedCursorTest extends ActivityInstrumentationTestCase2<ListManagedCursor> {
    private ListManagedCursor mActivity;
    private ListView mListView;

    public ListManagedCursorTest() {
        super(ListManagedCursor.class);
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

        assertEquals(0, mListView.getFirstVisiblePosition());
    }

    /**
     * Scroll the list using arrows, launch new activity, hit back, make sure we're still scrolled.
     */
    @LargeTest
    public void testKeyScrolling() {
        Instrumentation inst = getInstrumentation();

        int firstVisiblePosition = arrowScroll(inst);

        inst.sendCharacterSync(KeyEvent.KEYCODE_BACK);
        inst.waitForIdleSync();

        assertTrue("List changed to touch mode", !mListView.isInTouchMode());
        assertTrue("List did not preserve scroll position",
                firstVisiblePosition == mListView.getFirstVisiblePosition());
    }

    /**
     * Scroll the list using arrows, launch new activity, change to touch mode, hit back, make sure
     * we're still scrolled.
     */
    @LargeTest
    public void testKeyScrollingToTouchMode() {
        Instrumentation inst = getInstrumentation();

        int firstVisiblePosition = arrowScroll(inst);

        TouchUtils.dragQuarterScreenUp(this, getActivity());
        inst.sendCharacterSync(KeyEvent.KEYCODE_BACK);
        inst.waitForIdleSync();

        assertTrue("List did not change to touch mode", mListView.isInTouchMode());
        assertTrue("List did not preserve scroll position",
                firstVisiblePosition == mListView.getFirstVisiblePosition());
    }

    public int arrowScroll(Instrumentation inst) {
        int count = mListView.getChildCount();

        for (int i = 0; i < count * 2; i++) {
            inst.sendCharacterSync(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        inst.waitForIdleSync();

        int firstVisiblePosition = mListView.getFirstVisiblePosition();
        assertTrue("Arrow scroll did not happen", firstVisiblePosition > 0);
        assertTrue("List still in touch mode", !mListView.isInTouchMode());

        inst.sendCharacterSync(KeyEvent.KEYCODE_DPAD_CENTER);
        inst.waitForIdleSync();

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return firstVisiblePosition;
    }
}
