/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.pip;

import static android.content.pm.PackageManager.FEATURE_LEANBACK_ONLY;
import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;

import android.content.pm.PackageManager;
import android.content.res.Configuration;

import com.android.systemui.SystemUI;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.statusbar.CommandQueue;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Controls the picture-in-picture window.
 */
public class PipUI extends SystemUI implements CommandQueue.Callbacks {

    private BasePipManager mPipManager;

    private boolean mSupportsPip;

    @Override
    public void start() {
        PackageManager pm = mContext.getPackageManager();
        mSupportsPip = pm.hasSystemFeature(FEATURE_PICTURE_IN_PICTURE);
        if (!mSupportsPip) {
            return;
        }

        // Ensure that we are the primary user's SystemUI.
        final int processUser = SystemServicesProxy.getInstance(mContext).getProcessUser();
        if (!SystemServicesProxy.getInstance(mContext).isSystemUser(processUser)) {
            throw new IllegalStateException("Non-primary Pip component not currently supported.");
        }

        mPipManager = pm.hasSystemFeature(FEATURE_LEANBACK_ONLY)
                ? com.android.systemui.pip.tv.PipManager.getInstance()
                : com.android.systemui.pip.phone.PipManager.getInstance();
        mPipManager.initialize(mContext);

        getComponent(CommandQueue.class).addCallbacks(this);
    }

    @Override
    public void showPictureInPictureMenu() {
        mPipManager.showPictureInPictureMenu();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mPipManager == null) {
            return;
        }

        mPipManager.onConfigurationChanged(newConfig);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mPipManager == null) {
            return;
        }

        mPipManager.dump(pw);
    }
}
