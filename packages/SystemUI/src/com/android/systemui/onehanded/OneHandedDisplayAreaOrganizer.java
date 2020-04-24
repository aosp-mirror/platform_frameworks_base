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
import android.os.Handler;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.Dumpable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

import javax.inject.Inject;

/**
 * Manages OneHanded display areas such as offset.
 *
 * This class listens on {@link DisplayAreaOrganizer} callbacks for windowing mode change
 * both to and from OneHanded and issues corresponding animation if applicable.
 * Normally, we apply series of {@link SurfaceControl.Transaction} when the animator is running
 * and files a final {@link WindowContainerTransaction} at the end of the transition.
 *
 * This class is also responsible for translating one handed operations within SysUI component
 */
public class OneHandedDisplayAreaOrganizer extends DisplayAreaOrganizer implements Dumpable {
    private static final String TAG = "OneHandedDisplayAreaOrganizer";

    private final Handler mUpdateHandler;

    private boolean mIsInOneHanded;
    @VisibleForTesting
    DisplayAreaInfo mDisplayAreaInfo;
    private SurfaceControl mLeash;

    @SuppressWarnings("unchecked")
    private final Handler.Callback mUpdateCallback = (msg) -> true;

    /**
     * Constructor of OneHandedDisplayAreaOrganizer
     */
    @Inject
    public OneHandedDisplayAreaOrganizer(Context context) {
        mUpdateHandler = new Handler(OneHandedThread.get().getLooper(), mUpdateCallback);
    }

    @Override
    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        Objects.requireNonNull(displayAreaInfo, "displayAreaInfo must not be null");
        Objects.requireNonNull(leash, "leash must not be null");
        mDisplayAreaInfo = displayAreaInfo;
        mLeash = leash;
    }

    @Override
    public void onDisplayAreaInfoChanged(@NonNull DisplayAreaInfo displayAreaInfo) {
        Objects.requireNonNull(displayAreaInfo, "displayArea must not be null");
        mDisplayAreaInfo = displayAreaInfo;
    }

    @Override
    public void onDisplayAreaVanished(@NonNull DisplayAreaInfo displayAreaInfo) {
        Objects.requireNonNull(displayAreaInfo,
                "Requires valid displayArea, and displayArea must not be null");
        if (displayAreaInfo.token.asBinder() != mDisplayAreaInfo.token.asBinder()) {
            Log.w(TAG, "Unrecognized token: " + displayAreaInfo.token);
            return;
        }
        mDisplayAreaInfo = displayAreaInfo;

        // Stop one handed immediately
    }

    @VisibleForTesting
    Handler getUpdateHandler() {
        return mUpdateHandler;
    }

    /**
     * The latest state of one handed mode
     *
     * @return true Currently is in one handed mode, otherwise is not in one handed mode
     */
    public boolean isInOneHanded() {
        return mIsInOneHanded;
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        final String innerPrefix = "  ";
        pw.println(TAG + "states: ");
        pw.print(innerPrefix + "mIsInOneHanded=");
        pw.println(mIsInOneHanded);
        pw.print(innerPrefix + "mDisplayAreaInfo=");
        pw.println(mDisplayAreaInfo);
        pw.print(innerPrefix + "mLeash=");
        pw.println(mLeash);
    }
}
