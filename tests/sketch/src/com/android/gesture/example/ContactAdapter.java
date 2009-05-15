/*
 * Copyright (C) 2008-2009 The Android Open Source Project
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

package com.android.gesture.example;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.ArrayList;

class ContactAdapter extends ArrayAdapter<ContactItem> {

    private LayoutInflater mInflater;

    public ContactAdapter(Activity activity, ArrayList<ContactItem> contacts) {
        super(activity, 0, contacts);
        mInflater = activity.getLayoutInflater();
    }

    @Override
    public ContactItem getItem(int position) {
        return super.getItem(position);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).itemID;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final ContactItem info = getItem(position);

        View view = convertView;
        if (view == null) {
            view = mInflater.inflate(android.R.layout.simple_list_item_1, parent, false);
            view.setTag(view.findViewById(android.R.id.text1));
        }

        final TextView textView = (TextView)view.getTag();
        textView.setText(info.toString());

        return view;
    }

    public int search(String query) {
        if (query != null && query.length() > 0) {
            int start = 0;
            int end = getCount() - 1;
            int index = binarySearch(query, start, end);
            for (index = index - 1; index >= 0; index--) {
                String str = getItem(index).toString().toLowerCase();
                if (!str.startsWith(query)) {
                    return index + 1;
                }
                if (index == 0) {
                    return 0;
                }
            }
            return -1;
        } else {
            return -1;
        }
    }

    private int binarySearch(String prefix, int start, int end) {
        if (start > end) {
            return -1;
        }
        int mid = (start + end) / 2;
        String str = getItem(mid).toString().toLowerCase();
        if (prefix.compareTo(str) <= 0) {
            if (str.startsWith(prefix)) {
                return mid;
            } else {
                return binarySearch(prefix, start, mid - 1);
            }
        } else {
            return binarySearch(prefix, mid + 1, end);
        }
    }

}
