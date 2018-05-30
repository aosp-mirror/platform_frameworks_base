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

package com.android.internal.widget;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StyleRes;
import android.app.Notification;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pools;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RemoteViews;

import com.android.internal.R;

import java.io.IOException;

/**
 * A message of a {@link MessagingLayout} that is an image.
 */
@RemoteViews.RemoteView
public class MessagingImageMessage extends ImageView implements MessagingMessage {
    private static final String TAG = "MessagingImageMessage";
    private static Pools.SimplePool<MessagingImageMessage> sInstancePool
            = new Pools.SynchronizedPool<>(10);
    private final MessagingMessageState mState = new MessagingMessageState(this);
    private final int mMinImageHeight;
    private final Path mPath = new Path();
    private final int mImageRounding;
    private final int mMaxImageHeight;
    private final int mIsolatedSize;
    private final int mExtraSpacing;
    private Drawable mDrawable;
    private float mAspectRatio;
    private int mActualWidth;
    private int mActualHeight;
    private boolean mIsIsolated;

    public MessagingImageMessage(@NonNull Context context) {
        this(context, null);
    }

    public MessagingImageMessage(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MessagingImageMessage(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MessagingImageMessage(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr, @StyleRes int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mMinImageHeight = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.messaging_image_min_size);
        mMaxImageHeight = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.messaging_image_max_height);
        mImageRounding = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.messaging_image_rounding);
        mExtraSpacing = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.messaging_image_extra_spacing);
        setMaxHeight(mMaxImageHeight);
        mIsolatedSize = getResources().getDimensionPixelSize(R.dimen.messaging_avatar_size);
    }

    @Override
    public MessagingMessageState getState() {
        return mState;
    }

    @Override
    public boolean setMessage(Notification.MessagingStyle.Message message) {
        MessagingMessage.super.setMessage(message);
        Drawable drawable;
        try {
            drawable = LocalImageResolver.resolveImage(message.getDataUri(), getContext());
        } catch (IOException | SecurityException e) {
            e.printStackTrace();
            return false;
        }
        int intrinsicHeight = drawable.getIntrinsicHeight();
        if (intrinsicHeight == 0) {
            Log.w(TAG, "Drawable with 0 intrinsic height was returned");
            return false;
        }
        mDrawable = drawable;
        mAspectRatio = ((float) mDrawable.getIntrinsicWidth()) / intrinsicHeight;
        setImageDrawable(drawable);
        setContentDescription(message.getText());
        return true;
    }

    static MessagingMessage createMessage(MessagingLayout layout,
            Notification.MessagingStyle.Message m) {
        MessagingLinearLayout messagingLinearLayout = layout.getMessagingLinearLayout();
        MessagingImageMessage createdMessage = sInstancePool.acquire();
        if (createdMessage == null) {
            createdMessage = (MessagingImageMessage) LayoutInflater.from(
                    layout.getContext()).inflate(
                            R.layout.notification_template_messaging_image_message,
                            messagingLinearLayout,
                            false);
            createdMessage.addOnLayoutChangeListener(MessagingLayout.MESSAGING_PROPERTY_ANIMATOR);
        }
        boolean created = createdMessage.setMessage(m);
        if (!created) {
            createdMessage.recycle();
            return MessagingTextMessage.createMessage(layout, m);
        }
        return createdMessage;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(getRoundedRectPath());
        int width = (int) Math.max(getActualWidth(), getActualHeight() * mAspectRatio);
        int height = (int) (width / mAspectRatio);
        int left = (int) ((getActualWidth() - width) / 2.0f);
        mDrawable.setBounds(left, 0, left + width, height);
        mDrawable.draw(canvas);
        canvas.restore();
    }

    public Path getRoundedRectPath() {
        int left = 0;
        int right = getActualWidth();
        int top = 0;
        int bottom = getActualHeight();
        mPath.reset();
        int width = right - left;
        float roundnessX = mImageRounding;
        float roundnessY = mImageRounding;
        roundnessX = Math.min(width / 2, roundnessX);
        roundnessY = Math.min((bottom - top) / 2, roundnessY);
        mPath.moveTo(left, top + roundnessY);
        mPath.quadTo(left, top, left + roundnessX, top);
        mPath.lineTo(right - roundnessX, top);
        mPath.quadTo(right, top, right, top + roundnessY);
        mPath.lineTo(right, bottom - roundnessY);
        mPath.quadTo(right, bottom, right - roundnessX, bottom);
        mPath.lineTo(left + roundnessX, bottom);
        mPath.quadTo(left, bottom, left, bottom - roundnessY);
        mPath.close();
        return mPath;
    }

    public void recycle() {
        MessagingMessage.super.recycle();
        setImageBitmap(null);
        mDrawable = null;
        sInstancePool.release(this);
    }

    public static void dropCache() {
        sInstancePool = new Pools.SynchronizedPool<>(10);
    }

    @Override
    public int getMeasuredType() {
        int measuredHeight = getMeasuredHeight();
        int minImageHeight;
        if (mIsIsolated) {
            minImageHeight = mIsolatedSize;
        } else {
            minImageHeight = mMinImageHeight;
        }
        boolean measuredTooSmall = measuredHeight < minImageHeight
                && measuredHeight != mDrawable.getIntrinsicHeight();
        if (measuredTooSmall) {
            return MEASURED_TOO_SMALL;
        } else {
            if (!mIsIsolated && measuredHeight != mDrawable.getIntrinsicHeight()) {
                return MEASURED_SHORTENED;
            } else {
                return MEASURED_NORMAL;
            }
        }
    }

    @Override
    public void setMaxDisplayedLines(int lines) {
        // Nothing to do, this should be handled automatically.
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mIsIsolated) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                    MeasureSpec.getSize(heightMeasureSpec));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        // TODO: ensure that this isn't called when transforming
        setActualWidth(getStaticWidth());
        setActualHeight(getHeight());
    }

    @Override
    public int getConsumedLines() {
        return 3;
    }

    public void setActualWidth(int actualWidth) {
        mActualWidth = actualWidth;
        invalidate();
    }

    public int getActualWidth() {
        return mActualWidth;
    }

    public void setActualHeight(int actualHeight) {
        mActualHeight = actualHeight;
        invalidate();
    }

    public int getActualHeight() {
        return mActualHeight;
    }

    public int getStaticWidth() {
        if (mIsIsolated) {
            return getWidth();
        }
        return (int) (getHeight() * mAspectRatio);
    }

    public void setIsolated(boolean isolated) {
        if (mIsIsolated != isolated) {
            mIsIsolated = isolated;
            // update the layout params not to have margins
            ViewGroup.MarginLayoutParams layoutParams =
                    (ViewGroup.MarginLayoutParams) getLayoutParams();
            layoutParams.topMargin = isolated ? 0 : mExtraSpacing;
            setLayoutParams(layoutParams);
        }
    }

    @Override
    public int getExtraSpacing() {
        return mExtraSpacing;
    }
}
