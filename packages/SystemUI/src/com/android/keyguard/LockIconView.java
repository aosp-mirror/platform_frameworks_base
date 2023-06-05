/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.graphics.ColorUtils;
import com.android.settingslib.Utils;
import com.android.systemui.Dumpable;
import com.android.systemui.R;

import java.io.PrintWriter;

/**
 * A view positioned under the notification shade.
 */
public class LockIconView extends FrameLayout implements Dumpable {
    @IntDef({ICON_NONE, ICON_LOCK, ICON_FINGERPRINT, ICON_UNLOCK})
    public @interface IconType {}

    public static final int ICON_NONE = -1;
    public static final int ICON_LOCK = 0;
    public static final int ICON_FINGERPRINT = 1;
    public static final int ICON_UNLOCK = 2;

    private @IconType int mIconType;
    private boolean mAod;

    @NonNull private final RectF mSensorRect;
    @NonNull private Point mLockIconCenter = new Point(0, 0);
    private float mRadius;
    private int mLockIconPadding;

    private ImageView mLockIcon;
    private ImageView mBgView;

    private int mLockIconColor;
    private boolean mUseBackground = false;
    private float mDozeAmount = 0f;

    public LockIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSensorRect = new RectF();

        addBgImageView(context, attrs);
        addLockIconImageView(context, attrs);
    }

    void setDozeAmount(float dozeAmount) {
        mDozeAmount = dozeAmount;
        updateColorAndBackgroundVisibility();
    }

    void updateColorAndBackgroundVisibility() {
        if (mUseBackground && mLockIcon.getDrawable() != null) {
            mLockIconColor = ColorUtils.blendARGB(
                    Utils.getColorAttrDefaultColor(getContext(), android.R.attr.textColorPrimary),
                    Color.WHITE,
                    mDozeAmount);
            int backgroundColor = Utils.getColorAttrDefaultColor(getContext(),
                    com.android.internal.R.attr.colorSurface);
            mBgView.setImageTintList(ColorStateList.valueOf(backgroundColor));
            mBgView.setAlpha(1f - mDozeAmount);
            mBgView.setVisibility(View.VISIBLE);
        } else {
            mLockIconColor = ColorUtils.blendARGB(
                    Utils.getColorAttrDefaultColor(getContext(), R.attr.wallpaperTextColorAccent),
                    Color.WHITE,
                    mDozeAmount);
            mBgView.setVisibility(View.GONE);
        }

        mLockIcon.setImageTintList(ColorStateList.valueOf(mLockIconColor));
    }

    void setImageDrawable(Drawable drawable) {
        mLockIcon.setImageDrawable(drawable);

        if (!mUseBackground) return;

        if (drawable == null) {
            mBgView.setVisibility(View.INVISIBLE);
        } else {
            mBgView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Whether or not to render the lock icon background. Mainly used for UDPFS.
     */
    public void setUseBackground(boolean useBackground) {
        mUseBackground = useBackground;
        updateColorAndBackgroundVisibility();
    }

    /**
     * Set the location of the lock icon.
     */
    @VisibleForTesting
    public void setCenterLocation(@NonNull Point center, float radius, int drawablePadding) {
        mLockIconCenter = center;
        mRadius = radius;
        mLockIconPadding = drawablePadding;

        mLockIcon.setPadding(mLockIconPadding, mLockIconPadding, mLockIconPadding,
                mLockIconPadding);

        // mSensorProps coordinates assume portrait mode which is OK b/c the keyguard is always in
        // portrait.
        mSensorRect.set(mLockIconCenter.x - mRadius,
                mLockIconCenter.y - mRadius,
                mLockIconCenter.x + mRadius,
                mLockIconCenter.y + mRadius);

        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.width = (int) (mSensorRect.right - mSensorRect.left);
        lp.height = (int) (mSensorRect.bottom - mSensorRect.top);
        lp.topMargin = (int) mSensorRect.top;
        lp.setMarginStart((int) mSensorRect.left);
        setLayoutParams(lp);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    float getLocationTop() {
        return mLockIconCenter.y - mRadius;
    }

    float getLocationBottom() {
        return mLockIconCenter.y + mRadius;
    }

    /**
     * Updates the icon its default state where no visual is shown.
     */
    public void clearIcon() {
        updateIcon(ICON_NONE, false);
    }

    /**
     * Transition the current icon to a new state
     * @param icon type (ie: lock icon, unlock icon, fingerprint icon)
     * @param aod whether to use the aod icon variant (some icons don't have aod variants and will
     *            therefore show no icon)
     */
    public void updateIcon(@IconType int icon, boolean aod) {
        mIconType = icon;
        mAod = aod;

        mLockIcon.setImageState(getLockIconState(mIconType, mAod), true);
    }

    private void addLockIconImageView(Context context, AttributeSet attrs) {
        mLockIcon = new ImageView(context, attrs);
        mLockIcon.setId(R.id.lock_icon);
        mLockIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(mLockIcon);
        LayoutParams lp = (LayoutParams) mLockIcon.getLayoutParams();
        lp.height = MATCH_PARENT;
        lp.width = MATCH_PARENT;
        lp.gravity = Gravity.CENTER;
        mLockIcon.setLayoutParams(lp);
    }

    private void addBgImageView(Context context, AttributeSet attrs) {
        mBgView = new ImageView(context, attrs);
        mBgView.setId(R.id.lock_icon_bg);
        mBgView.setImageDrawable(context.getDrawable(R.drawable.fingerprint_bg));
        mBgView.setVisibility(View.INVISIBLE);
        addView(mBgView);
        LayoutParams lp = (LayoutParams) mBgView.getLayoutParams();
        lp.height = MATCH_PARENT;
        lp.width = MATCH_PARENT;
        mBgView.setLayoutParams(lp);
    }

    private static int[] getLockIconState(@IconType int icon, boolean aod) {
        if (icon == ICON_NONE) {
            return new int[0];
        }

        int[] lockIconState = new int[2];
        switch (icon) {
            case ICON_LOCK:
                lockIconState[0] = android.R.attr.state_first;
                break;
            case ICON_FINGERPRINT:
                lockIconState[0] = android.R.attr.state_middle;
                break;
            case ICON_UNLOCK:
                lockIconState[0] = android.R.attr.state_last;
                break;
        }

        if (aod) {
            lockIconState[1] = android.R.attr.state_single;
        } else {
            lockIconState[1] = -android.R.attr.state_single;
        }

        return lockIconState;
    }

    private String typeToString(@IconType int type) {
        switch (type) {
            case ICON_NONE:
                return "none";
            case ICON_LOCK:
                return "lock";
            case ICON_FINGERPRINT:
                return "fingerprint";
            case ICON_UNLOCK:
                return "unlock";
        }

        return "invalid";
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("Lock Icon View Parameters:");
        pw.println("    Center in px (x, y)= ("
                + mLockIconCenter.x + ", " + mLockIconCenter.y + ")");
        pw.println("    Radius in pixels: " + mRadius);
        pw.println("    Drawable padding: " + mLockIconPadding);
        pw.println("    mIconType=" + typeToString(mIconType));
        pw.println("    mAod=" + mAod);
        pw.println("Lock Icon View actual measurements:");
        pw.println("    topLeft= (" + getX() + ", " + getY() + ")");
        pw.println("    width=" + getWidth() + " height=" + getHeight());
    }
}
