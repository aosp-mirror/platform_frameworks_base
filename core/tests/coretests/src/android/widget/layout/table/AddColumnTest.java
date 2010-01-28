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

import android.widget.layout.table.AddColumn;
import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.TableLayout;
import android.widget.TableRow;

/**
 * {@link android.widget.layout.table.AddColumn} is
 * setup to exercise the case of adding row programmatically in a table.
 */
public class AddColumnTest extends ActivityInstrumentationTestCase<AddColumn> {
    private Button mAddRow;
    private TableLayout mTable;

    public AddColumnTest() {
        super("com.android.frameworks.coretests", AddColumn.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        final AddColumn activity = getActivity();
        mAddRow = (Button) activity.findViewById(R.id.add_row_button);
        mTable = (TableLayout) activity.findViewById(R.id.table);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mAddRow);
        assertNotNull(mTable);
        assertTrue(mAddRow.hasFocus());
    }

    @MediumTest
    public void testWidths() throws Exception {
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();

        TableRow row1 = (TableRow) mTable.getChildAt(0);
        TableRow row2 = (TableRow) mTable.getChildAt(1);

        assertTrue(row1.getChildCount() < row2.getChildCount());

        for (int i = 0; i < row1.getChildCount(); i++) {
            assertEquals(row2.getChildAt(i).getWidth(), row1.getChildAt(i).getWidth());
        }
    }
}
