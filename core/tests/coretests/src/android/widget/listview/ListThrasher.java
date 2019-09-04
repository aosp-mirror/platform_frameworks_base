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
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.android.frameworks.coretests.R;

import java.util.Random;

/**
 * Exercises change notification in a list
 */
public class ListThrasher extends ListActivity implements AdapterView.OnItemSelectedListener {
    Handler mHandler = new Handler();
    ThrashListAdapter mAdapter;
    Random mRandomizer = new Random();
    TextView mText;

    Runnable mThrash = new Runnable() {
        public void run() {
            mAdapter.bumpVersion();
            mHandler.postDelayed(mThrash, 500);
        }
    };

    private class ThrashListAdapter extends BaseAdapter {
        private LayoutInflater mInflater;

        /**
         * Our data, part 1.
         */
        private String[] mTitles = new String[100];

        /**
         * Our data, part 2.
         */
        private int[] mVersion = new int[100];

        public ThrashListAdapter(Context context) {
            mInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mTitles = new String[100];
            mVersion = new int[100];

            int i;
            for (i = 0; i < 100; i++) {
                mTitles[i] = "[" + i + "]";
                mVersion[i] = 0;
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
            view.setText(mTitles[position] + " " + mVersion[position]);
            return view;
        }


        public void bumpVersion() {
            int position = mRandomizer.nextInt(getCount());
            mVersion[position]++;
            notifyDataSetChanged();
        }


    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.list_thrasher);

        mText = findViewById(R.id.text);
        mAdapter = new ThrashListAdapter(this);
        setListAdapter(mAdapter);

        mHandler.postDelayed(mThrash, 5000);

        getListView().setOnItemSelectedListener(this);
    }

    public void onItemSelected(AdapterView parent, View v, int position, long id) {
        mText.setText("Position " + position);
    }

    public void onNothingSelected(AdapterView parent) {
        mText.setText("Nothing");
    }
}
