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

import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import com.android.frameworks.coretests.R;

/**
 * Exercises {@link android.view.View}'s ability to change visibility between
 * GONE, VISIBLE and INVISIBLE.
 */
public class VisibilityCallbackTest extends ActivityInstrumentationTestCase2<VisibilityCallback> {
    private TextView mRefUp;
    private TextView mRefDown;
    private VisibilityCallback.MonitoredTextView mVictim;
    private ViewGroup mParent;
    private Button mVisible;
    private Button mInvisible;
    private Button mGone;

    public VisibilityCallbackTest() {
        super("com.android.frameworks.coretests", VisibilityCallback.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        final VisibilityCallback a = getActivity();
        mRefUp = (TextView) a.findViewById(R.id.refUp);
        mRefDown = (TextView) a.findViewById(R.id.refDown);
        mVictim = (VisibilityCallback.MonitoredTextView) a.findViewById(R.id.victim);
        mParent = (ViewGroup) a.findViewById(R.id.parent);
        mVisible = (Button) a.findViewById(R.id.vis);
        mInvisible = (Button) a.findViewById(R.id.invis);
        mGone = (Button) a.findViewById(R.id.gone);

        mVictim.post(new Runnable() {
            public void run() {
                mVictim.setVisibility(View.INVISIBLE);
            }
        });
        getInstrumentation().waitForIdleSync();
     }

    @MediumTest
    @UiThreadTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mRefUp);
        assertNotNull(mRefDown);
        assertNotNull(mVictim);
        assertNotNull(mVisible);
        assertNotNull(mInvisible);
        assertNotNull(mGone);

        assertTrue(mVisible.hasFocus());
        assertEquals(View.INVISIBLE, mVictim.getVisibility());
        assertEquals(View.VISIBLE, mParent.getVisibility());
    }

    @MediumTest
    @UiThreadTest
    public void testDirect() throws Exception {
        mVictim.setVisibility(View.VISIBLE);
        assertEquals(View.VISIBLE, mVictim.getLastChangedVisibility());
        assertEquals(mVictim, mVictim.getLastVisChangedView());

        mVictim.setVisibility(View.INVISIBLE);
        assertEquals(View.INVISIBLE, mVictim.getLastChangedVisibility());
        assertEquals(mVictim, mVictim.getLastVisChangedView());

        mVictim.setVisibility(View.GONE);
        assertEquals(View.GONE, mVictim.getLastChangedVisibility());
        assertEquals(mVictim, mVictim.getLastVisChangedView());
    }

    @MediumTest
    @UiThreadTest
    public void testChild() throws Exception {
        mParent.setVisibility(View.INVISIBLE);
        assertEquals(View.INVISIBLE, mVictim.getLastChangedVisibility());
        assertEquals(mParent, mVictim.getLastVisChangedView());

        mParent.setVisibility(View.GONE);
        assertEquals(View.GONE, mVictim.getLastChangedVisibility());
        assertEquals(mParent, mVictim.getLastVisChangedView());

        mParent.setVisibility(View.VISIBLE);
        assertEquals(View.VISIBLE, mVictim.getLastChangedVisibility());
        assertEquals(mParent, mVictim.getLastVisChangedView());
    }
}
