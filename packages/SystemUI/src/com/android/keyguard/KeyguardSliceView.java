/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard;

import android.app.PendingIntent;
import android.app.slice.Slice;
import android.app.slice.SliceItem;
import android.app.slice.SliceQuery;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.R;
import com.android.systemui.keyguard.KeyguardSliceProvider;

import java.util.Collections;

/**
 * View visible under the clock on the lock screen and AoD.
 */
public class KeyguardSliceView extends LinearLayout {

    private final Uri mKeyguardSliceUri;
    private TextView mTitle;
    private TextView mText;
    private Slice mSlice;
    private PendingIntent mSliceAction;
    private int mTextColor;
    private float mDarkAmount = 0;

    private final ContentObserver mObserver;

    public KeyguardSliceView(Context context) {
        this(context, null, 0);
    }

    public KeyguardSliceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardSliceView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mObserver = new KeyguardSliceObserver(new Handler());
        mKeyguardSliceUri = Uri.parse(KeyguardSliceProvider.KEYGUARD_SLICE_URI);;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTitle = findViewById(R.id.title);
        mText = findViewById(R.id.text);
        mTextColor = mTitle.getCurrentTextColor();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Set initial content
        showSlice(Slice.bindSlice(getContext().getContentResolver(), mKeyguardSliceUri,
                Collections.emptyList()));

        // Make sure we always have the most current slice
        getContext().getContentResolver().registerContentObserver(mKeyguardSliceUri,
                false /* notifyDescendants */, mObserver);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        getContext().getContentResolver().unregisterContentObserver(mObserver);
    }

    private void showSlice(Slice slice) {
        // Items will be wrapped into an action when they have tap targets.
        SliceItem actionSlice = SliceQuery.find(slice, SliceItem.FORMAT_ACTION);
        if (actionSlice != null) {
            mSlice = actionSlice.getSlice();
            mSliceAction = actionSlice.getAction();
        } else {
            mSlice = slice;
            mSliceAction = null;
        }

        if (mSlice == null) {
            setVisibility(GONE);
            return;
        }

        SliceItem title = SliceQuery.find(mSlice, SliceItem.FORMAT_TEXT, Slice.HINT_TITLE, null);
        if (title == null) {
            mTitle.setVisibility(GONE);
        } else {
            mTitle.setVisibility(VISIBLE);
            mTitle.setText(title.getText());
        }

        SliceItem text = SliceQuery.find(mSlice, SliceItem.FORMAT_TEXT, null, Slice.HINT_TITLE);
        if (text == null) {
            mText.setVisibility(GONE);
        } else {
            mText.setVisibility(VISIBLE);
            mText.setText(text.getText());
        }

        final int visibility = title == null && text == null ? GONE : VISIBLE;
        if (visibility != getVisibility()) {
            setVisibility(visibility);
        }
    }

    public void setDark(float darkAmount) {
        mDarkAmount = darkAmount;
        updateTextColors();
    }

    public void setTextColor(int textColor) {
        mTextColor = textColor;
    }

    private void updateTextColors() {
        final int blendedColor = ColorUtils.blendARGB(mTextColor, Color.WHITE, mDarkAmount);
        mTitle.setTextColor(blendedColor);
        mText.setTextColor(blendedColor);
    }

    private class KeyguardSliceObserver extends ContentObserver {
        KeyguardSliceObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            this.onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            showSlice(Slice.bindSlice(getContext().getContentResolver(), mKeyguardSliceUri,
                    Collections.emptyList()));
        }
    }
}
