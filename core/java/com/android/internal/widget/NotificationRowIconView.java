/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.Nullable;
import android.app.Flags;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.widget.RemoteViews;

/**
 * An image view that holds the icon displayed on the left side of a notification row.
 */
@RemoteViews.RemoteView
public class NotificationRowIconView extends CachingIconView {
    private boolean mApplyCircularCrop = false;

    public NotificationRowIconView(Context context) {
        super(context);
    }

    public NotificationRowIconView(Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NotificationRowIconView(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public NotificationRowIconView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        // If showing the app icon, we don't need background or padding.
        if (Flags.notificationsUseAppIcon()) {
            setPadding(0, 0, 0, 0);
            setBackground(null);
        }

        super.onFinishInflate();
    }

    @Nullable
    @Override
    Drawable loadSizeRestrictedIcon(@Nullable Icon icon) {
        final Drawable original = super.loadSizeRestrictedIcon(icon);
        final Drawable result;
        if (mApplyCircularCrop) {
            result = makeCircularDrawable(original);
        } else {
            result = original;
        }

        return result;
    }

    /**
     * Enables circle crop that makes given image circular
     */
    @RemotableViewMethod(asyncImpl = "setApplyCircularCropAsync")
    public void setApplyCircularCrop(boolean applyCircularCrop) {
        mApplyCircularCrop = applyCircularCrop;
    }

    /**
     * Async version of {@link NotificationRowIconView#setApplyCircularCrop}
     */
    public Runnable setApplyCircularCropAsync(boolean applyCircularCrop) {
        mApplyCircularCrop = applyCircularCrop;
        return () -> {
        };
    }

    @Nullable
    private Drawable makeCircularDrawable(@Nullable Drawable original) {
        if (original == null) {
            return original;
        }

        final Bitmap source = drawableToBitmap(original);

        int size = Math.min(source.getWidth(), source.getHeight());

        Bitmap squared = Bitmap.createScaledBitmap(source, size, size, /* filter= */ false);
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);

        final Canvas canvas = new Canvas(result);
        final Paint paint = new Paint();
        paint.setShader(
                new BitmapShader(squared, BitmapShader.TileMode.CLAMP,
                        BitmapShader.TileMode.CLAMP));
        paint.setAntiAlias(true);
        float radius = size / 2f;
        canvas.drawCircle(radius, radius, radius, paint);
        return new BitmapDrawable(getResources(), result);
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable bitmapDrawable) {
            final Bitmap bitmap = bitmapDrawable.getBitmap();
            if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                return bitmap.copy(Bitmap.Config.ARGB_8888, false);
            } else {
                return bitmap;
            }
        }

        int width = drawable.getIntrinsicWidth();
        width = width > 0 ? width : 1;
        int height = drawable.getIntrinsicHeight();
        height = height > 0 ? height : 1;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }
}
