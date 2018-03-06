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
 * limitations under the License.
 */

package com.android.settingslib.drawable;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

import com.android.settingslib.R;

/**
 * Converts the user avatar icon to a circularly clipped one with an optional badge and frame
 */
public class UserIconDrawable extends Drawable implements Drawable.Callback {

    private Drawable mUserDrawable;
    private Bitmap mUserIcon;
    private Bitmap mBitmap; // baked representation. Required for transparent border around badge
    private final Paint mIconPaint = new Paint();
    private final Paint mPaint = new Paint();
    private final Matrix mIconMatrix = new Matrix();
    private float mIntrinsicRadius;
    private float mDisplayRadius;
    private float mPadding = 0;
    private int mSize = 0; // custom "intrinsic" size for this drawable if non-zero
    private boolean mInvalidated = true;
    private ColorStateList mTintColor = null;
    private PorterDuff.Mode mTintMode = PorterDuff.Mode.SRC_ATOP;

    private float mFrameWidth;
    private float mFramePadding;
    private ColorStateList mFrameColor = null;
    private Paint mFramePaint;

    private Drawable mBadge;
    private Paint mClearPaint;
    private float mBadgeRadius;
    private float mBadgeMargin;

    /**
     * Gets the system default managed-user badge as a drawable. This drawable is tint-able.
     * For badging purpose, consider
     * {@link android.content.pm.PackageManager#getUserBadgedDrawableForDensity(Drawable, UserHandle, Rect, int)}.
     *
     * @param context
     * @return drawable containing just the badge
     */
    public static Drawable getManagedUserDrawable(Context context) {
        return getDrawableForDisplayDensity
                (context, com.android.internal.R.drawable.ic_corp_user_badge);
    }

    private static Drawable getDrawableForDisplayDensity(
            Context context, @DrawableRes int drawable) {
        int density = context.getResources().getDisplayMetrics().densityDpi;
        return context.getResources().getDrawableForDensity(
                drawable, density, context.getTheme());
    }

    /**
     * Gets the preferred list-item size of this drawable.
     * @param context
     * @return size in pixels
     */
    public static int getSizeForList(Context context) {
        return (int) context.getResources().getDimension(R.dimen.circle_avatar_size);
    }

    public UserIconDrawable() {
        this(0);
    }

    /**
     * Use this constructor if the drawable is intended to be placed in listviews
     * @param intrinsicSize if 0, the intrinsic size will come from the icon itself
     */
    public UserIconDrawable(int intrinsicSize) {
        super();
        mIconPaint.setAntiAlias(true);
        mIconPaint.setFilterBitmap(true);
        mPaint.setFilterBitmap(true);
        mPaint.setAntiAlias(true);
        if (intrinsicSize > 0) {
            setBounds(0, 0, intrinsicSize, intrinsicSize);
            setIntrinsicSize(intrinsicSize);
        }
        setIcon(null);
    }

    public UserIconDrawable setIcon(Bitmap icon) {
        if (mUserDrawable != null) {
            mUserDrawable.setCallback(null);
            mUserDrawable = null;
        }
        mUserIcon = icon;
        if (mUserIcon == null) {
            mIconPaint.setShader(null);
            mBitmap = null;
        } else {
            mIconPaint.setShader(new BitmapShader(icon, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP));
        }
        onBoundsChange(getBounds());
        return this;
    }

    public UserIconDrawable setIconDrawable(Drawable icon) {
        if (mUserDrawable != null) {
            mUserDrawable.setCallback(null);
        }
        mUserIcon = null;
        mUserDrawable = icon;
        if (mUserDrawable == null) {
            mBitmap = null;
        } else {
            mUserDrawable.setCallback(this);
        }
        onBoundsChange(getBounds());
        return this;
    }

    public UserIconDrawable setBadge(Drawable badge) {
        mBadge = badge;
        if (mBadge != null) {
            if (mClearPaint == null) {
                mClearPaint = new Paint();
                mClearPaint.setAntiAlias(true);
                mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                mClearPaint.setStyle(Paint.Style.FILL);
            }
            // update metrics
            onBoundsChange(getBounds());
        } else {
            invalidateSelf();
        }
        return this;
    }

