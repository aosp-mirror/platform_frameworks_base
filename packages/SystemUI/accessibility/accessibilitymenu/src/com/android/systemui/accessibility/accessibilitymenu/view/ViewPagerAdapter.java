/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.accessibilitymenu.view;

import android.view.View;
import android.view.ViewGroup;

import androidx.viewpager.widget.PagerAdapter;

import java.util.List;

/** The pager adapter, which provides the pages to the view pager widget. */
class ViewPagerAdapter<T extends View> extends PagerAdapter {

    /** The widget list in each page of view pager. */
    private List<T> mWidgetList;

    ViewPagerAdapter() {}

    public void set(List<T> tList) {
        mWidgetList = tList;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        if (mWidgetList == null) {
            return 0;
        }
        return mWidgetList.size();
    }

    @Override
    public int getItemPosition(Object object) {
        return POSITION_NONE;
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view == object;
    }

    @Override
    public Object instantiateItem(ViewGroup container, int position) {
        if (mWidgetList == null) {
            return null;
        }
        container.addView(mWidgetList.get(position));
        return mWidgetList.get(position);
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        container.removeView((View) object);
    }
}
