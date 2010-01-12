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

import android.app.ListActivity;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.collect.Lists;
import com.android.frameworks.coretests.R;

import java.util.List;

public class ListWithFooterViewAndNewLabels extends ListActivity {

    private MyAdapter mMyAdapter;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.list_with_button_above);

        Button footerButton = new Button(this);
        footerButton.setText("hi");
        footerButton.setLayoutParams(
                new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        getListView().addFooterView(footerButton);

        mMyAdapter = new MyAdapter(this);
        setListAdapter(mMyAdapter);

        // not in list
        Button topButton = (Button) findViewById(R.id.button);
        topButton.setText("click to add new item");
        topButton.setOnClickListener(new View.OnClickListener() {

            public void onClick(View v) {
                mMyAdapter.addLabel("yo");
            }
        });

        mMyAdapter.addLabel("first");
    }

    /**
     * An adapter that can take new string labels.
     */
    static class MyAdapter extends BaseAdapter {

        private final Context mContext;
        private List<String> mLabels = Lists.newArrayList();

        public MyAdapter(Context context) {
            mContext = context;
        }

        public int getCount() {
            return mLabels.size();
        }

        public Object getItem(int position) {
            return mLabels.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            String label = mLabels.get(position);

            LayoutInflater inflater = (LayoutInflater)
                    mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            TextView tv = (TextView) inflater.inflate(
                    android.R.layout.simple_list_item_1,
                    null);
            tv.setText(label);
            return tv;
        }

        public void addLabel(String s) {
            mLabels.add(s + mLabels.size());
            notifyDataSetChanged();
        }
    }
}
