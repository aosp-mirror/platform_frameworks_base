/*
* Copyright (C) 2011-2014 The Android Open Source Project.
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

package com.android.internal.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import java.util.List;

public class AccountViewAdapter extends BaseAdapter {

    private List<AccountElements> mData;
    private Context mContext;

    /**
     * Constructor
     *
     * @param context The context where the View associated with this Adapter is running
     * @param data A list with AccountElements data type. The list contains the data of each
     *         account and the each member of AccountElements will correspond to one item view.
     */
    public AccountViewAdapter(Context context, final List<AccountElements> data) {
        mContext = context;
        mData = data;
    }

    @Override
    public int getCount() {
        return mData.size();
    }

    @Override
    public Object getItem(int position) {
        return mData.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void updateData(final List<AccountElements> data) {
        mData = data;
        notifyDataSetChanged();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        AccountItemView view;
        if (convertView == null) {
            view = new AccountItemView(mContext);
        } else {
            view = (AccountItemView) convertView;
        }
        AccountElements elements = (AccountElements) getItem(position);
        view.setViewItem(elements);
        return view;
    }

    public static class AccountElements {
        private int mIcon;
        private Drawable mDrawable;
        private String mName;
        private String mNumber;

        /**
         * Constructor
         * A structure with basic element of an Account, icon, name and number
         *
         * @param icon Account icon id
         * @param name Account name
         * @param num Account number
         */
        public AccountElements(int icon, String name, String number) {
            this(icon, null, name, number);
        }

        /**
         * Constructor
         * A structure with basic element of an Account, drawable, name and number
         *
         * @param drawable Account drawable
         * @param name Account name
         * @param num Account number
         */
        public AccountElements(Drawable drawable, String name, String number) {
            this(0, drawable, name, number);
        }

        private AccountElements(int icon, Drawable drawable, String name, String number) {
            mIcon = icon;
            mDrawable = drawable;
            mName = name;
            mNumber = number;
        }

        public int getIcon() {
            return mIcon;
        }
        public String getName() {
            return mName;
        }
        public String getNumber() {
            return mNumber;
        }
        public Drawable getDrawable() {
            return mDrawable;
        }
    }
}
