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

package android.view;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;

import android.test.ActivityInstrumentationTestCase;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

import com.android.frameworks.coretests.R;

/**
 * Exercises {@link android.view.View}'s ability to change visibility between
 * GONE, VISIBLE and INVISIBLE.
 */
public class VisibilityTest extends ActivityInstrumentationTestCase<Visibility> {
    private TextView mRefUp;
    private TextView mRefDown;
    private TextView mVictim;
    private Button mVisible;
    private Button mInvisible;
    private Button mGone;

    public VisibilityTest() {
        super("com.android.frameworks.coretests", Visibility.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        final Visibility a = getActivity();
        mRefUp = (TextView) a.findViewById(R.id.refUp);
        mRefDown = (TextView) a.findViewById(R.id.refDown);
        mVictim = (TextView) a.findViewById(R.id.victim);
        mVisible = (Button) a.findViewById(R.id.vis);
        mInvisible = (Button) a.findViewById(R.id.invis);
        mGone = (Button) a.findViewById(R.id.gone);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mRefUp);
        assertNotNull(mRefDown);
        assertNotNull(mVictim);
        assertNotNull(mVisible);
        assertNotNull(mInvisible);
        assertNotNull(mGone);
    }

    @MediumTest
    public void testVisibleToInvisible() throws Exception {
        getActivity().runOnUiThread(() -> mInvisible.requestFocus());
        getInstrumentation().waitForIdleSync();

        int oldTop = mVictim.getTop();

        sendKeys("DPAD_CENTER");
        assertEquals(View.INVISIBLE, mVictim.getVisibility());

        int newTop = mVictim.getTop();
        assertEquals(oldTop, newTop);
    }

    @MediumTest
    public void testVisibleToGone() throws Exception {
        getActivity().runOnUiThread(() -> mGone.requestFocus());
        getInstrumentation().waitForIdleSync();

        int oldTop = mVictim.getTop();

        sendKeys("DPAD_CENTER");
        assertEquals(View.GONE, mVictim.getVisibility());

        int refDownTop = mRefDown.getTop();
        assertEquals(oldTop, refDownTop);
    }

    @LargeTest
    public void testGoneToVisible() throws Exception {
        getActivity().runOnUiThread(() -> mGone.requestFocus());
        getInstrumentation().waitForIdleSync();

        int oldTop = mVictim.getTop();

        sendKeys("DPAD_CENTER");
        assertEquals(View.GONE, mVictim.getVisibility());

        int refDownTop = mRefDown.getTop();
        assertEquals(oldTop, refDownTop);

        sendKeys("2*DPAD_LEFT DPAD_CENTER");
        assertEquals(View.VISIBLE, mVictim.getVisibility());

        int newTop = mVictim.getTop();
        assertEquals(oldTop, newTop);
    }

    @MediumTest
    public void testGoneToInvisible() throws Exception {
        getActivity().runOnUiThread(() -> mGone.requestFocus());
        getInstrumentation().waitForIdleSync();

        int oldTop = mVictim.getTop();

        sendKeys("DPAD_CENTER");
        assertEquals(View.GONE, mVictim.getVisibility());

        int refDownTop = mRefDown.getTop();
        assertEquals(oldTop, refDownTop);

        sendKeys(KEYCODE_DPAD_LEFT, KEYCODE_DPAD_CENTER);
        assertEquals(View.INVISIBLE, mVictim.getVisibility());

        int newTop = mVictim.getTop();
        assertEquals(oldTop, newTop);
    }

    @MediumTest
    public void testInvisibleToVisible() throws Exception {
        getActivity().runOnUiThread(() -> mInvisible.requestFocus());
        getInstrumentation().waitForIdleSync();

        int oldTop = mVictim.getTop();

        sendKeys("DPAD_CENTER");
        assertEquals(View.INVISIBLE, mVictim.getVisibility());

        int newTop = mVictim.getTop();
        assertEquals(oldTop, newTop);

        sendKeys("DPAD_LEFT DPAD_CENTER");
        assertEquals(View.VISIBLE, mVictim.getVisibility());

        newTop = mVictim.getTop();
        assertEquals(oldTop, newTop);
    }

    @MediumTest
    public void testInvisibleToGone() throws Exception {
        getActivity().runOnUiThread(() -> mInvisible.requestFocus());
        getInstrumentation().waitForIdleSync();

        int oldTop = mVictim.getTop();

        sendKeys("DPAD_CENTER");
        assertEquals(View.INVISIBLE, mVictim.getVisibility());

        int newTop = mVictim.getTop();
        assertEquals(oldTop, newTop);

        sendKeys("DPAD_RIGHT DPAD_CENTER");
        assertEquals(View.GONE, mVictim.getVisibility());

        int refDownTop = mRefDown.getTop();
        assertEquals(oldTop, refDownTop);
    }
}
