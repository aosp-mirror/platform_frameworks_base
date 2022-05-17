/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.view;

import android.annotation.UiThread;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

public class ListViewCaptureHelperTest
        extends AbsCaptureHelperTest<ListView, ListViewCaptureHelper> {

    static final int CHILD_VIEW_HEIGHT = 300;
    static final int CHILD_VIEW_COUNT = CONTENT_HEIGHT / CHILD_VIEW_HEIGHT;

    @Override
    protected ListViewCaptureHelper createHelper() {
        return new ListViewCaptureHelper();
    }

    @Override
    protected ListView createScrollableContent(ViewGroup parent) {
        ListView listView = new ListView(parent.getContext());
        listView.setDivider(null);
        listView.setAdapter(new TestAdapter());
        return listView;
    }

    @UiThread
    protected void setInitialScrollPosition(ListView target, ScrollPosition position) {
        int offset = 0;
        switch (position) {
            case MIDDLE:
                offset = WINDOW_HEIGHT;
                break;
            case BOTTOM:
                offset = WINDOW_HEIGHT * 2;
                break;
        }
        int verticalPadding = target.getPaddingTop() + target.getPaddingBottom();
        int step = (target.getHeight() - verticalPadding) / 2;
        // ListView#scrollListBy will not scroll more than one screen height per call
        while (offset > step) {
            target.scrollListBy(step);
            offset -= step;
        }
        target.scrollListBy(offset);
    }

    static final class TestAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return CHILD_VIEW_COUNT;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return 0;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = (convertView != null)
                    ? (TextView) convertView : new TextView(parent.getContext());
            view.setText("Item #" + position);
            view.setTextColor(Color.WHITE);
            view.setTextSize(20f);
            view.setBackgroundColor(ITEM_COLORS[position % ITEM_COLORS.length]);
            view.setMinHeight(CHILD_VIEW_HEIGHT);
            return view;
        }
    }

}
