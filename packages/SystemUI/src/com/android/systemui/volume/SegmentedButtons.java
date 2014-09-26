/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.volume;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

import java.util.Objects;

public class SegmentedButtons extends LinearLayout {
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    private static final int LABEL_RES_KEY = R.id.label;

    private final Context mContext;
    private final LayoutInflater mInflater;

    private Callback mCallback;
    private Object mSelectedValue;

    public SegmentedButtons(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mInflater = LayoutInflater.from(mContext);
        setOrientation(HORIZONTAL);
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public Object getSelectedValue() {
        return mSelectedValue;
    }

    public void setSelectedValue(Object value) {
        if (Objects.equals(value, mSelectedValue)) return;
        mSelectedValue = value;
        for (int i = 0; i < getChildCount(); i++) {
            final TextView c = (TextView) getChildAt(i);
            final Object tag = c.getTag();
            final boolean selected = Objects.equals(mSelectedValue, tag);
            c.setSelected(selected);
            c.setTypeface(selected ? BOLD : MEDIUM);
        }
        fireOnSelected();
    }

    public void addButton(int labelResId, Object value) {
        final Button b = (Button) mInflater.inflate(R.layout.segmented_button, this, false);
        b.setTag(LABEL_RES_KEY, labelResId);
        b.setText(labelResId);
        final LayoutParams lp = (LayoutParams) b.getLayoutParams();
        if (getChildCount() == 0) {
            lp.leftMargin = lp.rightMargin = 0; // first button has no margin
        }
        b.setLayoutParams(lp);
        addView(b);
        b.setTag(value);
        b.setOnClickListener(mClick);
        Interaction.register(b, new Interaction.Callback() {
            @Override
            public void onInteraction() {
                fireInteraction();
            }
        });
    }

    public void updateLocale() {
        for (int i = 0; i < getChildCount(); i++) {
            final Button b = (Button) getChildAt(i);
            final int labelResId = (Integer) b.getTag(LABEL_RES_KEY);
            b.setText(labelResId);
        }
    }

    private void fireOnSelected() {
        if (mCallback != null) {
            mCallback.onSelected(mSelectedValue);
        }
    }

    private void fireInteraction() {
        if (mCallback != null) {
            mCallback.onInteraction();
        }
    }

    private final View.OnClickListener mClick = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            setSelectedValue(v.getTag());
        }
    };

    public interface Callback extends Interaction.Callback {
        void onSelected(Object value);
    }
}
