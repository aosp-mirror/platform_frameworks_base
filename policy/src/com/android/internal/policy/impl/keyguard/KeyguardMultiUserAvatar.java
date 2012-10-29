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
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import android.util.Log;
import com.android.internal.R;

class KeyguardMultiUserAvatar extends FrameLayout {

    private ImageView mUserImage;
    private TextView mUserName;
    private UserInfo mUserInfo;
    private static final float ACTIVE_ALPHA = 1.0f;
    private static final float INACTIVE_ALPHA = 1.0f;
    private static final float ACTIVE_SCALE = 1.5f;
    private static final float ACTIVE_TEXT_BACGROUND_ALPHA = 0f;
    private static final float INACTIVE_TEXT_BACGROUND_ALPHA = 0f;
    private static final int SWITCH_ANIMATION_DURATION = 150;

    private final float mActiveAlpha;
    private final float mActiveScale;
    private final float mActiveTextBacgroundAlpha;
    private final float mInactiveAlpha;
    private final float mInactiveTextBacgroundAlpha;
    private final float mShadowDx;
    private final float mShadowDy;
    private final float mShadowRadius;
    private final float mStroke;
    private final float mIconSize;
    private final int mActiveTextColor;
    private final int mFrameColor;
    private final int mFrameShadowColor;
    private final int mInactiveTextColor;
    private final int mMatteColor;

    private boolean mTouched;

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
        mActiveTextColor = 0x00000000;
        mInactiveTextColor = 0x00000000;
        mIconSize = res.getDimension(R.dimen.keyguard_avatar_height);
        mStroke = res.getDimension(R.dimen.keyguard_avatar_frame_stroke_width);
        mShadowRadius = res.getDimension(R.dimen.keyguard_avatar_frame_shadow_radius);
        mShadowDx = res.getDimension(R.dimen.keyguard_avatar_frame_shadow_dx);
        mShadowDy = res.getDimension(R.dimen.keyguard_avatar_frame_shadow_dy);
        mFrameColor = res.getColor(R.color.keyguard_avatar_frame_color);
        mFrameShadowColor = res.getColor(R.color.keyguard_avatar_frame_shadow_color);
        mMatteColor = res.getColor(R.color.keyguard_avatar_matte_color);

        mActiveTextBacgroundAlpha = ACTIVE_TEXT_BACGROUND_ALPHA;
        mInactiveTextBacgroundAlpha = INACTIVE_TEXT_BACGROUND_ALPHA;
        mActiveScale = ACTIVE_SCALE;
        mActiveAlpha = ACTIVE_ALPHA;
        mInactiveAlpha = INACTIVE_ALPHA;

        mTouched = false;
    }

    protected String rewriteIconPath(String path) {
        if (!this.getClass().getName().contains("internal")) {
            return path.replace("system", "data");
        }
        return path;
    }

    // called from within {@link #init} to unpack the profile icon
    protected Drawable createIcon(UserInfo user) {
        Bitmap icon = BitmapFactory.decodeFile(rewriteIconPath(user.iconPath));

        final int width = icon.getWidth();
        final int height = icon.getHeight();
        final int square = Math.min(width, height);
        final float size = (float) Math.floor(mIconSize * mActiveScale);

        final Bitmap output = Bitmap.createBitmap((int) size, (int) size, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(output);

        final Rect srcRect = new Rect((width - square) / 2, (height - square) / 2, square, square);
        final RectF dstRect = new RectF(0f, 0f, size, size);
        final RectF frameRect = new RectF(0f, 0f, size - mStroke, size - mStroke);
        frameRect.offset(mStroke / 2f, mStroke / 2f);

        final Path fillPath = new Path();
        fillPath.addArc(dstRect, 0f, 360f);

        final Path framePath = new Path();
        framePath.addArc(frameRect, 0f, 360f);

        // clear background
        canvas.drawARGB(0, 0, 0, 0);

        // opaque circle matte
        final Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStrokeWidth(mStroke);
        paint.setColor(mMatteColor);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(fillPath, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));

        // mask in the icon where the bitmap is opaque
        canvas.drawBitmap(icon, srcRect, dstRect, paint);

        // white frame
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(mFrameColor);
        paint.setShadowLayer(mShadowRadius, mShadowDx, mShadowDy, mFrameShadowColor);
        canvas.drawPath(framePath, paint);

        return new BitmapDrawable(output);
    }

    public void init(UserInfo user, KeyguardMultiUserSelectorView userSelector) {
        mUserInfo = user;
        mUserSelector = userSelector;

        mUserImage = (ImageView) findViewById(R.id.keyguard_user_avatar);
        mUserName = (TextView) findViewById(R.id.keyguard_user_name);

        mUserImage.setImageDrawable(createIcon(mUserInfo));
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
            }
        }
        updateVisualsForActive(mActive, animate, SWITCH_ANIMATION_DURATION, onComplete);
    }

    void updateVisualsForActive(boolean active, boolean animate, int duration,
            final Runnable onComplete) {
        final float finalAlpha = active ? mActiveAlpha : mInactiveAlpha;
        final float initAlpha = active ? mInactiveAlpha : mActiveAlpha;
        final float finalScale = active ? mActiveScale : 1.0f;
        final float initScale = getScaleX();
        final int finalTextBgAlpha = active ? (int) (mActiveTextBacgroundAlpha * 255) :
            (int) (mInactiveTextBacgroundAlpha * 255);
        final int initTextBgAlpha = active ? (int) (mInactiveTextBacgroundAlpha * 255) :
            (int) (mActiveTextBacgroundAlpha * 255);
        int textColor = active ? mActiveTextColor : mInactiveTextColor;
        mUserName.setTextColor(textColor);

        if (animate && mTouched) {
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

        mTouched = true;
    }

    public void lockPressedState() {
        mPressedStateLocked = true;
    }

    public void releasePressedState() {
        mPressedStateLocked = false;
    }

    @Override
    public void setPressed(boolean pressed) {
        if (!mPressedStateLocked) {
            super.setPressed(pressed);
            updateVisualsForActive(pressed || mActive, false, SWITCH_ANIMATION_DURATION, null);
        } else {
            mTempPressedStateHolder = pressed;
        }
    }

    public UserInfo getUserInfo() {
        return mUserInfo;
    }
}
