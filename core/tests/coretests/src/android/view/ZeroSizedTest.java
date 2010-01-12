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

import android.view.ZeroSized;
import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.View;
import android.graphics.Bitmap;

/**
 * Builds the drawing cache of Views of various dimension. The assumption is that
 * a View with a 0-sized dimension (width or height) will always have a null
 * drawing cache.
 */
public class ZeroSizedTest extends ActivityInstrumentationTestCase<ZeroSized> {
    private View mWithDimension;
    private View mWithNoWdith;
    private View mWithNoHeight;
    private View mWithNoDimension;

    public ZeroSizedTest() {
        super("com.android.frameworks.coretests", ZeroSized.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        final ZeroSized activity = getActivity();
        mWithDimension = activity.findViewById(R.id.dimension);
        mWithNoWdith = activity.findViewById(R.id.noWidth);
        mWithNoHeight = activity.findViewById(R.id.noHeight);
        mWithNoDimension = activity.findViewById(R.id.noDimension);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mWithDimension);
        assertNotNull(mWithNoWdith);
        assertNotNull(mWithNoHeight);
        assertNotNull(mWithNoDimension);
    }

    @MediumTest
    public void testDrawingCacheWithDimension() throws Exception {
        assertTrue(mWithDimension.getWidth() > 0);
        assertTrue(mWithDimension.getHeight() > 0);
        assertNotNull(createCacheForView(mWithDimension));
    }

    @MediumTest
    public void testDrawingCacheWithNoWidth() throws Exception {
        assertTrue(mWithNoWdith.getWidth() == 0);
        assertTrue(mWithNoWdith.getHeight() > 0);
        assertNull(createCacheForView(mWithNoWdith));
    }

    @MediumTest
    public void testDrawingCacheWithNoHeight() throws Exception {
        assertTrue(mWithNoHeight.getWidth() > 0);
        assertTrue(mWithNoHeight.getHeight() == 0);
        assertNull(createCacheForView(mWithNoHeight));
    }

    @MediumTest
    public void testDrawingCacheWithNoDimension() throws Exception {
        assertTrue(mWithNoDimension.getWidth() == 0);
        assertTrue(mWithNoDimension.getHeight() == 0);
        assertNull(createCacheForView(mWithNoDimension));
    }

    private Bitmap createCacheForView(final View view) {
        final Bitmap[] cache = new Bitmap[1];
        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                view.setDrawingCacheEnabled(true);
                view.invalidate();
                view.buildDrawingCache();
                cache[0] = view.getDrawingCache();
            }
        });
        getInstrumentation().waitForIdleSync();
        return cache[0];
    }
}
