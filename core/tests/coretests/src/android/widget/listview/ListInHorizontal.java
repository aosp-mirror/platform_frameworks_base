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

package android.widget.listview;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.ListView;

import com.android.frameworks.coretests.R;

/**
 * Exercises a list in a horizontal linear layout
 */
public class ListInHorizontal extends Activity {
    private ListView mListView;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.list_in_horizontal);

        String values[] = new String[1000];
        for (int i = 0; i < 1000; i++) {
            values[i] = ((Integer) i).toString();
        }

        mListView = findViewById(R.id.list);
        mListView.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, values));

    }

    public ListView getListView() {
        return mListView;
    }
}
