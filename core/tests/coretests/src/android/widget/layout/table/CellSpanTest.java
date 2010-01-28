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

import android.widget.layout.table.CellSpan;
import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.View;

/**
 * {@link android.widget.layout.table.CellSpan} is
 * setup to exercise tables in which cells use spanning.
 */
public class CellSpanTest extends ActivityInstrumentationTestCase<CellSpan> {
    private View mA;
    private View mB;
    private View mC;
    private View mSpanThenCell;
    private View mCellThenSpan;
    private View mSpan;

    public CellSpanTest() {
        super("com.android.frameworks.coretests", CellSpan.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        final CellSpan activity = getActivity();
        mA            = activity.findViewById(R.id.a);
        mB            = activity.findViewById(R.id.b);
        mC            = activity.findViewById(R.id.c);
        mSpanThenCell = activity.findViewById(R.id.spanThenCell);
        mCellThenSpan = activity.findViewById(R.id.cellThenSpan);
        mSpan         = activity.findViewById(R.id.span);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mA);
        assertNotNull(mB);
        assertNotNull(mC);
        assertNotNull(mSpanThenCell);
        assertNotNull(mCellThenSpan);
        assertNotNull(mSpan);
    }

    @MediumTest
    public void testSpanThenCell() throws Exception {
        int spanWidth = mA.getMeasuredWidth() + mB.getMeasuredWidth();
        assertEquals("span followed by cell is broken", spanWidth,
                mSpanThenCell.getMeasuredWidth());
    }

    @MediumTest
    public void testCellThenSpan() throws Exception {
        int spanWidth = mB.getMeasuredWidth() + mC.getMeasuredWidth();
        assertEquals("cell followed by span is broken", spanWidth,
                mCellThenSpan.getMeasuredWidth());
    }

    @MediumTest
    public void testSpan() throws Exception {
        int spanWidth = mA.getMeasuredWidth() + mB.getMeasuredWidth() +
                mC.getMeasuredWidth();
        assertEquals("span is broken", spanWidth, mSpan.getMeasuredWidth());
    }
}
