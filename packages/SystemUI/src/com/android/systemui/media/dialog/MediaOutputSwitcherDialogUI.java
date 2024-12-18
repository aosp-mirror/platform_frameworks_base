/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.media.dialog;

import android.annotation.MainThread;
import android.content.Context;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.CommandQueue;

import javax.inject.Inject;

/** Controls display of media output switcher. */
@SysUISingleton
public class MediaOutputSwitcherDialogUI implements CoreStartable, CommandQueue.Callbacks {

    private static final String TAG = "MediaOutputSwitcherDialogUI";

    private final CommandQueue mCommandQueue;
    private final MediaOutputDialogManager mMediaOutputDialogManager;

    @Inject
    public MediaOutputSwitcherDialogUI(
            Context context,
            CommandQueue commandQueue,
            MediaOutputDialogManager mediaOutputDialogManager) {
        mCommandQueue = commandQueue;
        mMediaOutputDialogManager = mediaOutputDialogManager;
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
    }

    @Override
    @MainThread
    public void showMediaOutputSwitcher(String packageName, UserHandle userHandle) {
        if (!TextUtils.isEmpty(packageName)) {
            mMediaOutputDialogManager.createAndShow(
                    packageName,
                    /* aboveStatusBar= */ false,
                    /* view= */ null,
                    userHandle,
                    /* token */ null);
        } else {
            Log.e(TAG, "Unable to launch media output dialog. Package name is empty.");
        }
    }
}
