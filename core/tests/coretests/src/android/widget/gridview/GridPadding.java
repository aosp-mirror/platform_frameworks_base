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

package android.widget.gridview;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.GridView;

import com.android.frameworks.coretests.R;

/**
 * Exercises a grid with padding
 */
public class GridPadding extends Activity {
    private GridView mGridView;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.grid_padding);

        String values[] = new String[1000];
        for(int i = 0; i < 1000; i++) {
            values[i] = String.valueOf(i);
        }

        mGridView = (GridView) findViewById(R.id.grid);
        mGridView.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, values));
    }

    public GridView getGridView() {
        return mGridView;
    }

}
