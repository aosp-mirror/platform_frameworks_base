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

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.view.View;
import android.widget.focus.GoneParentFocusedChild;

/**
 * When a parent is GONE, key events shouldn't go to its children, even if they
 * have focus. (part of investigation into issue 945150).
 */
public class GoneParentFocusedChildTest
        extends ActivityInstrumentationTestCase<GoneParentFocusedChild> {


    public GoneParentFocusedChildTest() {
        super("com.android.frameworks.coretests", GoneParentFocusedChild.class);
    }

    @MediumTest
    public void testPreconditinos() {
        assertNotNull(getActivity().getLayout());
        assertNotNull(getActivity().getGoneGroup());
        assertNotNull(getActivity().getButton());
        assertTrue("button should have focus",
                getActivity().getButton().hasFocus());
        assertEquals("gone group should be, well, gone!",
                View.GONE,
                getActivity().getGoneGroup().getVisibility());
        assertFalse("the activity should have received no key events",
                getActivity().isUnhandledKeyEvent());
    }

    @MediumTest
    public void testKeyEventGoesToActivity() {
        sendKeys(KeyEvent.KEYCODE_J);
        assertTrue(getActivity().isUnhandledKeyEvent());
    }
}
