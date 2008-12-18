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

package com.android.unit_tests.content;

import android.content.res.Resources;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.TypedValue;
import com.android.unit_tests.R;

public class FractionTest extends AndroidTestCase {

    private Resources mResources;
    private final TypedValue mValue = new TypedValue();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mResources = mContext.getResources();
    }

    @SmallTest
    public void testFractions() throws Exception {
        tryFraction(R.dimen.frac100perc, 1, 1, 1);
        tryFraction(R.dimen.frac1perc, 1, 1, .01f);
        tryFraction(R.dimen.fracp1perc, 1, 1, .001f);
        tryFraction(R.dimen.fracp01perc, 1, 1, .0001f);
        tryFraction(R.dimen.frac0perc, 1, 1, 0);
        tryFraction(R.dimen.frac1p1perc, 1, 1, .011f);
        tryFraction(R.dimen.frac100p1perc, 1, 1, 1.001f);
        tryFraction(R.dimen.frac25510perc, 1, 1, 255.1f);
        tryFraction(R.dimen.frac25610perc, 1, 1, 256.1f);
        tryFraction(R.dimen.frac6553510perc, 1, 1, 65535.1f);
        tryFraction(R.dimen.frac6553610perc, 1, 1, 65536.1f);

        tryFraction(R.dimen.frac100perc, 100, 1, 100);
        tryFraction(R.dimen.frac1perc, 100, 1, .01f * 100);
        tryFraction(R.dimen.fracp1perc, 100, 1, .001f * 100);
        tryFraction(R.dimen.fracp01perc, 100, 1, .0001f * 100);
        tryFraction(R.dimen.frac0perc, 100, 1, 0);
        tryFraction(R.dimen.frac1p1perc, 100, 1, .011f * 100);
        tryFraction(R.dimen.frac100p1perc, 100, 1, 1.001f * 100);
        tryFraction(R.dimen.frac25510perc, 100, 1, 255.1f * 100);
        tryFraction(R.dimen.frac25610perc, 100, 1, 256.1f * 100);
        tryFraction(R.dimen.frac6553510perc, 100, 1, 65535.1f * 100);
        tryFraction(R.dimen.frac6553610perc, 100, 1, 65536.1f * 100);

        tryFraction(R.dimen.frac100pperc, 100, 2, 2);
        tryFraction(R.dimen.frac1pperc, 100, 2, .01f * 2);
        tryFraction(R.dimen.fracp1pperc, 100, 2, .001f * 2);
        tryFraction(R.dimen.fracp01pperc, 100, 2, .0001f * 2);
        tryFraction(R.dimen.frac0pperc, 100, 2, 0);
        tryFraction(R.dimen.frac1p1pperc, 100, 2, .011f * 2);
        tryFraction(R.dimen.frac100p1pperc, 100, 2, 1.001f * 2);
        tryFraction(R.dimen.frac25510pperc, 100, 2, 255.1f * 2);
        tryFraction(R.dimen.frac25610pperc, 100, 2, 256.1f * 2);
        tryFraction(R.dimen.frac6553510pperc, 100, 2, 65535.1f * 2);
        tryFraction(R.dimen.frac6553610pperc, 100, 2, 65536.1f * 2);
    }

    private void tryFraction(int resid, float base, float pbase, float expected) {
        mResources.getValue(resid, mValue, true);
        float res = mValue.getFraction(base, pbase);
        float diff = Math.abs(expected - res);
        float prec = expected * 1e-4f;
        if (prec < 1e-5f) {
            prec = 1e-5f;
        }
        //System.out.println(
        //    "Res 0x" + Integer.toHexString(resid) + ": got=" + res
        //    + ", expected=" + expected + ", diff=" + diff);
        assertFalse("Expecting value " + expected + " got " + res
                            + ": in resource 0x" + Integer.toHexString(resid)
                            + " " + mValue,
                diff > prec);
    }
}

