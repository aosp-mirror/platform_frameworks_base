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

import android.app.ListActivity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.android.frameworks.coretests.R;

/**
 * Exercises change notification in a list
 */
public class ListScrollListener extends ListActivity implements AbsListView.OnScrollListener {
    Handler mHandler = new Handler();
    TextView mText;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.list_scroll_listener);

        String values[] = new String[1000];
        int i=0;
        for(i=0; i<1000; i++) {
            values[i] = ((Integer)i).toString();
        }
        
        mText = findViewById(R.id.text);
        setListAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, values));


        getListView().setOnScrollListener(this);
    }

    public void onItemSelected(AdapterView parent, View v, int position, long id) {
        mText.setText("Position " + position);
    }

    public void onNothingSelected(AdapterView parent) {
        mText.setText("Nothing");
    }

    public void onScroll(AbsListView view, int firstCell, int cellCount, int itemCount) {
        int last = firstCell + cellCount - 1;
        mText.setText("Showing " + firstCell + "-" + last + "/" + itemCount);
    }

    public void onScrollStateChanged(AbsListView view, int scrollState) {        
    }
}
