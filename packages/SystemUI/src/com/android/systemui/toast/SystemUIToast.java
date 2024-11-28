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

import static android.content.pm.ApplicationInfo.FLAG_SYSTEM;
import static android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;

import android.animation.Animator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.UserHandle;
import android.util.IconDrawableFactory;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.systemui.plugins.ToastPlugin;

/**
 * SystemUI TextToast that can be customized by ToastPlugins. Should never instantiate this class
 * directly. Instead, use {@link ToastFactory#createToast}.
 */
public class SystemUIToast implements ToastPlugin.Toast {
    static final String TAG = "SystemUIToast";
    final Context mContext;
    final Context mDisplayContext;
    final CharSequence mText;
    final ToastPlugin.Toast mPluginToast;

    private final String mPackageName;
    @UserIdInt private final int mUserId;
    private final LayoutInflater mLayoutInflater;

    final int mDefaultX = 0;
    final int mDefaultHorizontalMargin = 0;
    final int mDefaultVerticalMargin = 0;

    private int mDefaultY;
    private int mDefaultGravity;

    @NonNull private final View mToastView;
    @Nullable private final Animator mInAnimator;
    @Nullable private final Animator mOutAnimator;

    SystemUIToast(LayoutInflater layoutInflater, Context applicationContext, Context displayContext,
            CharSequence text, String packageName, int userId, int orientation) {
        this(layoutInflater, applicationContext, displayContext, text, null, packageName, userId,
                orientation);
    }

    SystemUIToast(LayoutInflater layoutInflater, Context applicationContext, Context displayContext,
            CharSequence text, ToastPlugin.Toast pluginToast, String packageName,
            @UserIdInt int userId, int orientation) {
        mLayoutInflater = layoutInflater;
        mContext = applicationContext;
        mDisplayContext = displayContext;
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

        final View toastView = mLayoutInflater.inflate(
                    com.android.systemui.res.R.layout.text_toast, null);
        final TextView textView = toastView.findViewById(com.android.systemui.res.R.id.text);
        final ImageView iconView = toastView.findViewById(com.android.systemui.res.R.id.icon);
        textView.setText(mText);

        ApplicationInfo appInfo = null;
        try {
            appInfo = mContext.getPackageManager()
                    .getApplicationInfoAsUser(mPackageName, 0, mUserId);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Package name not found package=" + mPackageName
                    + " user=" + mUserId);
        }

        if (appInfo != null && appInfo.targetSdkVersion < Build.VERSION_CODES.S) {
            // no two-line limit
            textView.setMaxLines(Integer.MAX_VALUE);

            // no app icon
            toastView.findViewById(com.android.systemui.res.R.id.icon).setVisibility(View.GONE);
        } else {
            Drawable icon = getBadgedIcon(mContext, mPackageName, mUserId);
            if (icon == null) {
                iconView.setVisibility(View.GONE);
            } else {
                iconView.setImageDrawable(icon);
                if (appInfo == null) {
                    Log.d(TAG, "No appInfo for pkg=" + mPackageName + " usr=" + mUserId);
                } else if (appInfo.labelRes != 0) {
                    try {
                        Resources res = mContext.getPackageManager().getResourcesForApplication(
                                appInfo,
                                new Configuration(mContext.getResources().getConfiguration()));
                        iconView.setContentDescription(res.getString(appInfo.labelRes));
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.d(TAG, "Cannot find application resources for icon label.");
                    }
                }
            }
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

        mDefaultY = mDisplayContext.getResources().getDimensionPixelSize(R.dimen.toast_y_offset);
        mDefaultGravity =
                mDisplayContext.getResources().getInteger(R.integer.config_toastDefaultGravity);
    }

    private Animator createInAnimator() {
        if (isPluginToast() && mPluginToast.getInAnimation() != null) {
            return mPluginToast.getInAnimation();
        }

        return ToastDefaultAnimation.Companion.toastIn(getView());
    }

    private Animator createOutAnimator() {
        if (isPluginToast() && mPluginToast.getOutAnimation() != null) {
            return mPluginToast.getOutAnimation();
        }
        return ToastDefaultAnimation.Companion.toastOut(getView());
    }

    /**
     * Get badged app icon if necessary, similar as used in the Settings UI.
     * @return The icon to use
     */
    public static Drawable getBadgedIcon(@NonNull Context context, String packageName,
            int userId) {
        if (!(context.getApplicationContext() instanceof Application)) {
            return null;
        }

        try {
            final PackageManager packageManager = context.getPackageManager();
            final ApplicationInfo appInfo = packageManager.getApplicationInfoAsUser(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA),
                    userId);
            if (appInfo == null || !showApplicationIcon(appInfo, packageManager)) {
                return null;
            }

            IconDrawableFactory iconFactory = IconDrawableFactory.newInstance(context);
            return iconFactory.getBadgedIcon(appInfo, UserHandle.getUserId(appInfo.uid));
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Couldn't find application info for packageName=" + packageName
                    + " userId=" + userId);
            return null;
        }
    }

    private static boolean showApplicationIcon(ApplicationInfo appInfo,
            PackageManager packageManager) {
        if (hasFlag(appInfo.flags, FLAG_UPDATED_SYSTEM_APP | FLAG_SYSTEM)) {
            return packageManager.getLaunchIntentForPackage(appInfo.packageName) != null;
        }
        return true;
    }

    private static boolean hasFlag(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
