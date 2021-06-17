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

package com.android.wm.shell.pip;

import android.app.TaskInfo;
import android.content.pm.PackageManager;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

/**
 * Helper class that ends PiP log to UiEvent, see also go/uievent
 */
public class PipUiEventLogger {

    private static final int INVALID_PACKAGE_UID = -1;

    private final UiEventLogger mUiEventLogger;
    private final PackageManager mPackageManager;

    private String mPackageName;
    private int mPackageUid = INVALID_PACKAGE_UID;

    public PipUiEventLogger(UiEventLogger uiEventLogger, PackageManager packageManager) {
        mUiEventLogger = uiEventLogger;
        mPackageManager = packageManager;
    }

    public void setTaskInfo(TaskInfo taskInfo) {
        if (taskInfo != null && taskInfo.topActivity != null) {
            mPackageName = taskInfo.topActivity.getPackageName();
            mPackageUid = getUid(mPackageName, taskInfo.userId);
        } else {
            mPackageName = null;
            mPackageUid = INVALID_PACKAGE_UID;
        }
    }

    /**
     * Sends log via UiEvent, reference go/uievent for how to debug locally
     */
    public void log(PipUiEventEnum event) {
        if (mPackageName == null || mPackageUid == INVALID_PACKAGE_UID) {
            return;
        }
        mUiEventLogger.log(event, mPackageUid, mPackageName);
    }

    private int getUid(String packageName, int userId) {
        int uid = INVALID_PACKAGE_UID;
        try {
            uid = mPackageManager.getApplicationInfoAsUser(
                    packageName, 0 /* ApplicationInfoFlags */, userId).uid;
        } catch (PackageManager.NameNotFoundException e) {
            // do nothing.
        }
        return uid;
    }

    /**
     * Enums for logging the PiP events to UiEvent
     */
    public enum PipUiEventEnum implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Activity enters picture-in-picture mode")
        PICTURE_IN_PICTURE_ENTER(603),

        @UiEvent(doc = "Expands from picture-in-picture to fullscreen")
        PICTURE_IN_PICTURE_EXPAND_TO_FULLSCREEN(604),

        @UiEvent(doc = "Removes picture-in-picture by tap close button")
        PICTURE_IN_PICTURE_TAP_TO_REMOVE(605),

        @UiEvent(doc = "Removes picture-in-picture by drag to dismiss area")
        PICTURE_IN_PICTURE_DRAG_TO_REMOVE(606),

        @UiEvent(doc = "Shows picture-in-picture menu")
        PICTURE_IN_PICTURE_SHOW_MENU(607),

        @UiEvent(doc = "Hides picture-in-picture menu")
        PICTURE_IN_PICTURE_HIDE_MENU(608),

        @UiEvent(doc = "Changes the aspect ratio of picture-in-picture window. This is inherited"
                + " from previous Tron-based logging and currently not in use.")
        PICTURE_IN_PICTURE_CHANGE_ASPECT_RATIO(609),

        @UiEvent(doc = "User resize of the picture-in-picture window")
        PICTURE_IN_PICTURE_RESIZE(610),

        @UiEvent(doc = "User unstashed picture-in-picture")
        PICTURE_IN_PICTURE_STASH_UNSTASHED(709),

        @UiEvent(doc = "User stashed picture-in-picture to the left side")
        PICTURE_IN_PICTURE_STASH_LEFT(710),

        @UiEvent(doc = "User stashed picture-in-picture to the right side")
        PICTURE_IN_PICTURE_STASH_RIGHT(711);

        private final int mId;

        PipUiEventEnum(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }
}
