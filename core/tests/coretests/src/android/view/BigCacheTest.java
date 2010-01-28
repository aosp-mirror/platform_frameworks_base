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

import android.view.BigCache;
import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.View;
import android.view.ViewConfiguration;
import android.graphics.Bitmap;

/**
 * Builds the drawing cache of two Views, one smaller than the maximum cache size,
 * one larger than the maximum cache size. The latter should always have a null
 * drawing cache.
 */
public class BigCacheTest extends ActivityInstrumentationTestCase<BigCache> {
    private View mTiny;
    private View mLarge;

    public BigCacheTest() {
        super("com.android.frameworks.coretests", BigCache.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        final BigCache activity = getActivity();
        mTiny = activity.findViewById(R.id.a);
        mLarge = activity.findViewById(R.id.b);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mTiny);
        assertNotNull(mLarge);
    }

    @MediumTest
    public void testDrawingCacheBelowMaximumSize() throws Exception {
        final int max = ViewConfiguration.get(getActivity()).getScaledMaximumDrawingCacheSize();
        assertTrue(mTiny.getWidth() * mTiny.getHeight() * 2 < max);
        assertNotNull(createCacheForView(mTiny));
    }

    // TODO: needs to be adjusted to pass on non-HVGA displays
    // @MediumTest
    public void testDrawingCacheAboveMaximumSize() throws Exception {
        final int max = ViewConfiguration.get(getActivity()).getScaledMaximumDrawingCacheSize();
        assertTrue(mLarge.getWidth() * mLarge.getHeight() * 2 > max);
        assertNull(createCacheForView(mLarge));
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
