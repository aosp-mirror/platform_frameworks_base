/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.widget;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.RemoteViews;

/**
 * A TextView that can float around an image on the end.
 *
 * @hide
 */
@RemoteViews.RemoteView
public class MediaNotificationView extends FrameLayout {

    private final int mMaxImageSize;
    private final int mImageMinTopMargin;
    private final int mNotificationContentMarginEnd;
    private final int mNotificationContentImageMarginEnd;
    private ImageView mRightIcon;
    private View mActions;
    private View mHeader;
    private View mMainColumn;

    public MediaNotificationView(Context context) {
        this(context, null);
    }

    public MediaNotificationView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MediaNotificationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        boolean hasIcon = mRightIcon.getVisibility() != GONE;
        if (hasIcon && mode != MeasureSpec.UNSPECIFIED) {
            measureChild(mActions, widthMeasureSpec, heightMeasureSpec);
            int size = MeasureSpec.getSize(widthMeasureSpec);
            size = size - mActions.getMeasuredWidth();
            ViewGroup.MarginLayoutParams layoutParams =
                    (MarginLayoutParams) mRightIcon.getLayoutParams();
            int imageEndMargin = layoutParams.getMarginEnd();
            size -= imageEndMargin;
            size = Math.min(size, mMaxImageSize);
            size = Math.max(size, mRightIcon.getMinimumWidth());
            layoutParams.width = size;
            layoutParams.height = size;
            mRightIcon.setLayoutParams(layoutParams);

            // lets ensure that the main column doesn't run into the image
            ViewGroup.MarginLayoutParams mainParams
                    = (MarginLayoutParams) mMainColumn.getLayoutParams();
            int marginEnd = size + imageEndMargin + mNotificationContentMarginEnd;
            if (marginEnd != mainParams.getMarginEnd()) {
                mainParams.setMarginEnd(marginEnd);
                mMainColumn.setLayoutParams(mainParams);
            }

        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        ViewGroup.MarginLayoutParams iconParams =
                (MarginLayoutParams) mRightIcon.getLayoutParams();
        int topMargin = getMeasuredHeight() - mRightIcon.getMeasuredHeight()
                - iconParams.bottomMargin;
        // If the topMargin is high enough we can also remove the header constraint!
        boolean reMeasure = false;
        if (!hasIcon || topMargin >= mImageMinTopMargin) {
            reMeasure = resetHeaderIndention();
        } else {
            int paddingEnd = mNotificationContentImageMarginEnd;
            ViewGroup.MarginLayoutParams headerParams =
                    (MarginLayoutParams) mHeader.getLayoutParams();
            int newMarginEnd = mRightIcon.getMeasuredWidth() + iconParams.getMarginEnd();
            if (headerParams.getMarginEnd() != newMarginEnd) {
                headerParams.setMarginEnd(newMarginEnd);
                mHeader.setLayoutParams(headerParams);
                reMeasure = true;
            }
            if (mHeader.getPaddingEnd() != paddingEnd) {
                mHeader.setPaddingRelative(mHeader.getPaddingStart(),
                        mHeader.getPaddingTop(),
                        paddingEnd,
                        mHeader.getPaddingBottom());
                reMeasure = true;
            }
        }
        if (reMeasure) {
            measureChildWithMargins(mHeader, widthMeasureSpec, 0, heightMeasureSpec, 0);
        }
    }

    private boolean resetHeaderIndention() {
        boolean remeasure = false;
        if (mHeader.getPaddingEnd() != mNotificationContentMarginEnd) {
            mHeader.setPaddingRelative(mHeader.getPaddingStart(),
                    mHeader.getPaddingTop(),
                    mNotificationContentMarginEnd,
                    mHeader.getPaddingBottom());
            remeasure = true;
        }
        ViewGroup.MarginLayoutParams headerParams =
                (MarginLayoutParams) mHeader.getLayoutParams();
        headerParams.setMarginEnd(0);
        if (headerParams.getMarginEnd() != 0) {
            headerParams.setMarginEnd(0);
            mHeader.setLayoutParams(headerParams);
            remeasure = true;
        }
        return remeasure;
    }

    public MediaNotificationView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mMaxImageSize = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.media_notification_expanded_image_max_size);
        mImageMinTopMargin = (int) (context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_top)
                + getResources().getDisplayMetrics().density * 2);
        mNotificationContentMarginEnd = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_end);
        mNotificationContentImageMarginEnd = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_image_margin_end);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mRightIcon = (ImageView) findViewById(com.android.internal.R.id.right_icon);
        mActions = findViewById(com.android.internal.R.id.media_actions);
        mHeader = findViewById(com.android.internal.R.id.notification_header);
        mMainColumn = findViewById(com.android.internal.R.id.notification_main_column);
    }
}
