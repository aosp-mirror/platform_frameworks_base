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

package com.android.systemui.statusbar.tv.micdisclosure;

import static com.android.systemui.statusbar.tv.micdisclosure.AudioRecordingDisclosureBar.DEBUG;

import android.annotation.UiThread;
import android.app.AppOpsManager;
import android.content.Context;
import android.util.ArraySet;
import android.util.Log;

import java.util.Set;

/**
 * The purpose of these class is to detect packages that are conducting audio recording (according
 * to {@link AppOpsManager}) and report this to {@link AudioRecordingDisclosureBar}.
 */
class RecordAudioAppOpObserver extends AudioActivityObserver implements
        AppOpsManager.OnOpActiveChangedListener {
    private static final String TAG = "RecordAudioAppOpObserver";

    /**
     * Set of the applications that currently are conducting audio recording according to {@link
     * AppOpsManager}.
     */
    private final Set<String> mActiveAudioRecordingPackages = new ArraySet<>();

    RecordAudioAppOpObserver(Context context, OnAudioActivityStateChangeListener listener) {
        super(context, listener);

        // Register AppOpsManager callback
        final AppOpsManager appOpsManager = (AppOpsManager) mContext.getSystemService(
                Context.APP_OPS_SERVICE);
        appOpsManager.startWatchingActive(
                new String[]{AppOpsManager.OPSTR_RECORD_AUDIO},
                mContext.getMainExecutor(),
                this);
    }

    @UiThread
    @Override
    Set<String> getActivePackages() {
        return mActiveAudioRecordingPackages;
    }

    @UiThread
    @Override
    public void onOpActiveChanged(String op, int uid, String packageName, boolean active) {
        if (DEBUG) {
            Log.d(TAG,
                    "OP_RECORD_AUDIO active change, active=" + active + ", package="
                            + packageName);
        }

        if (active) {
            if (mActiveAudioRecordingPackages.add(packageName)) {
                mListener.onAudioActivityStateChange(true, packageName);
            }
        } else {
            if (mActiveAudioRecordingPackages.remove(packageName)) {
                mListener.onAudioActivityStateChange(false, packageName);
            }
        }
    }
}
