/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;

class KeyguardMultiUserAvatar extends FrameLayout {

    private ImageView mUserImage;
    private TextView mUserName;
    private UserInfo mUserInfo;
    private static final float ACTIVE_ALPHA = 1.0f;
    private static final float INACTIVE_ALPHA = 0.5f;
    private static final float ACTIVE_SCALE = 1.2f;
    private static final float ACTIVE_TEXT_BACGROUND_ALPHA = 0.5f;
    private static final float INACTIVE_TEXT_BACGROUND_ALPHA = 0f;
    private static int mActiveTextColor;
    private static int mInactiveTextColor;
    private boolean mActive;
    private boolean mInit = true;
    private KeyguardMultiUserSelectorView mUserSelector;

    boolean mPressedStateLocked = false;
    boolean mTempPressedStateHolder = false;

    public static KeyguardMultiUserAvatar fromXml(int resId, Context context,
            KeyguardMultiUserSelectorView userSelector, UserInfo info) {
        KeyguardMultiUserAvatar icon = (KeyguardMultiUserAvatar)
                LayoutInflater.from(context).inflate(resId, userSelector, false);

        icon.init(info, userSelector);
        return icon;
    }

    public KeyguardMultiUserAvatar(Context context) {
        this(context, null, 0);
    }

    public KeyguardMultiUserAvatar(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardMultiUserAvatar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        Resources res = mContext.getResources();
        mActiveTextColor = res.getColor(R.color.kg_multi_user_text_active);
        mInactiveTextColor = res.getColor(R.color.kg_multi_user_text_inactive);
    }

    public void init(UserInfo user, KeyguardMultiUserSelectorView userSelector) {
        mUserInfo = user;
        mUserSelector = userSelector;

        mUserImage = (ImageView) findViewById(R.id.keyguard_user_avatar);
        mUserName = (TextView) findViewById(R.id.keyguard_user_name);

        mUserImage.setImageDrawable(Drawable.createFromPath(mUserInfo.iconPath));
        mUserName.setText(mUserInfo.name);
        setOnClickListener(mUserSelector);
        setActive(false, false, 0, null);
        mInit = false;
    }

    public void setActive(boolean active, boolean animate, int duration, final Runnable onComplete) {
        if (mActive != active || mInit) {
            mActive = active;

            if (active) {
                KeyguardSubdivisionLayout parent = (KeyguardSubdivisionLayout) getParent();
                parent.setTopChild(parent.indexOfChild(this));
            }
        }
        updateVisualsForActive(mActive, animate, duration, true, onComplete);
    }

    void updateVisualsForActive(boolean active, boolean animate, int duration, boolean scale,
            final Runnable onComplete) {
        final float finalAlpha = active ? ACTIVE_ALPHA : INACTIVE_ALPHA;
        final float initAlpha = active ? INACTIVE_ALPHA : ACTIVE_ALPHA;
        final float finalScale = active && scale ? ACTIVE_SCALE : 1.0f;
        final float initScale = active ? 1.0f : ACTIVE_SCALE;
        final int finalTextBgAlpha = active ? (int) (ACTIVE_TEXT_BACGROUND_ALPHA * 255) :
            (int) (INACTIVE_TEXT_BACGROUND_ALPHA * 255);
        final int initTextBgAlpha = active ? (int) (INACTIVE_TEXT_BACGROUND_ALPHA * 255) :
            (int) (ACTIVE_TEXT_BACGROUND_ALPHA * 255);
        int textColor = active ? mActiveTextColor : mInactiveTextColor;
        mUserName.setTextColor(textColor);

        if (animate) {
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float r = animation.getAnimatedFraction();
                    float scale = (1 - r) * initScale + r * finalScale;
                    float alpha = (1 - r) * initAlpha + r * finalAlpha;
                    int textBgAlpha = (int) ((1 - r) * initTextBgAlpha + r * finalTextBgAlpha);
                    setScaleX(scale);
                    setScaleY(scale);
                    mUserImage.setAlpha(alpha);
                    mUserName.setBackgroundColor(Color.argb(textBgAlpha, 0, 0, 0));
                    mUserSelector.invalidate();
                }
            });
            va.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
            });
            va.setDuration(duration);
            va.start();
        } else {
            setScaleX(finalScale);
            setScaleY(finalScale);
            mUserImage.setAlpha(finalAlpha);
            mUserName.setBackgroundColor(Color.argb(finalTextBgAlpha, 0, 0, 0));
            if (onComplete != null) {
                post(onComplete);
            }
        }
    }

    public void lockPressedState() {
        mPressedStateLocked = true;
    }

    public void resetPressedState() {
        mPressedStateLocked = false;
        post(new Runnable() {
            @Override
            public void run() {
                KeyguardMultiUserAvatar.this.setPressed(mTempPressedStateHolder);
            }
        });
    }

    @Override
    public void setPressed(boolean pressed) {
        if (!mPressedStateLocked) {
            super.setPressed(pressed);
            updateVisualsForActive(pressed || mActive, false, 0, mActive, null);
        } else {
            mTempPressedStateHolder = pressed;
        }
    }

    public UserInfo getUserInfo() {
        return mUserInfo;
    }
}
