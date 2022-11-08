/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.pip.tv;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Handler;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.TvWindowMenuActionButton;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

abstract class TvPipAction {

    private static final String TAG = TvPipAction.class.getSimpleName();

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ACTION_"}, value = {
            ACTION_FULLSCREEN,
            ACTION_CLOSE,
            ACTION_MOVE,
            ACTION_EXPAND_COLLAPSE,
            ACTION_CUSTOM,
            ACTION_CUSTOM_CLOSE
    })
    public @interface ActionType {
    }

    public static final int ACTION_FULLSCREEN = 0;
    public static final int ACTION_CLOSE = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_EXPAND_COLLAPSE = 3;
    public static final int ACTION_CUSTOM = 4;
    public static final int ACTION_CUSTOM_CLOSE = 5;

    @ActionType
    private final int mActionType;

    TvPipAction(@ActionType int actionType) {
        mActionType = actionType;
    }

    boolean isCloseAction() {
        return mActionType == ACTION_CLOSE || mActionType == ACTION_CUSTOM_CLOSE;
    }

    @ActionType
    int getActionType() {
        return mActionType;
    }

    abstract void populateButton(@NonNull TvWindowMenuActionButton button, Handler mainHandler);

    abstract PendingIntent getPendingIntent();

    void executePendingIntent() {
        if (getPendingIntent() == null) return;
        try {
            getPendingIntent().send();
        } catch (PendingIntent.CanceledException e) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Failed to send action, %s", TAG, e);
        }
    }

    abstract Notification.Action toNotificationAction(Context context);

}
