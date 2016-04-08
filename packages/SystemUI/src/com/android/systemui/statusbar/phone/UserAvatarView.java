/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import com.android.settingslib.drawable.UserIconDrawable;
import com.android.systemui.R;

/**
 * A view that displays a user image cropped to a circle with an optional frame.
 */
public class UserAvatarView extends View {

    private final UserIconDrawable mDrawable = new UserIconDrawable();

    public UserAvatarView(Context context, AttributeSet attrs,
            int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.UserAvatarView, defStyleAttr, defStyleRes);
        final int N = a.getIndexCount();
        for (int i = 0; i < N; i++) {
            int attr = a.getIndex(i);
            switch (attr) {
                case R.styleable.UserAvatarView_avatarPadding:
                    setAvatarPadding(a.getDimension(attr, 0));
                    break;
                case R.styleable.UserAvatarView_frameWidth:
                    setFrameWidth(a.getDimension(attr, 0));
                    break;
                case R.styleable.UserAvatarView_framePadding:
                    setFramePadding(a.getDimension(attr, 0));
                    break;
                case R.styleable.UserAvatarView_frameColor:
                    setFrameColor(a.getColorStateList(attr));
                    break;
                case R.styleable.UserAvatarView_badgeDiameter:
                    setBadgeDiameter(a.getDimension(attr, 0));
                    break;
                case R.styleable.UserAvatarView_badgeMargin:
                    setBadgeMargin(a.getDimension(attr, 0));
                    break;
            }
        }
        a.recycle();
        setBackground(mDrawable);
    }

    public UserAvatarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public UserAvatarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UserAvatarView(Context context) {
        this(context, null);
    }

    /**
     * @deprecated use {@link #setAvatar(Bitmap)} instead.
     */
    @Deprecated
    public void setBitmap(Bitmap bitmap) {
        setAvatar(bitmap);
    }

    public void setFrameColor(ColorStateList color) {
        mDrawable.setFrameColor(color);
    }

    public void setFrameWidth(float frameWidth) {
        mDrawable.setFrameWidth(frameWidth);
    }

    public void setFramePadding(float framePadding) {
        mDrawable.setFramePadding(framePadding);
    }

    public void setAvatarPadding(float avatarPadding) {
        mDrawable.setPadding(avatarPadding);
    }

    public void setBadgeDiameter(float diameter) {
        mDrawable.setBadgeRadius(diameter * 0.5f);
    }

    public void setBadgeMargin(float margin) {
        mDrawable.setBadgeMargin(margin);
    }

    public void setAvatar(Bitmap avatar) {
        mDrawable.setIcon(avatar);
        mDrawable.setBadge(null);
    }

    public void setAvatarWithBadge(Bitmap avatar, int userId) {
        mDrawable.setIcon(avatar);
        mDrawable.setBadgeIfManagedUser(getContext(), userId);
    }

    public void setDrawable(Drawable d) {
        if (d instanceof UserIconDrawable) {
            throw new RuntimeException("Recursively adding UserIconDrawable");
        }
        mDrawable.setIconDrawable(d);
        mDrawable.setBadge(null);
    }

    public void setDrawableWithBadge(Drawable d, int userId) {
        if (d instanceof UserIconDrawable) {
            throw new RuntimeException("Recursively adding UserIconDrawable");
        }
        mDrawable.setIconDrawable(d);
        mDrawable.setBadgeIfManagedUser(getContext(), userId);
    }
}
