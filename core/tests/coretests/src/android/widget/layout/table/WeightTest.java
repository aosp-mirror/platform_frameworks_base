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

package android.widget.layout.table;

import android.test.ActivityInstrumentationTestCase;
import android.view.View;

import androidx.test.filters.MediumTest;

import com.android.frameworks.coretests.R;

/**
 * {@link android.widget.layout.table.Weight} is
 * setup to exercise tables in which cells use a weight.
 */
public class WeightTest extends ActivityInstrumentationTestCase<Weight> {
    private View mCell1;
    private View mCell2;
    private View mCell3;
    private View mRow;

    public WeightTest() {
        super("com.android.frameworks.coretests", Weight.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        final Weight activity = getActivity();
        mCell1 = activity.findViewById(R.id.cell1);
        mCell3 = activity.findViewById(R.id.cell2);
        mCell2 = activity.findViewById(R.id.cell3);
        mRow = activity.findViewById(R.id.row);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mCell1);
        assertNotNull(mCell2);
        assertNotNull(mCell3);
        assertNotNull(mRow);
    }

    @MediumTest
    public void testAllCellsFillParent() throws Exception {
        assertEquals(mCell1.getWidth() + mCell2.getWidth() + mCell3.getWidth(), mRow.getWidth());
    }
}
