/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.tv;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class AudioRecordingDisclosureBar {
    private static final String TAG = "AudioRecordingDisclosureBar";
    private static final boolean DEBUG = false;

    private static final String LAYOUT_PARAMS_TITLE = "AudioRecordingDisclosureBar";
    private static final int ANIM_DURATION_MS = 150;

    private final Context mContext;
    private final List<String> mAudioRecordingApps = new ArrayList<>();
    private View mView;
    private ViewGroup mAppsInfoContainer;

    AudioRecordingDisclosureBar(Context context) {
        mContext = context;
    }

    void start() {
        // Inflate and add audio recording disclosure bar
        createView();

        // Register AppOpsManager callback
        final AppOpsManager appOpsManager = (AppOpsManager) mContext.getSystemService(
                Context.APP_OPS_SERVICE);
        appOpsManager.startWatchingActive(
                new String[]{AppOpsManager.OPSTR_RECORD_AUDIO}, mContext.getMainExecutor(),
                new OnActiveRecordingListener());
    }

    private void createView() {
        mView = View.inflate(mContext,
                R.layout.tv_status_bar_audio_recording, null);
        mAppsInfoContainer = mView.findViewById(R.id.container);

        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                MATCH_PARENT,
                WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.BOTTOM;
        layoutParams.setTitle(LAYOUT_PARAMS_TITLE);
        layoutParams.packageName = mContext.getPackageName();

        final WindowManager windowManager = (WindowManager) mContext.getSystemService(
                Context.WINDOW_SERVICE);
        windowManager.addView(mView, layoutParams);

        // Set invisible first util it gains its actual size and we are able to hide it by moving
        // off the screen
        mView.setVisibility(View.INVISIBLE);
        mView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        // Now that we get the height, we can move the bar off ("below") the screen
                        final int height = mView.getHeight();
                        mView.setTranslationY(height);
                        // ... and make it visible
                        mView.setVisibility(View.VISIBLE);
                        // Remove the observer
                        mView.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                    }
                });
    }

    private void showAudioRecordingDisclosureBar() {
        mView.animate()
                .translationY(0f)
                .setDuration(ANIM_DURATION_MS)
                .start();
    }

    private void addToAudioRecordingDisclosureBar(String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        final ApplicationInfo appInfo;
        try {
            appInfo = pm.getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        final CharSequence label = pm.getApplicationLabel(appInfo);
        final Drawable icon = pm.getApplicationIcon(appInfo);

        final View view = LayoutInflater.from(mContext).inflate(R.layout.tv_item_app_info,
                mAppsInfoContainer, false);
        ((TextView) view.findViewById(R.id.title)).setText(label);
        ((ImageView) view.findViewById(R.id.icon)).setImageDrawable(icon);

        mAppsInfoContainer.addView(view);
    }

    private void removeFromAudioRecordingDisclosureBar(int index) {
        mAppsInfoContainer.removeViewAt(index);
    }

    private void hideAudioRecordingDisclosureBar() {
        mView.animate()
                .translationY(mView.getHeight())
                .setDuration(ANIM_DURATION_MS)
                .start();
    }

    private class OnActiveRecordingListener implements AppOpsManager.OnOpActiveChangedListener {
        private final List<String> mExemptApps;

        private OnActiveRecordingListener() {
            mExemptApps = Arrays.asList(mContext.getResources().getStringArray(
                    R.array.audio_recording_disclosure_exempt_apps));
        }

        @Override
        public void onOpActiveChanged(String op, int uid, String packageName, boolean active) {
            if (DEBUG) {
                Log.d(TAG,
                        "OP_RECORD_AUDIO active change, active" + active + ", app=" + packageName);
            }

            if (mExemptApps.contains(packageName)) {
                if (DEBUG) {
                    Log.d(TAG, "\t- exempt app");
                }
                return;
            }

            final boolean alreadyTracking = mAudioRecordingApps.contains(packageName);
            if ((active && alreadyTracking) || (!active && !alreadyTracking)) {
                if (DEBUG) {
                    Log.d(TAG, "\t- nothing changed");
                }
                return;
            }

            if (active) {
                if (DEBUG) {
                    Log.d(TAG, "\t- new recording app");
                }

                if (mAudioRecordingApps.isEmpty()) {
                    showAudioRecordingDisclosureBar();
                }

                mAudioRecordingApps.add(packageName);
                addToAudioRecordingDisclosureBar(packageName);
            } else {
                if (DEBUG) {
                    Log.d(TAG, "\t- not recording any more");
                }

                final int index = mAudioRecordingApps.indexOf(packageName);
                removeFromAudioRecordingDisclosureBar(index);
                mAudioRecordingApps.remove(index);

                if (mAudioRecordingApps.isEmpty()) {
                    hideAudioRecordingDisclosureBar();
                }
            }
        }
    }
}
