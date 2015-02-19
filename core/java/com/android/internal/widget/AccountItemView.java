/*
* Copyright (C) 2011-2014 The Android Open Source Project
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
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.widget.AccountViewAdapter.AccountElements;


/**
 * An LinearLayout view, to show Accounts elements.
 */
public class AccountItemView extends LinearLayout {

    private ImageView mAccountIcon;
    private TextView mAccountName;
    private TextView mAccountNumber;

    /**
     * Constructor.
     */
    public AccountItemView(Context context) {
        this(context, null);
    }

    /**
     * Constructor.
     */
    public AccountItemView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater inflator = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflator.inflate(R.layout.simple_account_item, null);
        addView(view);
        initViewItem(view);
    }

    private void initViewItem(View view) {
        mAccountIcon = (ImageView)view.findViewById(android.R.id.icon);
        mAccountName = (TextView)view.findViewById(android.R.id.title);
        mAccountNumber = (TextView)view.findViewById(android.R.id.summary);
    }

    public void setViewItem(AccountElements element) {
        Drawable drawable = element.getDrawable();
        if (drawable != null) {
            setAccountIcon(drawable);
        } else {
            setAccountIcon(element.getIcon());
        }
        setAccountName(element.getName());
        setAccountNumber(element.getNumber());
    }

    public void setAccountIcon(int resId) {
        mAccountIcon.setImageResource(resId);
    }

    public void setAccountIcon(Drawable drawable) {
        mAccountIcon.setBackgroundDrawable(drawable);
    }

    public void setAccountName(String name) {
        setText(mAccountName, name);
    }

    public void setAccountNumber(String number) {
        setText(mAccountNumber, number);
    }

    private void setText(TextView view, String text) {
        if (TextUtils.isEmpty(text)) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(text);
            view.setVisibility(View.VISIBLE);
        }
    }
}
