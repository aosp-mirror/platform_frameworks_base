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

import android.widget.focus.VerticalFocusSearch;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.Suppress;
import android.view.FocusFinder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * Tests that focus searching works on a vertical linear layout of buttons of
 * various widths and horizontal placements.
 */
// Suppress until bug http://b/issue?id=1416545 is fixed
@Suppress 
public class VerticalFocusSearchTest extends ActivityInstrumentationTestCase<VerticalFocusSearch> {

    private LinearLayout mLayout;

    private Button mTopWide;
    private Button mMidSkinny1Left;
    private Button mMidSkinny2Right;
    private Button mBottomWide;

    private FocusSearchAlg mFocusFinder;

    // helps test old and new impls when figuring out why something that used
    // to work doesn't anymore (or verifying that new works as well as old).
    interface FocusSearchAlg {
        View findNextFocus(ViewGroup root, View focused, int direction);
    }

    // calls new impl
    static class NewFocusSearchAlg implements FocusSearchAlg {

        public View findNextFocus(ViewGroup root, View focused, int direction) {
            return FocusFinder.getInstance()
                    .findNextFocus(root, focused, direction);
        }
    }

    public VerticalFocusSearchTest() {
        super("com.android.frameworks.coretests", VerticalFocusSearch.class);
    }


    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mLayout = getActivity().getLayout();
        mTopWide = getActivity().getTopWide();
        mMidSkinny1Left = getActivity().getMidSkinny1Left();
        mMidSkinny2Right = getActivity().getMidSkinny2Right();
        mBottomWide = getActivity().getBottomWide();

        mFocusFinder = new NewFocusSearchAlg();
    }

    public void testPreconditions() {
        assertNotNull(mLayout);
        assertNotNull(mTopWide);
        assertNotNull(mMidSkinny1Left);
        assertNotNull(mMidSkinny2Right);
        assertNotNull(mBottomWide);
    }

    public void testSearchFromTopButton() {
        assertNull("going up from mTopWide.",
                mFocusFinder.findNextFocus(mLayout, mTopWide, View.FOCUS_UP));

        assertNull("going left from mTopWide.",
                mFocusFinder.findNextFocus(mLayout, mTopWide, View.FOCUS_LEFT));

        assertNull("going right from mTopWide.",
                mFocusFinder.findNextFocus(mLayout, mTopWide, View.FOCUS_RIGHT));

        assertEquals("going down from mTopWide.",
                mMidSkinny1Left,
                mFocusFinder
                .findNextFocus(mLayout, mTopWide, View.FOCUS_DOWN));
    }

    public void testSearchFromMidLeft() {
        assertNull("going left should have no next focus",
                mFocusFinder.findNextFocus(mLayout, mMidSkinny1Left, View.FOCUS_LEFT));

        assertEquals("going right from mMidSkinny1Left should go to mMidSkinny2Right",
                mMidSkinny2Right,
                mFocusFinder.findNextFocus(mLayout, mMidSkinny1Left, View.FOCUS_RIGHT));

        assertEquals("going up from mMidSkinny1Left should go to mTopWide",
                mTopWide,
                mFocusFinder.findNextFocus(mLayout, mMidSkinny1Left, View.FOCUS_UP));

        assertEquals("going down from mMidSkinny1Left should go to mMidSkinny2Right",
                mMidSkinny2Right,
                mFocusFinder.findNextFocus(mLayout, mMidSkinny1Left, View.FOCUS_DOWN));
    }

    public void testSearchFromMidRight() {
        assertEquals("going left from mMidSkinny2Right should go to mMidSkinny1Left",
                mMidSkinny1Left,
                mFocusFinder.findNextFocus(mLayout, mMidSkinny2Right, View.FOCUS_LEFT));

        assertNull("going right should have no next focus",
                mFocusFinder.findNextFocus(mLayout, mMidSkinny2Right, View.FOCUS_RIGHT));

        assertEquals("going up from mMidSkinny2Right should go to mMidSkinny1Left",
                mMidSkinny1Left,
                mFocusFinder.findNextFocus(mLayout, mMidSkinny2Right, View.FOCUS_UP));
        
        assertEquals("going down from mMidSkinny2Right should go to mBottomWide",
                mBottomWide,
                mFocusFinder.findNextFocus(mLayout, mMidSkinny2Right, View.FOCUS_DOWN));

    }

    public void testSearchFromFromBottom() {
        assertNull("going down from bottom button should have no next focus.",
                mFocusFinder.findNextFocus(mLayout, mBottomWide, View.FOCUS_DOWN));

        assertNull("going left from bottom button should have no next focus.",
                mFocusFinder.findNextFocus(mLayout, mBottomWide, View.FOCUS_LEFT));

        assertNull("going right from bottom button should have no next focus.",
                mFocusFinder.findNextFocus(mLayout, mBottomWide, View.FOCUS_RIGHT));

        assertEquals("going up from bottom button should go to mMidSkinny2Right.",
                mMidSkinny2Right,
                mFocusFinder.findNextFocus(mLayout, mBottomWide, View.FOCUS_UP));
    }
}
