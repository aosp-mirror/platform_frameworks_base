/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.annotation.IntDef;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Trace;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.ViewTreeObserver.OnPreDrawListener;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.statusbar.KeyguardAffordanceView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Manages the different states and animations of the unlock icon.
 */
public class LockIcon extends KeyguardAffordanceView {

    static final int STATE_LOCKED = 0;
    static final int STATE_LOCK_OPEN = 1;
    static final int STATE_SCANNING_FACE = 2;
    static final int STATE_BIOMETRICS_ERROR = 3;
    private float mDozeAmount;
    private int mIconColor = Color.TRANSPARENT;
    private int mOldState;
    private int mState;
    private boolean mDozing;
    private boolean mKeyguardJustShown;
    private boolean mPredrawRegistered;
    private final SparseArray<Drawable> mDrawableCache = new SparseArray<>();

    private final OnPreDrawListener mOnPreDrawListener = new OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            getViewTreeObserver().removeOnPreDrawListener(this);
            mPredrawRegistered = false;

            int newState = mState;
            Drawable icon = getIcon(newState);
            setImageDrawable(icon, false);

            if (newState == STATE_SCANNING_FACE) {
                announceForAccessibility(getResources().getString(
                        R.string.accessibility_scanning_face));
            }

            if (icon instanceof AnimatedVectorDrawable) {
                final AnimatedVectorDrawable animation = (AnimatedVectorDrawable) icon;
                animation.forceAnimationOnUI();
                animation.clearAnimationCallbacks();
                animation.registerAnimationCallback(
                        new Animatable2.AnimationCallback() {
                            @Override
                            public void onAnimationEnd(Drawable drawable) {
                                if (getDrawable() == animation
                                        && newState == mState
                                        && newState == STATE_SCANNING_FACE) {
                                    animation.start();
                                } else {
                                    Trace.endAsyncSection("LockIcon#Animation", newState);
                                }
                            }
                        });
                Trace.beginAsyncSection("LockIcon#Animation", newState);
                animation.start();
            }

            return true;
        }
    };

    public LockIcon(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDrawableCache.clear();
    }

    /**
     * Update the icon visibility
     * @return true if the visibility changed
     */
    boolean updateIconVisibility(boolean visible) {
        boolean wasVisible = getVisibility() == VISIBLE;
        if (visible != wasVisible) {
            setVisibility(visible ? VISIBLE : INVISIBLE);
            animate().cancel();
            if (visible) {
                setScaleX(0);
                setScaleY(0);
                animate()
                        .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                        .scaleX(1)
                        .scaleY(1)
                        .withLayer()
                        .setDuration(233)
                        .start();
            }
            return true;
        }
        return false;
    }

    void update(int newState, boolean dozing, boolean keyguardJustShown) {
        mOldState = mState;
        mState = newState;
        mDozing = dozing;
        mKeyguardJustShown = keyguardJustShown;

        if (!mPredrawRegistered) {
            mPredrawRegistered = true;
            getViewTreeObserver().addOnPreDrawListener(mOnPreDrawListener);
        }
    }

    void setDozeAmount(float dozeAmount) {
        mDozeAmount = dozeAmount;
        updateDarkTint();
    }

    void updateColor(int iconColor) {
        if (mIconColor == iconColor) {
            return;
        }
        mDrawableCache.clear();
        mIconColor = iconColor;
        updateDarkTint();
    }

    private void updateDarkTint() {
        int color = ColorUtils.blendARGB(mIconColor, Color.WHITE, mDozeAmount);
        setImageTintList(ColorStateList.valueOf(color));
    }

    private Drawable getIcon(int newState) {
        @LockAnimIndex final int lockAnimIndex =
                getAnimationIndexForTransition(mOldState, newState, mDozing, mKeyguardJustShown);

        boolean isAnim = lockAnimIndex != -1;
        int iconRes = isAnim ? getThemedAnimationResId(lockAnimIndex) : getIconForState(newState);

        if (!mDrawableCache.contains(iconRes)) {
            mDrawableCache.put(iconRes, getContext().getDrawable(iconRes));
        }

        return mDrawableCache.get(iconRes);
    }

    private static int getIconForState(int state) {
        int iconRes;
        switch (state) {
            case STATE_LOCKED:
            // Scanning animation is a pulsing padlock. This means that the resting state is
            // just a padlock.
            case STATE_SCANNING_FACE:
            // Error animation also starts and ands on the padlock.
            case STATE_BIOMETRICS_ERROR:
                iconRes = com.android.internal.R.drawable.ic_lock;
                break;
            case STATE_LOCK_OPEN:
                iconRes = com.android.internal.R.drawable.ic_lock_open;
                break;
            default:
                throw new IllegalArgumentException();
        }

        return iconRes;
    }

    private static int getAnimationIndexForTransition(int oldState, int newState, boolean dozing,
            boolean keyguardJustShown) {

        // Never animate when screen is off
        if (dozing) {
            return -1;
        }

        if (newState == STATE_BIOMETRICS_ERROR) {
            return ERROR;
        } else if (oldState != STATE_LOCK_OPEN && newState == STATE_LOCK_OPEN) {
            return UNLOCK;
        } else if (oldState == STATE_LOCK_OPEN && newState == STATE_LOCKED && !keyguardJustShown) {
            return LOCK;
        } else if (newState == STATE_SCANNING_FACE) {
            return SCANNING;
        }
        return -1;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ERROR, UNLOCK, LOCK, SCANNING})
    @interface LockAnimIndex {}
    static final int ERROR = 0, UNLOCK = 1, LOCK = 2, SCANNING = 3;
    private static final int[][] LOCK_ANIM_RES_IDS = new int[][] {
            {
                    R.anim.lock_to_error,
                    R.anim.lock_unlock,
                    R.anim.lock_lock,
                    R.anim.lock_scanning
            },
            {
                    R.anim.lock_to_error_circular,
                    R.anim.lock_unlock_circular,
                    R.anim.lock_lock_circular,
                    R.anim.lock_scanning_circular
            },
            {
                    R.anim.lock_to_error_filled,
                    R.anim.lock_unlock_filled,
                    R.anim.lock_lock_filled,
                    R.anim.lock_scanning_filled
            },
            {
                    R.anim.lock_to_error_rounded,
                    R.anim.lock_unlock_rounded,
                    R.anim.lock_lock_rounded,
                    R.anim.lock_scanning_rounded
            },
    };

    private int getThemedAnimationResId(@LockAnimIndex int lockAnimIndex) {
        final String setting = TextUtils.emptyIfNull(
                Settings.Secure.getString(getContext().getContentResolver(),
                        Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES));
        if (setting.contains("com.android.theme.icon_pack.circular.android")) {
            return LOCK_ANIM_RES_IDS[1][lockAnimIndex];
        } else if (setting.contains("com.android.theme.icon_pack.filled.android")) {
            return LOCK_ANIM_RES_IDS[2][lockAnimIndex];
        } else if (setting.contains("com.android.theme.icon_pack.rounded.android")) {
            return LOCK_ANIM_RES_IDS[3][lockAnimIndex];
        }
        return LOCK_ANIM_RES_IDS[0][lockAnimIndex];
    }
}
