/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.assist;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ConfigurationController;

/**
 * AssistOrbController controls the showing and hiding of the assistant orb.
 */
public class AssistOrbController {
    private static final String ASSIST_ICON_METADATA_NAME =
            "com.android.systemui.action_assist_icon";
    private static final String TAG = "AssistOrbController";
    private static final boolean VERBOSE = false;

    private final InterestingConfigChanges mInterestingConfigChanges;
    private AssistOrbContainer mView;
    private final Context mContext;
    private final WindowManager mWindowManager;

    private Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            mView.removeCallbacks(this);
            mView.show(false /* show */, true /* animate */, () -> {
                if (mView.isAttachedToWindow()) {
                    mWindowManager.removeView(mView);
                }
            });
        }
    };

    private ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onConfigChanged(Configuration newConfig) {
                    if (!mInterestingConfigChanges.applyNewConfig(mContext.getResources())) {
                        return;
                    }
                    boolean visible = false;
                    if (mView != null) {
                        visible = mView.isShowing();
                        if (mView.isAttachedToWindow()) {
                            mWindowManager.removeView(mView);
                        }
                    }

                    if (visible) {
                        showOrb(false);
                    }
                }
            };

    AssistOrbController(ConfigurationController configurationController, Context context) {
        mContext = context;
        mWindowManager =  mContext.getSystemService(WindowManager.class);
        mInterestingConfigChanges = new InterestingConfigChanges(ActivityInfo.CONFIG_ORIENTATION
                | ActivityInfo.CONFIG_LOCALE | ActivityInfo.CONFIG_UI_MODE
                | ActivityInfo.CONFIG_SCREEN_LAYOUT | ActivityInfo.CONFIG_ASSETS_PATHS);

        configurationController.addCallback(mConfigurationListener);
        mConfigurationListener.onConfigChanged(context.getResources().getConfiguration());
    }

    public void postHide() {
        mView.post(mHideRunnable);
    }

    public void postHideDelayed(long delayMs) {
        mView.postDelayed(mHideRunnable, delayMs);
    }

    private void showOrb(boolean animated) {
        if (mView == null) {
            mView = (AssistOrbContainer) LayoutInflater.from(mContext).inflate(
                    R.layout.assist_orb, null);
            mView.setVisibility(View.GONE);
            mView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (!mView.isAttachedToWindow()) {
            WindowManager.LayoutParams params = getLayoutParams();
            mWindowManager.addView(mView, params);
        }
        mView.show(true, animated, null);
    }

    private WindowManager.LayoutParams getLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                mContext.getResources().getDimensionPixelSize(R.dimen.assist_orb_scrim_height),
                WindowManager.LayoutParams.TYPE_VOICE_INTERACTION_STARTING,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.token = new Binder();
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setTitle("AssistPreviewPanel");
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        return lp;
    }

    public void showOrb(@NonNull ComponentName assistComponent, boolean isService) {
        showOrb(true);
        maybeSwapSearchIcon(assistComponent, isService);
    }

    private void maybeSwapSearchIcon(@NonNull ComponentName assistComponent, boolean isService) {
        replaceDrawable(mView.getOrb().getLogo(), assistComponent, ASSIST_ICON_METADATA_NAME,
                isService);
    }

    public void replaceDrawable(ImageView v, ComponentName component, String name,
            boolean isService) {
        if (component != null) {
            try {
                PackageManager packageManager = mContext.getPackageManager();
                // Look for the search icon specified in the activity meta-data
                Bundle metaData = isService
                        ? packageManager.getServiceInfo(
                        component, PackageManager.GET_META_DATA).metaData
                        : packageManager.getActivityInfo(
                                component, PackageManager.GET_META_DATA).metaData;
                if (metaData != null) {
                    int iconResId = metaData.getInt(name);
                    if (iconResId != 0) {
                        Resources res = packageManager.getResourcesForApplication(
                                component.getPackageName());
                        v.setImageDrawable(res.getDrawable(iconResId));
                        return;
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                if (VERBOSE) {
                    Log.v(TAG, "Assistant component "
                            + component.flattenToShortString() + " not found");
                }
            } catch (Resources.NotFoundException nfe) {
                Log.w(TAG, "Failed to swap drawable from "
                        + component.flattenToShortString(), nfe);
            }
        }
        v.setImageDrawable(null);
    }
}
