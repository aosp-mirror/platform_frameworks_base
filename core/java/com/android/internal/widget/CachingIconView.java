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

import static com.android.internal.widget.ColoredIconHelper.applyGrayTint;

import android.annotation.DrawableRes;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.widget.ImageView;
import android.widget.RemoteViews;

import com.android.internal.R;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * An ImageView for displaying an Icon. Avoids reloading the Icon when possible.
 */
@RemoteViews.RemoteView
public class CachingIconView extends ImageView {

    private String mLastPackage;
    private int mLastResId;
    private boolean mInternalSetDrawable;
    private boolean mForceHidden;
    private int mDesiredVisibility;
    private Consumer<Integer> mOnVisibilityChangedListener;
    private Consumer<Boolean> mOnForceHiddenChangedListener;
    private int mIconColor;
    private int mBackgroundColor;
    private boolean mWillBeForceHidden;

    private int mMaxDrawableWidth = -1;
    private int mMaxDrawableHeight = -1;

    public CachingIconView(Context context) {
        this(context, null, 0, 0);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public CachingIconView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0, 0);
    }

    public CachingIconView(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public CachingIconView(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs, defStyleAttr, defStyleRes);
    }

    private void init(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        if (attrs == null) {
            return;
        }

        TypedArray ta = context.obtainStyledAttributes(attrs,
                R.styleable.CachingIconView, defStyleAttr, defStyleRes);
        mMaxDrawableWidth = ta.getDimensionPixelSize(R.styleable
                .CachingIconView_maxDrawableWidth, -1);
        mMaxDrawableHeight = ta.getDimensionPixelSize(R.styleable
                .CachingIconView_maxDrawableHeight, -1);
        ta.recycle();
    }

    @Override
    @RemotableViewMethod(asyncImpl="setImageIconAsync")
    public void setImageIcon(@Nullable Icon icon) {
        if (!testAndSetCache(icon)) {
            mInternalSetDrawable = true;
            // This calls back to setImageDrawable, make sure we don't clear the cache there.
            Drawable drawable = loadSizeRestrictedIcon(icon);
            if (drawable == null) {
                super.setImageIcon(icon);
            } else {
                super.setImageDrawable(drawable);
            }
            mInternalSetDrawable = false;
        }
    }

    @Nullable
    Drawable loadSizeRestrictedIcon(@Nullable Icon icon) {
        return LocalImageResolver.resolveImage(icon, getContext(), mMaxDrawableWidth,
                mMaxDrawableHeight);
    }

    @Override
    public Runnable setImageIconAsync(@Nullable final Icon icon) {
        resetCache();
        Drawable drawable = loadSizeRestrictedIcon(icon);
        if (drawable != null) {
            return () -> setImageDrawable(drawable);
        }
        return super.setImageIconAsync(icon);
    }

    @Override
    @RemotableViewMethod(asyncImpl="setImageResourceAsync")
    public void setImageResource(@DrawableRes int resId) {
        if (!testAndSetCache(resId)) {
            mInternalSetDrawable = true;
            // This calls back to setImageDrawable, make sure we don't clear the cache there.
            Drawable drawable = loadSizeRestrictedDrawable(resId);
            if (drawable == null) {
                super.setImageResource(resId);
            } else {
                super.setImageDrawable(drawable);
            }
            mInternalSetDrawable = false;
        }
    }

    @Nullable
    private Drawable loadSizeRestrictedDrawable(@DrawableRes int resId) {
        return LocalImageResolver.resolveImage(resId, getContext(), mMaxDrawableWidth,
                mMaxDrawableHeight);
    }

    @Override
    public Runnable setImageResourceAsync(@DrawableRes int resId) {
        resetCache();
        Drawable drawable = loadSizeRestrictedDrawable(resId);
        if (drawable != null) {
            return () -> setImageDrawable(drawable);
        }

        return super.setImageResourceAsync(resId);
    }

    @Override
    @RemotableViewMethod(asyncImpl="setImageURIAsync")
    public void setImageURI(@Nullable Uri uri) {
        resetCache();
        Drawable drawable = loadSizeRestrictedUri(uri);
        if (drawable == null) {
            super.setImageURI(uri);
        } else {
            mInternalSetDrawable = true;
            super.setImageDrawable(drawable);
            mInternalSetDrawable = false;
        }
    }

    @Nullable
    private Drawable loadSizeRestrictedUri(@Nullable Uri uri) {
        return LocalImageResolver.resolveImage(uri, getContext(), mMaxDrawableWidth,
                mMaxDrawableHeight);
    }

    @Override
    public Runnable setImageURIAsync(@Nullable Uri uri) {
        resetCache();
        Drawable drawable = loadSizeRestrictedUri(uri);
        if (drawable == null) {
            return super.setImageURIAsync(uri);
        } else {
            return () -> setImageDrawable(drawable);
        }
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        if (!mInternalSetDrawable) {
            // Only clear the cache if we were externally called.
            resetCache();
        }
        super.setImageDrawable(drawable);
    }

    @Override
    @RemotableViewMethod
    public void setImageBitmap(Bitmap bm) {
        resetCache();
        super.setImageBitmap(bm);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        resetCache();
    }

    /**
     * @return true if the currently set image is the same as {@param icon}
     */
    private synchronized boolean testAndSetCache(Icon icon) {
        if (icon != null && icon.getType() == Icon.TYPE_RESOURCE) {
            String iconPackage = normalizeIconPackage(icon);

            boolean isCached = mLastResId != 0
                    && icon.getResId() == mLastResId
                    && Objects.equals(iconPackage, mLastPackage);

            mLastPackage = iconPackage;
            mLastResId = icon.getResId();

            return isCached;
        } else {
            resetCache();
            return false;
        }
    }

    /**
     * @return true if the currently set image is the same as {@param resId}
     */
    private synchronized boolean testAndSetCache(int resId) {
        boolean isCached;
        if (resId == 0 || mLastResId == 0) {
            isCached = false;
        } else {
            isCached = resId == mLastResId && null == mLastPackage;
        }
        mLastPackage = null;
        mLastResId = resId;
        return isCached;
    }

    /**
     * Returns the normalized package name of {@param icon}.
     * @return null if icon is null or if the icons package is null, empty or matches the current
     *         context. Otherwise returns the icon's package context.
     */
    private String normalizeIconPackage(Icon icon) {
        if (icon == null) {
            return null;
        }

        String pkg = icon.getResPackage();
        if (TextUtils.isEmpty(pkg)) {
            return null;
        }
        if (pkg.equals(mContext.getPackageName())) {
            return null;
        }
        return pkg;
    }

    private synchronized void resetCache() {
        mLastResId = 0;
        mLastPackage = null;
    }

    /**
     * Set the icon to be forcibly hidden, even when it's visibility is changed to visible.
     * This is necessary since we still want to keep certain views hidden when their visibility
     * is modified from other sources like the shelf.
     */
    public void setForceHidden(boolean forceHidden) {
        if (forceHidden != mForceHidden) {
            mForceHidden = forceHidden;
            mWillBeForceHidden = false;
            updateVisibility();
            if (mOnForceHiddenChangedListener != null) {
                mOnForceHiddenChangedListener.accept(forceHidden);
            }
        }
    }

    @Override
    @RemotableViewMethod
    public void setVisibility(int visibility) {
        mDesiredVisibility = visibility;
        updateVisibility();
    }

    private void updateVisibility() {
        int visibility = mDesiredVisibility == VISIBLE && mForceHidden ? INVISIBLE
                : mDesiredVisibility;
        if (mOnVisibilityChangedListener != null) {
            mOnVisibilityChangedListener.accept(visibility);
        }
        super.setVisibility(visibility);
    }

    public void setOnVisibilityChangedListener(Consumer<Integer> listener) {
        mOnVisibilityChangedListener = listener;
    }

    public void setOnForceHiddenChangedListener(Consumer<Boolean> listener) {
        mOnForceHiddenChangedListener = listener;
    }


    public boolean isForceHidden() {
        return mForceHidden;
    }

    /**
     * Provides the notification's background color to the icon.  This is only used when the icon
     * is "inverted".  This should be called before calling {@link #setOriginalIconColor(int)}.
     */
    @RemotableViewMethod
    public void setBackgroundColor(int color) {
        mBackgroundColor = color;
    }

    /**
     * Sets the icon color. If COLOR_INVALID is set, the icon's color filter will
     * not be altered. If there is a background drawable, this method uses the value from
     * {@link #setBackgroundColor(int)} which must have been already called.
     */
    @RemotableViewMethod
    public void setOriginalIconColor(int color) {
        mIconColor = color;
        Drawable background = getBackground();
        Drawable icon = getDrawable();
        boolean hasColor = color != ColoredIconHelper.COLOR_INVALID;
        if (background == null) {
            // This is the pre-S style -- colored icon with no background.
            if (hasColor && icon != null) {
                icon.mutate().setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
            }
        } else {
            // When there is a background drawable, color it with the foreground color and
            // colorize the icon itself with the background color, creating an inverted effect.
            if (hasColor) {
                background.mutate().setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
                if (icon != null) {
                    icon.mutate().setColorFilter(mBackgroundColor, PorterDuff.Mode.SRC_ATOP);
                }
            } else {
                background.mutate().setColorFilter(mBackgroundColor, PorterDuff.Mode.SRC_ATOP);
            }
        }
    }

    /**
     * Set the icon's color filter: to gray if true, otherwise colored.
     * If this icon has no original color, this has no effect.
     */
    public void setGrayedOut(boolean grayedOut) {
        // If there is a background drawable, then it has the foreground color and the image
        // drawable has the background color, creating an inverted efffect.
        Drawable drawable = getBackground();
        if (drawable == null) {
            drawable = getDrawable();
        }
        applyGrayTint(mContext, drawable, grayedOut, mIconColor);
    }

    public int getOriginalIconColor() {
        return mIconColor;
    }

    /**
     * @return if the view will be forceHidden after an animation
     */
    public boolean willBeForceHidden() {
        return mWillBeForceHidden;
    }

    /**
     * Set that this view will be force hidden after an animation
     *
     * @param forceHidden if it will be forcehidden
     */
    public void setWillBeForceHidden(boolean forceHidden) {
        mWillBeForceHidden = forceHidden;
    }

    /**
     * Returns the set maximum width of drawable in pixels. -1 if not set.
     */
    public int getMaxDrawableWidth() {
        return mMaxDrawableWidth;
    }

    /**
     * Returns the set maximum height of drawable in pixels. -1 if not set.
     */
    public int getMaxDrawableHeight() {
        return mMaxDrawableHeight;
    }
}
