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
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;

class KeyguardMultiUserAvatar extends FrameLayout {

    private ImageView mUserImage;
    private TextView mUserName;
    private UserInfo mUserInfo;
    private static final int INACTIVE_COLOR = 85;
    private static final int INACTIVE_ALPHA = 195;
    private static final float ACTIVE_SCALE = 1.1f;
    private boolean mActive;
    private boolean mInit = true;
    private KeyguardMultiUserSelectorView mUserSelector;

    boolean mPressedStateLocked = false;
    boolean mTempPressedStateHolder = false;

    public static KeyguardMultiUserAvatar fromXml(int resId, Context context,
            KeyguardMultiUserSelectorView userSelector, UserInfo info) {
        KeyguardMultiUserAvatar icon = (KeyguardMultiUserAvatar)
                LayoutInflater.from(context).inflate(resId, userSelector, false);

        icon.setup(info, userSelector);
        return icon;
    }

    public KeyguardMultiUserAvatar(Context context) {
        super(context, null, 0);
    }

    public KeyguardMultiUserAvatar(Context context, AttributeSet attrs) {
        super(context, attrs, 0);
    }

    public KeyguardMultiUserAvatar(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setup(UserInfo user, KeyguardMultiUserSelectorView userSelector) {
        mUserInfo = user;
        mUserSelector = userSelector;
        init();
    }

    private void init() {
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
            final int finalFilterAlpha = mActive ? 0 : INACTIVE_ALPHA;
            final int initFilterAlpha = mActive ? INACTIVE_ALPHA : 0;

            final float finalScale = mActive ? ACTIVE_SCALE : 1.0f;
            final float initScale = mActive ? 1.0f : ACTIVE_SCALE;

            if (active) {
                KeyguardSubdivisionLayout parent = (KeyguardSubdivisionLayout) getParent();
                parent.setTopChild(parent.indexOfChild(this));
            }

            if (animate) {
                ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                va.addUpdateListener(new AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        float r = animation.getAnimatedFraction();
                        float scale = (1 - r) * initScale + r * finalScale;
                        int filterAlpha = (int) ((1 - r) * initFilterAlpha + r * finalFilterAlpha);
                        setScaleX(scale);
                        setScaleY(scale);
                        mUserImage.setColorFilter(Color.argb(filterAlpha, INACTIVE_COLOR,
                                INACTIVE_COLOR, INACTIVE_COLOR));
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
                mUserImage.setColorFilter(Color.argb(finalFilterAlpha, INACTIVE_COLOR,
                        INACTIVE_COLOR, INACTIVE_COLOR));
                if (onComplete != null) {
                    post(onComplete);
                }
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
            if (pressed) {
                mUserImage.setColorFilter(Color.argb(0, INACTIVE_COLOR,
                        INACTIVE_COLOR, INACTIVE_COLOR));
            } else if (!mActive) {
                mUserImage.setColorFilter(Color.argb(INACTIVE_ALPHA, INACTIVE_COLOR,
                        INACTIVE_COLOR, INACTIVE_COLOR));
            }
        } else {
            mTempPressedStateHolder = pressed;
        }
    }

    public UserInfo getUserInfo() {
        return mUserInfo;
    }
}
