/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.carrier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class QSCarrierTest extends SysuiTestCase {

    private QSCarrier mQSCarrier;
    private TestableLooper mTestableLooper;

    @Before
    public void setUp() throws Exception {
        mTestableLooper = TestableLooper.get(this);
        LayoutInflater inflater = LayoutInflater.from(mContext);
        mTestableLooper.runWithLooper(() ->
                mQSCarrier = (QSCarrier) inflater.inflate(R.layout.qs_carrier, null));
    }

    @Test
    public void testUpdateState_first() {
        CellSignalState c = new CellSignalState(true, 0, "", "", false);

        assertTrue(mQSCarrier.updateState(c));
    }

    @Test
    public void testUpdateState_same() {
        CellSignalState c = new CellSignalState(true, 0, "", "", false);

        assertTrue(mQSCarrier.updateState(c));
        assertFalse(mQSCarrier.updateState(c));
    }

    @Test
    public void testUpdateState_changed() {
        CellSignalState c = new CellSignalState(true, 0, "", "", false);

        assertTrue(mQSCarrier.updateState(c));

        CellSignalState other = c.changeVisibility(false);

        assertTrue(mQSCarrier.updateState(other));
    }
}
