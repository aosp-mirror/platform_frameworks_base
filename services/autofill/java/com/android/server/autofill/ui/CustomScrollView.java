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
 * limitations under the License.
 */
package com.android.server.autofill.ui;

import static com.android.server.autofill.Helper.sDebug;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.provider.DeviceConfig;
import android.util.AttributeSet;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View;
import android.widget.ScrollView;

import com.android.internal.R;

/**
 * Custom scroll view that stretches to a maximum height.
 */
public class CustomScrollView extends ScrollView {

    private static final String TAG = "CustomScrollView";

    /**
     * Sets the max percent of screen that the autofill save dialog can take up in height
     * when the device is in portrait orientation.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_SAVE_DIALOG_PORTRAIT_BODY_HEIGHT_MAX_PERCENT =
            "autofill_save_dialog_portrait_body_height_max_percent";

    /**
     * Sets the max percent of screen that the autofill save dialog can take up in height
     * when the device is in landscape orientation.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_SAVE_DIALOG_LANDSCAPE_BODY_HEIGHT_MAX_PERCENT =
            "autofill_save_dialog_landscape_body_height_max_percent";

    private int mWidth = -1;
    private int mHeight = -1;
    private int mMaxPortraitBodyHeightPercent = 20;
    private int mMaxLandscapeBodyHeightPercent = 20;

    public CustomScrollView(Context context) {
        super(context);
        setMaxBodyHeightPercent();
    }

    public CustomScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setMaxBodyHeightPercent();
    }

    public CustomScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setMaxBodyHeightPercent();
    }

    public CustomScrollView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setMaxBodyHeightPercent();
    }

    private void setMaxBodyHeightPercent() {
        mMaxPortraitBodyHeightPercent = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_SAVE_DIALOG_PORTRAIT_BODY_HEIGHT_MAX_PERCENT,
                mMaxPortraitBodyHeightPercent);
        mMaxLandscapeBodyHeightPercent = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_AUTOFILL,
                DEVICE_CONFIG_SAVE_DIALOG_LANDSCAPE_BODY_HEIGHT_MAX_PERCENT,
                mMaxLandscapeBodyHeightPercent);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (getChildCount() == 0) {
            // Should not happen
            Slog.e(TAG, "no children");
            return;
        }

        mWidth = MeasureSpec.getSize(widthMeasureSpec);
        calculateDimensions();
        setMeasuredDimension(mWidth, mHeight);
    }

    private void calculateDimensions() {
        if (mHeight != -1) return;

        final Point point = new Point();
        final Context context = getContext();
        context.getDisplayNoVerify().getSize(point);

        final View content = getChildAt(0);
        final int contentHeight = content.getMeasuredHeight();
        int displayHeight = point.y;

        int configBasedMaxHeight = (getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE)
                ? (int) (mMaxLandscapeBodyHeightPercent * displayHeight / 100)
                : (int) (mMaxPortraitBodyHeightPercent * displayHeight / 100);
        mHeight = configBasedMaxHeight > 0
                ? Math.min(contentHeight, configBasedMaxHeight)
                : Math.min(contentHeight, getAttrBasedMaxHeight(context, displayHeight));

        if (sDebug) {
            Slog.d(TAG, "calculateDimensions():"
                    + " mMaxPortraitBodyHeightPercent=" + mMaxPortraitBodyHeightPercent
                    + ", mMaxLandscapeBodyHeightPercent=" + mMaxLandscapeBodyHeightPercent
                    + ", configBasedMaxHeight=" + configBasedMaxHeight
                    + ", attrBasedMaxHeight=" + getAttrBasedMaxHeight(context, displayHeight)
                    + ", contentHeight=" + contentHeight
                    + ", w=" + mWidth + ", h=" + mHeight);
        }
    }

    private int getAttrBasedMaxHeight(Context context, int displayHeight) {
        final TypedValue maxHeightAttrTypedValue = new TypedValue();
        context.getTheme().resolveAttribute(R.attr.autofillSaveCustomSubtitleMaxHeight,
                maxHeightAttrTypedValue, true);
        return (int) maxHeightAttrTypedValue.getFraction(displayHeight, displayHeight);
    }
}
