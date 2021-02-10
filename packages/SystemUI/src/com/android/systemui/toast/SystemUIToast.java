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

package com.android.systemui.toast;

import android.animation.Animator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ToastPresenter;

import com.android.internal.R;
import com.android.launcher3.icons.IconFactory;
import com.android.systemui.plugins.ToastPlugin;

/**
 * SystemUI TextToast that can be customized by ToastPlugins. Should never instantiate this class
 * directly. Instead, use {@link ToastFactory#createToast}.
 */
public class SystemUIToast implements ToastPlugin.Toast {
    final Context mContext;
    final CharSequence mText;
    final ToastPlugin.Toast mPluginToast;

    private final String mPackageName;
    private final int mUserId;
    private final LayoutInflater mLayoutInflater;
    private final boolean mToastStyleEnabled;

    final int mDefaultX = 0;
    final int mDefaultHorizontalMargin = 0;
    final int mDefaultVerticalMargin = 0;

    private int mDefaultY;
    private int mDefaultGravity;

    @NonNull private final View mToastView;
    @Nullable private final Animator mInAnimator;
    @Nullable private final Animator mOutAnimator;

    SystemUIToast(LayoutInflater layoutInflater, Context context, CharSequence text,
            String packageName, int userId, boolean toastStyleEnabled, int orientation) {
        this(layoutInflater, context, text, null, packageName, userId,
                toastStyleEnabled, orientation);
    }

    SystemUIToast(LayoutInflater layoutInflater, Context context, CharSequence text,
            ToastPlugin.Toast pluginToast, String packageName, int userId,
            boolean toastStyleEnabled, int orientation) {
        mToastStyleEnabled = toastStyleEnabled;
        mLayoutInflater = layoutInflater;
        mContext = context;
        mText = text;
        mPluginToast = pluginToast;
        mPackageName = packageName;
        mUserId = userId;
        mToastView = inflateToastView();
        mInAnimator = createInAnimator();
        mOutAnimator = createOutAnimator();

        onOrientationChange(orientation);
    }

    @Override
    @NonNull
    public Integer getGravity() {
        if (isPluginToast() && mPluginToast.getGravity() != null) {
            return mPluginToast.getGravity();
        }
        return mDefaultGravity;
    }

    @Override
    @NonNull
    public Integer getXOffset() {
        if (isPluginToast() && mPluginToast.getXOffset() != null) {
            return mPluginToast.getXOffset();
        }
        return mDefaultX;
    }

    @Override
    @NonNull
    public Integer getYOffset() {
        if (isPluginToast() && mPluginToast.getYOffset() != null) {
            return mPluginToast.getYOffset();
        }
        return mDefaultY;
    }

    @Override
    @NonNull
    public Integer getHorizontalMargin() {
        if (isPluginToast() && mPluginToast.getHorizontalMargin() != null) {
            return mPluginToast.getHorizontalMargin();
        }
        return mDefaultHorizontalMargin;
    }

    @Override
    @NonNull
    public Integer getVerticalMargin() {
        if (isPluginToast() && mPluginToast.getVerticalMargin() != null) {
            return mPluginToast.getVerticalMargin();
        }
        return mDefaultVerticalMargin;
    }

    @Override
    @NonNull
    public View getView() {
        return mToastView;
    }

    @Override
    @Nullable
    public Animator getInAnimation() {
        return mInAnimator;
    }

    @Override
    @Nullable
    public Animator getOutAnimation() {
        return mOutAnimator;
    }

    /**
     * Whether this toast has a custom animation.
     */
    public boolean hasCustomAnimation() {
        return getInAnimation() != null || getOutAnimation() != null;
    }

    private boolean isPluginToast() {
        return mPluginToast != null;
    }

    private View inflateToastView() {
        if (isPluginToast() && mPluginToast.getView() != null) {
            return mPluginToast.getView();
        }

        View toastView;
        if (mToastStyleEnabled) {
            toastView = mLayoutInflater.inflate(
                    com.android.systemui.R.layout.text_toast, null);
            ((TextView) toastView.findViewById(com.android.systemui.R.id.text)).setText(mText);

            ((ImageView) toastView.findViewById(com.android.systemui.R.id.icon))
                    .setImageDrawable(getBadgedIcon(mContext, mPackageName, mUserId));
        } else {
            toastView = ToastPresenter.getTextToastView(mContext, mText);
        }

        return toastView;
    }

    /**
     * Called on orientation changes to update parameters associated with the toast placement.
     */
    public void onOrientationChange(int orientation) {
        if (mPluginToast != null) {
            mPluginToast.onOrientationChange(orientation);
        }

        mDefaultY = mContext.getResources().getDimensionPixelSize(
                mToastStyleEnabled
                        ? com.android.systemui.R.dimen.toast_y_offset
                        : R.dimen.toast_y_offset);
        mDefaultGravity =
                mContext.getResources().getInteger(R.integer.config_toastDefaultGravity);
    }

    private Animator createInAnimator() {
        if (isPluginToast() && mPluginToast.getInAnimation() != null) {
            return mPluginToast.getInAnimation();
        }

        return mToastStyleEnabled
                ? ToastDefaultAnimation.Companion.toastIn(getView())
                : null;
    }

    private Animator createOutAnimator() {
        if (isPluginToast() && mPluginToast.getOutAnimation() != null) {
            return mPluginToast.getOutAnimation();
        }
        return mToastStyleEnabled
                ? ToastDefaultAnimation.Companion.toastOut(getView())
                : null;
    }

    /**
     * Get badged app icon if necessary, similar as used in the Settings UI.
     * @return The icon to use
     */
    public static Drawable getBadgedIcon(@NonNull Context context, String packageName,
            int userId) {
        final PackageManager packageManager = context.getPackageManager();
        try {
            final ApplicationInfo appInfo = packageManager.getApplicationInfoAsUser(
                    packageName, PackageManager.GET_META_DATA, userId);
            UserHandle user = UserHandle.getUserHandleForUid(appInfo.uid);
            IconFactory iconFactory = IconFactory.obtain(context);
            Bitmap iconBmp = iconFactory.createBadgedIconBitmap(
                    appInfo.loadUnbadgedIcon(packageManager), user, false).icon;
            return new BitmapDrawable(context.getResources(), iconBmp);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e("SystemUIToast", "could not load icon for package=" + packageName + " e=" + e);
            return null;
        }
    }
}
