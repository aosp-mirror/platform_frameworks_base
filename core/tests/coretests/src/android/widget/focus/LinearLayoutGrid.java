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

package android.widget.focus;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import com.android.frameworks.coretests.R;

public class LinearLayoutGrid extends Activity {


    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.linear_layout_grid);
    }

    public ViewGroup getRootView() {
        return (ViewGroup) findViewById(R.id.layout);
    }

    public Button getButtonAt(int column, int row) {
        if (row < 0 || row > 2) {
            throw new IllegalArgumentException("row out of range");
        }
        if (column < 0 || column > 2) {
            throw new IllegalArgumentException("column out of range");
        }
        return (Button) getColumn(column).getChildAt(row);
    }



    private LinearLayout getColumn(int column) {
        switch (column) {
            case 0:
                return (LinearLayout) findViewById(R.id.column1);
            case 1:
                return (LinearLayout) findViewById(R.id.column2);
            case 2:
                return (LinearLayout) findViewById(R.id.column3);
            default:
                throw new IllegalArgumentException("column out of range");
        }
    }


}
