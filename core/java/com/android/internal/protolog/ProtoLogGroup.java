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

package com.android.internal.protolog;

import com.android.internal.protolog.common.IProtoLogGroup;

import java.util.UUID;

/**
 * Defines logging groups for ProtoLog.
 *
 * This file is used by the ProtoLogTool to generate optimized logging code. All of its dependencies
 * must be included in services.core.wm.protologgroups build target.
 */
public enum ProtoLogGroup implements IProtoLogGroup {
    WM_ERROR(true, true, true, Consts.TAG_WM),
    WM_DEBUG_ORIENTATION(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_FOCUS_LIGHT(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_BOOT(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_RESIZE(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_ADD_REMOVE(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_CONFIGURATION(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_SWITCH(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_CONTAINERS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_FOCUS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_IMMERSIVE(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_LOCKTASK(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_STATES(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_TASKS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_STARTING_WINDOW(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_SHOW_TRANSACTIONS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_SHOW_SURFACE_ALLOC(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_APP_TRANSITIONS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_ANIM(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false, Consts.TAG_WM),
    WM_DEBUG_APP_TRANSITIONS_ANIM(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_RECENTS_ANIMATIONS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_DRAW(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false, Consts.TAG_WM),
    WM_DEBUG_REMOTE_ANIMATIONS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_SCREEN_ON(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false, Consts.TAG_WM),
    WM_DEBUG_KEEP_SCREEN_ON(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_WINDOW_MOVEMENT(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_IME(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_WINDOW_ORGANIZER(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_SYNC_ENGINE(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_WINDOW_TRANSITIONS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_WINDOW_TRANSITIONS_MIN(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, true,
            Consts.TAG_WM),
    WM_DEBUG_WINDOW_INSETS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_CONTENT_RECORDING(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, true,
            Consts.TAG_WM),
    WM_DEBUG_WALLPAPER(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false, Consts.TAG_WM),
    WM_DEBUG_BACK_PREVIEW(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, true,
            "CoreBackPreview"),
    WM_DEBUG_DREAM(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, true, Consts.TAG_WM),

    WM_DEBUG_DIMMER(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false, Consts.TAG_WM),
    WM_DEBUG_TPL(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false, Consts.TAG_WM),
    WM_DEBUG_EMBEDDED_WINDOWS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    TEST_GROUP(true, true, false, "WindowManagerProtoLogTest");

    private final boolean mEnabled;
    private volatile boolean mLogToProto;
    private volatile boolean mLogToLogcat;
    private final String mTag;

    /**
     * @param enabled     set to false to exclude all log statements for this group from
     *                    compilation,
     *                    they will not be available in runtime.
     * @param logToProto  enable binary logging for the group
     * @param logToLogcat enable text logging for the group
     * @param tag         name of the source of the logged message
     */
    ProtoLogGroup(boolean enabled, boolean logToProto, boolean logToLogcat, String tag) {
        this.mEnabled = enabled;
        this.mLogToProto = logToProto;
        this.mLogToLogcat = logToLogcat;
        this.mTag = tag;
    }

    @Override
    public boolean isEnabled() {
        return mEnabled;
    }

    @Override
    public boolean isLogToProto() {
        return mLogToProto;
    }

    @Override
    public boolean isLogToLogcat() {
        return mLogToLogcat;
    }

    @Override
    public boolean isLogToAny() {
        return mLogToLogcat || mLogToProto;
    }

    @Override
    public String getTag() {
        return mTag;
    }

    @Override
    public void setLogToProto(boolean logToProto) {
        this.mLogToProto = logToProto;
    }

    @Override
    public void setLogToLogcat(boolean logToLogcat) {
        this.mLogToLogcat = logToLogcat;
    }

    @Override
    public int getId() {
        return Consts.START_ID + this.ordinal();
    }

    private static class Consts {
        private static final String TAG_WM = "WindowManager";

        private static final boolean ENABLE_DEBUG = true;
        private static final boolean ENABLE_LOG_TO_PROTO_DEBUG = true;
        private static final int START_ID = (int) (
                UUID.nameUUIDFromBytes(ProtoLogGroup.class.getName().getBytes())
                        .getMostSignificantBits() % Integer.MAX_VALUE);
    }
}
