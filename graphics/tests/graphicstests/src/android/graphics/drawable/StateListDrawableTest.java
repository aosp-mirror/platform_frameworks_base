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

package android.graphics.drawable;

import junit.framework.TestCase;

import android.R;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.util.StateSet;
import android.view.MockView;

/**
 * Tests for StateListDrawable
 *
 */

public class StateListDrawableTest extends TestCase {

    private StateListDrawable slDrawable;
    private MockDrawable mockFocusedDrawable;
    private MockDrawable mockCheckedDrawable;
    private MockView mockView;
    private MockDrawable mockDefaultDrawable;


    // Re-enable tests when we are running in the framework-test directory which allows
    // access to package private access for MockView

    public void broken_testFocusScenarioSetStringWildcardFirst() throws Exception {
        int focusedStateSet[] = {R.attr.state_focused};
        int checkedStateSet[] = {R.attr.state_checked};
        slDrawable.addState(StateSet.WILD_CARD,
                               mockDefaultDrawable);
        slDrawable.addState(checkedStateSet, mockCheckedDrawable);
        slDrawable.addState(focusedStateSet, mockFocusedDrawable);
        mockView.requestFocus();
        mockView.getBackground().draw(null);
        assertTrue(mockDefaultDrawable.wasDrawn);
    }

    public void broken_testFocusScenarioStateSetWildcardLast() throws Exception {
        int focusedStateSet[] = {R.attr.state_focused};
        int checkedStateSet[] = {R.attr.state_checked};
        slDrawable.addState(checkedStateSet, mockCheckedDrawable);
        slDrawable.addState(focusedStateSet, mockFocusedDrawable);
        slDrawable.addState(StateSet.WILD_CARD,
                               mockDefaultDrawable);
        mockView.requestFocus();
        mockView.getBackground().draw(null);
        assertTrue(mockFocusedDrawable.wasDrawn);
    }

 
    protected void setUp() throws Exception {
        super.setUp();
        slDrawable = new StateListDrawable();
        mockFocusedDrawable = new MockDrawable();
        mockCheckedDrawable = new MockDrawable();
        mockDefaultDrawable = new MockDrawable();
        mockView = new MockView();
        mockView.setBackgroundDrawable(slDrawable);
    }

    static class MockDrawable extends Drawable {

        public boolean wasDrawn = false;

        public void draw(Canvas canvas) {
            wasDrawn = true;
        }

        public void setAlpha(int alpha) {
        }

        public void setColorFilter(ColorFilter cf) {
        }

        public int getOpacity() {
            return android.graphics.PixelFormat.UNKNOWN;
        }
    }

}
