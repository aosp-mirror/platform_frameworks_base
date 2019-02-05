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
import android.widget.RemoteViews;

/**
 * A TextView that can float around an image on the end.
 *
 * @hide
 */
@RemoteViews.RemoteView
public class MediaNotificationView extends FrameLayout {

    private final int mNotificationContentMarginEnd;
    private final int mNotificationContentImageMarginEnd;
    private ImageView mRightIcon;
    private View mActions;
    private View mHeader;
    private View mMainColumn;
    private View mMediaContent;
    private int mImagePushIn;

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
        boolean hasIcon = mRightIcon.getVisibility() != GONE;
        if (!hasIcon) {
            resetHeaderIndention();
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        boolean reMeasure = false;
        mImagePushIn = 0;
        if (hasIcon && mode != MeasureSpec.UNSPECIFIED) {
            int size = MeasureSpec.getSize(widthMeasureSpec);
            size = size - mActions.getMeasuredWidth();
            ViewGroup.MarginLayoutParams layoutParams =
                    (MarginLayoutParams) mRightIcon.getLayoutParams();
            int imageEndMargin = layoutParams.getMarginEnd();
            size -= imageEndMargin;
            int fullHeight = mMediaContent.getMeasuredHeight();
            if (size > fullHeight) {
                size = fullHeight;
            } else if (size < fullHeight) {
                size = Math.max(0, size);
                mImagePushIn = fullHeight - size;
            }
            if (layoutParams.width != fullHeight || layoutParams.height != fullHeight) {
                layoutParams.width = fullHeight;
                layoutParams.height = fullHeight;
                mRightIcon.setLayoutParams(layoutParams);
                reMeasure = true;
            }

            // lets ensure that the main column doesn't run into the image
            ViewGroup.MarginLayoutParams params
                    = (MarginLayoutParams) mMainColumn.getLayoutParams();
            int marginEnd = size + imageEndMargin + mNotificationContentMarginEnd;
            if (marginEnd != params.getMarginEnd()) {
                params.setMarginEnd(marginEnd);
                mMainColumn.setLayoutParams(params);
                reMeasure = true;
            }
            int headerMarginEnd = size + imageEndMargin;
            params = (MarginLayoutParams) mHeader.getLayoutParams();
            if (params.getMarginEnd() != headerMarginEnd) {
                params.setMarginEnd(headerMarginEnd);
                mHeader.setLayoutParams(params);
                reMeasure = true;
            }
            if (mHeader.getPaddingEnd() != mNotificationContentImageMarginEnd) {
                mHeader.setPaddingRelative(mHeader.getPaddingStart(),
                        mHeader.getPaddingTop(),
                        mNotificationContentImageMarginEnd,
                        mHeader.getPaddingBottom());
                reMeasure = true;
            }
        }
        if (reMeasure) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mImagePushIn > 0) {
            mRightIcon.layout(mRightIcon.getLeft() + mImagePushIn, mRightIcon.getTop(),
                    mRightIcon.getRight()  + mImagePushIn, mRightIcon.getBottom());
        }
    }

    private void resetHeaderIndention() {
        if (mHeader.getPaddingEnd() != mNotificationContentMarginEnd) {
            mHeader.setPaddingRelative(mHeader.getPaddingStart(),
                    mHeader.getPaddingTop(),
                    mNotificationContentMarginEnd,
                    mHeader.getPaddingBottom());
        }
        ViewGroup.MarginLayoutParams headerParams =
                (MarginLayoutParams) mHeader.getLayoutParams();
        headerParams.setMarginEnd(0);
        if (headerParams.getMarginEnd() != 0) {
            headerParams.setMarginEnd(0);
            mHeader.setLayoutParams(headerParams);
        }
    }

    public MediaNotificationView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mNotificationContentMarginEnd = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_end);
        mNotificationContentImageMarginEnd = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_image_margin_end);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mRightIcon = findViewById(com.android.internal.R.id.right_icon);
        mActions = findViewById(com.android.internal.R.id.media_actions);
        mHeader = findViewById(com.android.internal.R.id.notification_header);
        mMainColumn = findViewById(com.android.internal.R.id.notification_main_column);
        mMediaContent = findViewById(com.android.internal.R.id.notification_media_content);
    }
}
