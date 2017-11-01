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

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.util.ArrayUtils;
import com.android.settingslib.drawable.UserIconDrawable;
import com.android.systemui.FontSizeUtils;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.UserAvatarView;

/**
 * Displays one user in the {@link UserDetailView} view.
 */
public class UserDetailItemView extends LinearLayout {

    protected static int layoutResId = R.layout.qs_user_detail_item;

    private UserAvatarView mAvatar;
    private TextView mName;
    private Typeface mRegularTypeface;
    private Typeface mActivatedTypeface;
    private View mRestrictedPadlock;

    public UserDetailItemView(Context context) {
        this(context, null);
    }

    public UserDetailItemView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UserDetailItemView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public UserDetailItemView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.UserDetailItemView, defStyleAttr, defStyleRes);
        final int N = a.getIndexCount();
        for (int i = 0; i < N; i++) {
            int attr = a.getIndex(i);
            switch (attr) {
                case R.styleable.UserDetailItemView_regularFontFamily:
                    mRegularTypeface = Typeface.create(a.getString(attr), 0 /* style */);
                    break;
                case R.styleable.UserDetailItemView_activatedFontFamily:
                    mActivatedTypeface = Typeface.create(a.getString(attr), 0 /* style */);
                    break;
            }
        }
        a.recycle();
    }

    public static UserDetailItemView convertOrInflate(Context context, View convertView,
            ViewGroup root) {
        if (!(convertView instanceof UserDetailItemView)) {
            convertView = LayoutInflater.from(context).inflate(
                    layoutResId, root, false);
        }
        return (UserDetailItemView) convertView;
    }

    public void bind(String name, Bitmap picture, int userId) {
        mName.setText(name);
        mAvatar.setAvatarWithBadge(picture, userId);
    }

    public void bind(String name, Drawable picture, int userId) {
        mName.setText(name);
        mAvatar.setDrawableWithBadge(picture, userId);
    }

    public void setAvatarEnabled(boolean enabled) {
        mAvatar.setEnabled(enabled);
    }

    public void setDisabledByAdmin(boolean disabled) {
        mRestrictedPadlock.setVisibility(disabled ? View.VISIBLE : View.GONE);
        mName.setEnabled(!disabled);
        mAvatar.setEnabled(!disabled);
    }

    public void setEnabled(boolean enabled) {
        mName.setEnabled(enabled);
        mAvatar.setEnabled(enabled);
    }

    @Override
    protected void onFinishInflate() {
        mAvatar = findViewById(R.id.user_picture);
        mName = findViewById(R.id.user_name);
        if (mRegularTypeface == null) {
            mRegularTypeface = mName.getTypeface();
        }
        if (mActivatedTypeface == null) {
            mActivatedTypeface = mName.getTypeface();
        }
        updateTypeface();
        mRestrictedPadlock = findViewById(R.id.restricted_padlock);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        FontSizeUtils.updateFontSize(mName, R.dimen.qs_detail_item_secondary_text_size);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        updateTypeface();
    }

    private void updateTypeface() {
        boolean activated = ArrayUtils.contains(getDrawableState(), android.R.attr.state_activated);
        mName.setTypeface(activated ? mActivatedTypeface : mRegularTypeface);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
