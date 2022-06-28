/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.AlphaOptimizedLinearLayout;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry.OnSensitivityChangedListener;

import java.util.ArrayList;


/**
 * The view in the statusBar that contains part of the heads-up information
 */
public class HeadsUpStatusBarView extends AlphaOptimizedLinearLayout {
    private static final String HEADS_UP_STATUS_BAR_VIEW_SUPER_PARCELABLE =
            "heads_up_status_bar_view_super_parcelable";
    private static final String VISIBILITY = "visibility";
    private static final String ALPHA = "alpha";
    private final Rect mLayoutedIconRect = new Rect();
    private final int[] mTmpPosition = new int[2];
    private final Rect mIconDrawingRect = new Rect();
    private View mIconPlaceholder;
    private TextView mTextView;
    private NotificationEntry mShowingEntry;
    private Runnable mOnDrawingRectChangedListener;

    public HeadsUpStatusBarView(Context context) {
        this(context, null);
    }

    public HeadsUpStatusBarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HeadsUpStatusBarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public HeadsUpStatusBarView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public Bundle onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putParcelable(HEADS_UP_STATUS_BAR_VIEW_SUPER_PARCELABLE,
                super.onSaveInstanceState());
        bundle.putInt(VISIBILITY, getVisibility());
        bundle.putFloat(ALPHA, getAlpha());

        return bundle;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof Bundle)) {
            super.onRestoreInstanceState(state);
            return;
        }

        Bundle bundle = (Bundle) state;
        Parcelable superState = bundle.getParcelable(HEADS_UP_STATUS_BAR_VIEW_SUPER_PARCELABLE);
        super.onRestoreInstanceState(superState);
        if (bundle.containsKey(VISIBILITY)) {
            setVisibility(bundle.getInt(VISIBILITY));
        }
        if (bundle.containsKey(ALPHA)) {
            setAlpha(bundle.getFloat(ALPHA));
        }
    }

    @VisibleForTesting
    public HeadsUpStatusBarView(Context context, View iconPlaceholder, TextView textView) {
        this(context);
        mIconPlaceholder = iconPlaceholder;
        mTextView = textView;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mIconPlaceholder = findViewById(R.id.icon_placeholder);
        mTextView = findViewById(R.id.text);
    }

    public void setEntry(NotificationEntry entry) {
        if (mShowingEntry != null) {
            mShowingEntry.removeOnSensitivityChangedListener(mOnSensitivityChangedListener);
        }
        mShowingEntry = entry;

        if (mShowingEntry != null) {
            CharSequence text = entry.headsUpStatusBarText;
            if (entry.isSensitive()) {
                text = entry.headsUpStatusBarTextPublic;
            }
            mTextView.setText(text);
            mShowingEntry.addOnSensitivityChangedListener(mOnSensitivityChangedListener);
        }
    }

    private final OnSensitivityChangedListener mOnSensitivityChangedListener = entry -> {
        if (entry != mShowingEntry) {
            throw new IllegalStateException("Got a sensitivity change for " + entry
                    + " but mShowingEntry is " + mShowingEntry);
        }
        // Update the text
        setEntry(entry);
    };

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mIconPlaceholder.getLocationOnScreen(mTmpPosition);
        int left = mTmpPosition[0];
        int top = mTmpPosition[1];
        int right = left + mIconPlaceholder.getWidth();
        int bottom = top + mIconPlaceholder.getHeight();
        mLayoutedIconRect.set(left, top, right, bottom);
        updateDrawingRect();
    }

    private void updateDrawingRect() {
        float oldLeft = mIconDrawingRect.left;
        mIconDrawingRect.set(mLayoutedIconRect);
        if (oldLeft != mIconDrawingRect.left && mOnDrawingRectChangedListener != null) {
            mOnDrawingRectChangedListener.run();
        }
    }

    public NotificationEntry getShowingEntry() {
        return mShowingEntry;
    }

    public Rect getIconDrawingRect() {
        return mIconDrawingRect;
    }

    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        mTextView.setTextColor(DarkIconDispatcher.getTint(areas, this, tint));
    }

    public void setOnDrawingRectChangedListener(Runnable onDrawingRectChangedListener) {
        mOnDrawingRectChangedListener = onDrawingRectChangedListener;
    }
}
