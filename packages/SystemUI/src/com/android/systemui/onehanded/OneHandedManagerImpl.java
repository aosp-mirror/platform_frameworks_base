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

package com.android.systemui.onehanded;

import android.content.Context;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.model.SysUiState;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages and manipulates the one handed states, transitions, and gesture for phones.
 */
@Singleton
public class OneHandedManagerImpl implements OneHandedManager, Dumpable {
    private static final String TAG = "OneHandedManager";

    private boolean mIsOneHandedEnabled;
    private float mOffSetFraction;
    private OneHandedDisplayAreaOrganizer mDisplayAreaOrganizer;
    private SysUiState mSysUiFlagContainer;

    /**
     * Constructor of OneHandedManager
     */
    @Inject
    public OneHandedManagerImpl(Context context,
            OneHandedDisplayAreaOrganizer displayAreaOrganizer,
            SysUiState sysUiState) {

        mDisplayAreaOrganizer = displayAreaOrganizer;
        mSysUiFlagContainer = sysUiState;
        mOffSetFraction =
                context.getResources().getFraction(R.fraction.config_one_handed_offset, 1, 1);
        mIsOneHandedEnabled = OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                context.getContentResolver());
        updateOneHandedEnabled();
    }

    /**
     * Set one handed enabled or disabled by OneHanded UI when user update settings
     */
    public void setOneHandedEnabled(boolean enabled) {
        mIsOneHandedEnabled = enabled;
        updateOneHandedEnabled();
    }

    /**
     * Start one handed mode
     */
    @Override
    public void startOneHanded() {
    }

    /**
     * Stop one handed mode
     */
    @Override
    public void stopOneHanded() {
    }

    private void updateOneHandedEnabled() {
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        final String innerPrefix = "  ";
        pw.println(TAG + "states: ");
        pw.print(innerPrefix + "mIsOneHandedEnabled=");
        pw.println(mIsOneHandedEnabled);

        if (mDisplayAreaOrganizer != null) {
            mDisplayAreaOrganizer.dump(fd, pw, args);
        }
    }
}