    public UserIconDrawable setBadgeIfManagedUser(Context context, int userId) {
        Drawable badge = null;
        boolean isManaged = context.getSystemService(DevicePolicyManager.class)
                .getProfileOwnerAsUser(userId) != null;
        if (isManaged) {
            badge = getDrawableForDisplayDensity(
                    context, com.android.internal.R.drawable.ic_corp_badge_case);
        }
        return setBadge(badge);
    }

    public void setBadgeRadius(float radius) {
        mBadgeRadius = radius;
        onBoundsChange(getBounds());
    }

    public void setBadgeMargin(float margin) {
        mBadgeMargin = margin;
        onBoundsChange(getBounds());
    }

    /**
     * Sets global padding of icon/frame. Doesn't effect the badge.
     * @param padding
     */
    public void setPadding(float padding) {
        mPadding = padding;
        onBoundsChange(getBounds());
    }

    private void initFramePaint() {
        if (mFramePaint == null) {
            mFramePaint = new Paint();
            mFramePaint.setStyle(Paint.Style.STROKE);
            mFramePaint.setAntiAlias(true);
        }
    }

    public void setFrameWidth(float width) {
        initFramePaint();
        mFrameWidth = width;
        mFramePaint.setStrokeWidth(width);
        onBoundsChange(getBounds());
    }

    public void setFramePadding(float padding) {
        initFramePaint();
        mFramePadding = padding;
        onBoundsChange(getBounds());
    }

    public void setFrameColor(int color) {
        initFramePaint();
        mFramePaint.setColor(color);
        invalidateSelf();
    }

    public void setFrameColor(ColorStateList colorList) {
        initFramePaint();
        mFrameColor = colorList;
        invalidateSelf();
    }

    /**
     * This sets the "intrinsic" size of this drawable. Useful for views which use the drawable's
     * intrinsic size for layout. It is independent of the bounds.
     * @param size if 0, the intrinsic size will be set to the displayed icon's size
     */
    public void setIntrinsicSize(int size) {
        mSize = size;
    }

