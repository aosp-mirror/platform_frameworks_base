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

package com.android.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.UserManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

class KeyguardMultiUserAvatar extends FrameLayout {
    private static final String TAG = KeyguardMultiUserAvatar.class.getSimpleName();
    private static final boolean DEBUG = KeyguardHostView.DEBUG;

    private ImageView mUserImage;
    private TextView mUserName;
    private UserInfo mUserInfo;
    private static final float ACTIVE_ALPHA = 1.0f;
    private static final float INACTIVE_ALPHA = 1.0f;
    private static final float ACTIVE_SCALE = 1.5f;
    private static final float ACTIVE_TEXT_ALPHA = 0f;
    private static final float INACTIVE_TEXT_ALPHA = 0.5f;
    private static final int SWITCH_ANIMATION_DURATION = 150;

    private final float mActiveAlpha;
    private final float mActiveScale;
    private final float mActiveTextAlpha;
    private final float mInactiveAlpha;
    private final float mInactiveTextAlpha;
    private final float mShadowRadius;
    private final float mStroke;
    private final float mIconSize;
    private final int mFrameColor;
    private final int mFrameShadowColor;
    private final int mTextColor;
    private final int mHighlightColor;

    private boolean mTouched;

    private boolean mActive;
    private boolean mInit = true;
    private KeyguardMultiUserSelectorView mUserSelector;
    private KeyguardCircleFramedDrawable mFramed;
    private boolean mPressLock;
    private UserManager mUserManager;

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
        mTextColor = res.getColor(R.color.keyguard_avatar_nick_color);
        mIconSize = res.getDimension(R.dimen.keyguard_avatar_size);
        mStroke = res.getDimension(R.dimen.keyguard_avatar_frame_stroke_width);
        mShadowRadius = res.getDimension(R.dimen.keyguard_avatar_frame_shadow_radius);
        mFrameColor = res.getColor(R.color.keyguard_avatar_frame_color);
        mFrameShadowColor = res.getColor(R.color.keyguard_avatar_frame_shadow_color);
        mHighlightColor = res.getColor(R.color.keyguard_avatar_frame_pressed_color);
        mActiveTextAlpha = ACTIVE_TEXT_ALPHA;
        mInactiveTextAlpha = INACTIVE_TEXT_ALPHA;
        mActiveScale = ACTIVE_SCALE;
        mActiveAlpha = ACTIVE_ALPHA;
        mInactiveAlpha = INACTIVE_ALPHA;
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);

        mTouched = false;

        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    protected String rewriteIconPath(String path) {
        return path;
    }

    public void init(UserInfo user, KeyguardMultiUserSelectorView userSelector) {
        mUserInfo = user;
        mUserSelector = userSelector;

        mUserImage = (ImageView) findViewById(R.id.keyguard_user_avatar);
        mUserName = (TextView) findViewById(R.id.keyguard_user_name);

        mFramed = (KeyguardCircleFramedDrawable)
                KeyguardViewMediator.getAvatarCache().get(user.id);

        // If we can't find it or the params don't match, create the drawable again
        if (mFramed == null
                || !mFramed.verifyParams(mIconSize, mFrameColor, mStroke, mFrameShadowColor,
                        mShadowRadius, mHighlightColor)) {
            Bitmap icon = null;
            try {
                icon = mUserManager.getUserIcon(user.id);
            } catch (Exception e) {
                if (DEBUG) Log.d(TAG, "failed to get profile icon " + user, e);
            }

            if (icon == null) {
                icon = BitmapFactory.decodeResource(mContext.getResources(),
                        com.android.internal.R.drawable.ic_contact_picture);
            }

            mFramed = new KeyguardCircleFramedDrawable(icon, (int) mIconSize, mFrameColor, mStroke,
                    mFrameShadowColor, mShadowRadius, mHighlightColor);
            KeyguardViewMediator.getAvatarCache().put(user.id, mFramed);
        }

        mFramed.reset();

        mUserImage.setImageDrawable(mFramed);
        mUserName.setText(mUserInfo.name);
        setOnClickListener(mUserSelector);
        mInit = false;
    }

    public void setActive(boolean active, boolean animate, final Runnable onComplete) {
        if (mActive != active || mInit) {
            mActive = active;

            if (active) {
                KeyguardLinearLayout parent = (KeyguardLinearLayout) getParent();
                parent.setTopChild(this);
                // TODO: Create an appropriate asset when string changes are possible.
                setContentDescription(mUserName.getText()
                        + ". " + mContext.getString(R.string.user_switched, ""));
            } else {
                setContentDescription(mUserName.getText());
            }
        }
        updateVisualsForActive(mActive, animate, SWITCH_ANIMATION_DURATION, onComplete);
    }

    void updateVisualsForActive(boolean active, boolean animate, int duration,
            final Runnable onComplete) {
        final float finalAlpha = active ? mActiveAlpha : mInactiveAlpha;
        final float initAlpha = active ? mInactiveAlpha : mActiveAlpha;
        final float finalScale = active ? 1f : 1f / mActiveScale;
        final float initScale = mFramed.getScale();
        final int finalTextAlpha = active ? (int) (mActiveTextAlpha * 255) :
                (int) (mInactiveTextAlpha * 255);
        final int initTextAlpha = active ? (int) (mInactiveTextAlpha * 255) :
                (int) (mActiveTextAlpha * 255);
        int textColor = mTextColor;
        mUserName.setTextColor(textColor);

        if (animate && mTouched) {
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float r = animation.getAnimatedFraction();
                    float scale = (1 - r) * initScale + r * finalScale;
                    float alpha = (1 - r) * initAlpha + r * finalAlpha;
                    int textAlpha = (int) ((1 - r) * initTextAlpha + r * finalTextAlpha);
                    mFramed.setScale(scale);
                    mUserImage.setAlpha(alpha);
                    mUserName.setTextColor(Color.argb(textAlpha, 255, 255, 255));
                    mUserImage.invalidate();
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
            mFramed.setScale(finalScale);
            mUserImage.setAlpha(finalAlpha);
            mUserName.setTextColor(Color.argb(finalTextAlpha, 255, 255, 255));
            if (onComplete != null) {
                post(onComplete);
            }
        }

        mTouched = true;
    }

    @Override
    public void setPressed(boolean pressed) {
        if (mPressLock && !pressed) {
            return;
        }

        if (mPressLock || !pressed || isClickable()) {
            super.setPressed(pressed);
            mFramed.setPressed(pressed);
            mUserImage.invalidate();
        }
    }

    public void lockPressed(boolean pressed) {
        mPressLock = pressed;
        setPressed(pressed);
    }

    public UserInfo getUserInfo() {
        return mUserInfo;
    }
}
