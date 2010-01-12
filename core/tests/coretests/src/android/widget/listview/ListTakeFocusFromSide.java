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

import com.android.frameworks.coretests.R;

import android.app.ListActivity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.BaseAdapter;
import android.widget.TextView;

/**
 * Exercises moving focus into the list from the side
 */
public class ListTakeFocusFromSide extends ListActivity {

    
    private class ThrashListAdapter extends BaseAdapter {
        private LayoutInflater mInflater;

        private String[] mTitles = new String[100];

        public ThrashListAdapter(Context context) {
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mTitles = new String[100];

            int i;
            for (i = 0; i < 100; i++) {
                mTitles[i] = "[" + i + "]";
            }
        }

        public int getCount() {
            return mTitles.length;
        }

        public Object getItem(int position) {
            return position;
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view;

            if (convertView == null) {
                view = (TextView) mInflater.inflate(android.R.layout.simple_list_item_1, null);
            } else {
                view = (TextView) convertView;
            }
            view.setText(mTitles[position]);
            return view;
        }

    }
  
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.list_take_focus_from_side);
        setListAdapter(new ThrashListAdapter(this));
    }
}
