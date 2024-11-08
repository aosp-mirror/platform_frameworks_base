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
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.widget.RemoteViews;

/**
 * An image view that holds the icon displayed at the start of a notification row.
 * This can generally either display the "small icon" of a notification set via
 * {@link this#setImageIcon(Icon)}, or an app icon controlled and fetched by the provider set
 * through {@link this#setIconProvider(NotificationIconProvider)}.
 */
@RemoteViews.RemoteView
public class NotificationRowIconView extends CachingIconView {
    private NotificationIconProvider mIconProvider;

    private boolean mApplyCircularCrop = false;
    private Drawable mAppIcon = null;

    // Padding, background and colors set on the view prior to being overridden when showing the app
    // icon, to be restored if we're showing the small icon again.
    private Rect mOriginalPadding = null;
    private Drawable mOriginalBackground = null;
    private int mOriginalBackgroundColor = ColoredIconHelper.COLOR_INVALID;
    private int mOriginalIconColor = ColoredIconHelper.COLOR_INVALID;

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

    /**
     * Sets the icon provider for this view. This is used to determine whether we should show the
     * app icon instead of the small icon, and to fetch the app icon if needed.
     */
    public void setIconProvider(NotificationIconProvider iconProvider) {
        mIconProvider = iconProvider;
    }

    private Drawable loadAppIcon() {
        if (mIconProvider != null && mIconProvider.shouldShowAppIcon()) {
            return mIconProvider.getAppIcon();
        }
        return null;
    }

    @RemotableViewMethod(asyncImpl = "setImageIconAsync")
    @Override
    public void setImageIcon(Icon icon) {
        if (Flags.notificationsRedesignAppIcons()) {
            if (mAppIcon != null) {
                // We already know that we should be using the app icon, and we already loaded it.
                // We assume that cannot change throughout the lifetime of a notification, so
                // there's nothing to do here.
                return;
            }
            mAppIcon = loadAppIcon();
            if (mAppIcon != null) {
                setImageDrawable(mAppIcon);
                adjustViewForAppIcon();
            } else {
                super.setImageIcon(icon);
                restoreViewForSmallIcon();
            }
            return;
        }
        super.setImageIcon(icon);
    }

    @RemotableViewMethod
    @Override
    public Runnable setImageIconAsync(Icon icon) {
        if (Flags.notificationsRedesignAppIcons()) {
            if (mAppIcon != null) {
                // We already know that we should be using the app icon, and we already loaded it.
                // We assume that cannot change throughout the lifetime of a notification, so
                // there's nothing to do here.
                return () -> {
                };
            }
            mAppIcon = loadAppIcon();
            if (mAppIcon != null) {
                return () -> {
                    setImageDrawable(mAppIcon);
                    adjustViewForAppIcon();
                };
            } else {
                return () -> {
                    super.setImageIcon(icon);
                    restoreViewForSmallIcon();
                };
            }
        }
        return super.setImageIconAsync(icon);
    }

    /**
     * Override padding and background from the view to display the app icon.
     */
    private void adjustViewForAppIcon() {
        removePadding();
        removeBackground();
    }

    /**
     * Restore padding and background overridden by {@link this#adjustViewForAppIcon}.
     * Does nothing if they were not overridden.
     */
    private void restoreViewForSmallIcon() {
        restorePadding();
        restoreBackground();
        restoreColors();
    }

    private void removePadding() {
        if (mOriginalPadding == null) {
            mOriginalPadding = new Rect(getPaddingLeft(), getPaddingTop(),
                    getPaddingRight(), getPaddingBottom());
        }
        setPadding(0, 0, 0, 0);
    }

    private void restorePadding() {
        if (mOriginalPadding != null) {
            setPadding(mOriginalPadding.left, mOriginalPadding.top,
                    mOriginalPadding.right,
                    mOriginalPadding.bottom);
            mOriginalPadding = null;
        }
    }

    private void removeBackground() {
        if (mOriginalBackground == null) {
            mOriginalBackground = getBackground();
        }

        setBackground(null);
    }

    private void restoreBackground() {
        // NOTE: This will not work if the original background was null, but that's better than
        //  accidentally clearing the background. We expect that there's generally going to be one
        //  anyway unless we manually clear it.
        if (mOriginalBackground != null) {
            setBackground(mOriginalBackground);
            mOriginalBackground = null;
        }
    }

    private void restoreColors() {
        if (mOriginalBackgroundColor != ColoredIconHelper.COLOR_INVALID) {
            super.setBackgroundColor(mOriginalBackgroundColor);
            mOriginalBackgroundColor = ColoredIconHelper.COLOR_INVALID;
        }
        if (mOriginalIconColor != ColoredIconHelper.COLOR_INVALID) {
            super.setOriginalIconColor(mOriginalIconColor);
            mOriginalIconColor = ColoredIconHelper.COLOR_INVALID;
        }
    }

    @RemotableViewMethod
    @Override
    public void setBackgroundColor(int color) {
        // Ignore color overrides if we're showing the app icon.
        if (mAppIcon == null) {
            super.setBackgroundColor(color);
        } else {
            mOriginalBackgroundColor = color;
        }
    }

    @RemotableViewMethod
    @Override
    public void setOriginalIconColor(int color) {
        // Ignore color overrides if we're showing the app icon.
        if (mAppIcon == null) {
            super.setOriginalIconColor(color);
        } else {
            mOriginalIconColor = color;
        }
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

    /**
     * A provider that allows this view to verify whether it should use the app icon instead of the
     * icon provided to it via setImageIcon, as well as actually fetching the app icon. It should
     * primarily be called on the background thread.
     */
    public interface NotificationIconProvider {
        /** Whether this notification should use the app icon instead of the small icon. */
        boolean shouldShowAppIcon();

        /** Get the app icon for this notification. */
        Drawable getAppIcon();
    }
}
