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

package android.widget.scroll;

import android.test.ActivityInstrumentationTestCase;
import android.util.InternalSelectionView;
import android.view.KeyEvent;

import androidx.test.filters.MediumTest;
import androidx.test.filters.Suppress;

@Suppress // Failing.
public class ButtonAboveTallInternalSelectionViewTest extends
        ActivityInstrumentationTestCase<ButtonAboveTallInternalSelectionView> {

    public ButtonAboveTallInternalSelectionViewTest() {
        super("com.android.frameworks.coretests", ButtonAboveTallInternalSelectionView.class);
    }

    @MediumTest
    public void testPreconditions() {
        assertTrue("expecting the top button to have focus",
                getActivity().getButtonAbove().isFocused());
        assertEquals("scrollview scroll y",
                0,
                getActivity().getScrollView().getScrollY());
        assertTrue("internal selection view should be taller than screen",
                getActivity().getIsv().getHeight() > getActivity().getScrollView().getHeight());

        assertTrue("top of ISV should be on screen",
                getActivity().getIsv().getTop() >
                getActivity().getScrollView().getScrollY());

    }

    @MediumTest
    public void testMovingFocusDownToItemTallerThanScreenStillOnScreen() {
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        getInstrumentation().waitForIdleSync();

        final InternalSelectionView isv = getActivity().getIsv();
        assertTrue("internal selection view should have taken focus",
                isv.isFocused());
        assertEquals("internal selection view selected row",
                0, isv.getSelectedRow());
        assertTrue("top of ISV should still be on screen",
                getActivity().getIsv().getTop() >
                getActivity().getScrollView().getScrollY());
    }

    

}