    @Override
    public void draw(Canvas canvas) {
        if (mInvalidated) {
            rebake();
        }
        if (mBitmap != null) {
            if (mTintColor == null) {
                mPaint.setColorFilter(null);
            } else {
                int color = mTintColor.getColorForState(getState(), mTintColor.getDefaultColor());
                if (mPaint.getColorFilter() == null) {
                    mPaint.setColorFilter(new PorterDuffColorFilter(color, mTintMode));
                } else {
                    ((PorterDuffColorFilter) mPaint.getColorFilter()).setMode(mTintMode);
                    ((PorterDuffColorFilter) mPaint.getColorFilter()).setColor(color);
                }
            }

            canvas.drawBitmap(mBitmap, 0, 0, mPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        super.invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public void setTintList(ColorStateList tintList) {
        mTintColor = tintList;
        super.invalidateSelf();
    }

    @Override
    public void setTintMode(@NonNull PorterDuff.Mode mode) {
        mTintMode = mode;
        super.invalidateSelf();
    }

    @Override
    public ConstantState getConstantState() {
        return new BitmapDrawable(mBitmap).getConstantState();
    }

    /**
     * This 'bakes' the current state of this icon into a bitmap and removes/recycles the source
     * bitmap/drawable. Use this when no more changes will be made and an intrinsic size is set.
     * This effectively turns this into a static drawable.
     */
    public UserIconDrawable bake() {
        if (mSize <= 0) {
            throw new IllegalStateException("Baking requires an explicit intrinsic size");
        }
        onBoundsChange(new Rect(0, 0, mSize, mSize));
        rebake();
        mFrameColor = null;
        mFramePaint = null;
        mClearPaint = null;
        if (mUserDrawable != null) {
            mUserDrawable.setCallback(null);
            mUserDrawable = null;
        } else if (mUserIcon != null) {
            mUserIcon.recycle();
            mUserIcon = null;
        }
        return this;
    }

    private void rebake() {
        mInvalidated = false;

        if (mBitmap == null || (mUserDrawable == null && mUserIcon == null)) {
            return;
        }

        final Canvas canvas = new Canvas(mBitmap);
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);

        if(mUserDrawable != null) {
            mUserDrawable.draw(canvas);
        } else if (mUserIcon != null) {
            int saveId = canvas.save();
            canvas.concat(mIconMatrix);
            canvas.drawCircle(mUserIcon.getWidth() * 0.5f, mUserIcon.getHeight() * 0.5f,
                    mIntrinsicRadius, mIconPaint);
            canvas.restoreToCount(saveId);
        }
        if (mFrameColor != null) {
            mFramePaint.setColor(mFrameColor.getColorForState(getState(), Color.TRANSPARENT));
        }
        if ((mFrameWidth + mFramePadding) > 0.001f) {
            float radius = mDisplayRadius - mPadding - mFrameWidth * 0.5f;
            canvas.drawCircle(getBounds().exactCenterX(), getBounds().exactCenterY(),
                    radius, mFramePaint);
        }

        if ((mBadge != null) && (mBadgeRadius > 0.001f)) {
            final float badgeDiameter = mBadgeRadius * 2f;
            final float badgeTop = mBitmap.getHeight() - badgeDiameter;
            float badgeLeft = mBitmap.getWidth() - badgeDiameter;

            mBadge.setBounds((int) badgeLeft, (int) badgeTop,
                    (int) (badgeLeft + badgeDiameter), (int) (badgeTop + badgeDiameter));

            final float borderRadius = mBadge.getBounds().width() * 0.5f + mBadgeMargin;
            canvas.drawCircle(badgeLeft + mBadgeRadius, badgeTop + mBadgeRadius,
                    borderRadius, mClearPaint);
            mBadge.draw(canvas);
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        if (bounds.isEmpty() || (mUserIcon == null && mUserDrawable == null)) {
            return;
        }

        // re-create bitmap if applicable
        float newDisplayRadius = Math.min(bounds.width(), bounds.height()) * 0.5f;
        int size = (int) (newDisplayRadius * 2);
        if (mBitmap == null || size != ((int) (mDisplayRadius * 2))) {
            mDisplayRadius = newDisplayRadius;
            if (mBitmap != null) {
                mBitmap.recycle();
            }
            mBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        }

        // update metrics
        mDisplayRadius = Math.min(bounds.width(), bounds.height()) * 0.5f;
        final float iconRadius = mDisplayRadius - mFrameWidth - mFramePadding - mPadding;
        RectF dstRect = new RectF(bounds.exactCenterX() - iconRadius,
                                  bounds.exactCenterY() - iconRadius,
                                  bounds.exactCenterX() + iconRadius,
                                  bounds.exactCenterY() + iconRadius);
        if (mUserDrawable != null) {
            Rect rounded = new Rect();
            dstRect.round(rounded);
            mIntrinsicRadius = Math.min(mUserDrawable.getIntrinsicWidth(),
                                        mUserDrawable.getIntrinsicHeight()) * 0.5f;
            mUserDrawable.setBounds(rounded);
        } else if (mUserIcon != null) {
            // Build square-to-square transformation matrix
            final float iconCX = mUserIcon.getWidth() * 0.5f;
            final float iconCY = mUserIcon.getHeight() * 0.5f;
            mIntrinsicRadius = Math.min(iconCX, iconCY);
            RectF srcRect = new RectF(iconCX - mIntrinsicRadius, iconCY - mIntrinsicRadius,
                                      iconCX + mIntrinsicRadius, iconCY + mIntrinsicRadius);
            mIconMatrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.FILL);
        }

        invalidateSelf();
    }

    @Override
    public void invalidateSelf() {
        super.invalidateSelf();
        mInvalidated = true;
    }

    @Override
    public boolean isStateful() {
        return mFrameColor != null && mFrameColor.isStateful();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return (mSize <= 0 ? (int) mIntrinsicRadius * 2 : mSize);
    }

    @Override
    public int getIntrinsicHeight() {
        return getIntrinsicWidth();
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        unscheduleSelf(what);
    }
}
