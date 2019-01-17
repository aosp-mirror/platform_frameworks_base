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

package android.widget.focus;

import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;

import androidx.test.filters.MediumTest;
import androidx.test.filters.Suppress;

import com.android.frameworks.coretests.R;

/**
 * Tests that focus works as expected when navigating into and out of
 * a {@link ListView} that has buttons in it.
 */
@Suppress // Flaky
public class ListOfButtonsTest extends ActivityInstrumentationTestCase2<ListOfButtons> {

    private ListAdapter mListAdapter;
    private Button mButtonAtTop;

    private ListView mListView;

    public ListOfButtonsTest() {
        super(ListOfButtons.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        ListOfButtons a = getActivity();
        getInstrumentation().waitForIdleSync();
        mListAdapter = a.getListAdapter();
        mButtonAtTop = (Button) a.findViewById(R.id.button);
        mListView = a.getListView();
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mListAdapter);
        assertNotNull(mButtonAtTop);
        assertNotNull(mListView);

        assertFalse(mButtonAtTop.hasFocus());
        assertTrue(mListView.hasFocus());
        assertEquals("expecting 0 index to be selected",
                0, mListView.getSelectedItemPosition());
    }

    @MediumTest
    public void testNavigateToButtonAbove() {
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);

        assertTrue(mButtonAtTop.hasFocus());        
        assertFalse(mListView.hasFocus());
    }

    @MediumTest
    public void testNavigateToSecondItem() {
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);

        assertTrue(mListView.hasFocus());

        View childOne = mListView.getChildAt(1);
        assertNotNull(childOne);
        assertEquals(childOne, mListView.getFocusedChild());
        assertTrue(childOne.hasFocus());
    }

    @MediumTest
    public void testNavigateUpAboveAndBackOut() {
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);

        assertFalse("button at top should have focus back",
                mButtonAtTop.hasFocus());
        assertTrue(mListView.hasFocus());
    }

    // TODO: this reproduces bug 981791
    public void TODO_testNavigateThroughAllButtonsAndBack() {

        String[] labels = getActivity().getLabels();
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i];
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
            getInstrumentation().waitForIdleSync();

            String indexInfo = "index: " + i + ", label: " + label;

            assertTrue(indexInfo, mListView.hasFocus());
            
            Button button = (Button) mListView.getSelectedView();
            assertNotNull(indexInfo, button);
            assertEquals(indexInfo, label, button.getText().toString());
            assertTrue(indexInfo, button.hasFocus());
        }

        // pressing down again shouldn't matter; make sure last item keeps focus
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);


        for (int i = labels.length - 1; i >= 0; i--) {
            String label = labels[i];

            String indexInfo = "index: " + i + ", label: " + label;

            assertTrue(indexInfo, mListView.hasFocus());

            Button button = (Button) mListView.getSelectedView();
            assertNotNull(indexInfo, button);
            assertEquals(indexInfo, label, button.getText().toString());
            assertTrue(indexInfo, button.hasFocus());

            sendKeys(KeyEvent.KEYCODE_DPAD_UP);
            getInstrumentation().waitForIdleSync();
        }

        assertTrue("button at top should have focus back",
                mButtonAtTop.hasFocus());
        assertFalse(mListView.hasFocus());
    }

    @MediumTest
    public void testGoInAndOutOfListWithItemsFocusable() {

        sendKeys(KeyEvent.KEYCODE_DPAD_UP);

        assertTrue(mButtonAtTop.hasFocus());

        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);

        final String firstButtonLabel = getActivity().getLabels()[0];
        final Button firstButton = (Button) mListView.getSelectedView();

        assertTrue(firstButton.isFocused());
        assertEquals(firstButtonLabel, firstButton.getText());

        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue(mButtonAtTop.isFocused());

        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(firstButton.isFocused());

        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue(mButtonAtTop.isFocused());

        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(firstButton.isFocused());
    }


}
