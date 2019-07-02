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

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.android.frameworks.coretests.R;

/**
 * This test adds an extra row with an extra column in the table.
 */
public class AddColumn extends Activity {
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.add_column_in_table);

        final Button addRowButton = (Button) findViewById(R.id.add_row_button);
        addRowButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final TableLayout table = (TableLayout) findViewById(R.id.table);
                final TableRow newRow = new TableRow(AddColumn.this);
                for (int i = 0; i < 4; i++) {
                    final TextView view = new TextView(AddColumn.this);
                    view.setText("Column " + (i + 1));
                    view.setPadding(3, 3, 3, 3);
                    newRow.addView(view, new TableRow.LayoutParams());
                }
                table.addView(newRow, new TableLayout.LayoutParams());
                newRow.requestLayout();
            }
        });
    }
}
