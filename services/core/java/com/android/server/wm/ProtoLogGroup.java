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

package com.android.server.wm;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.protolog.common.IProtoLogGroup;
import com.android.server.protolog.common.ProtoLog;

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
    WM_DEBUG_FOCUS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false, Consts.TAG_WM),
    WM_DEBUG_STARTING_WINDOW(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_SHOW_TRANSACTIONS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_SHOW_SURFACE_ALLOC(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
    WM_DEBUG_APP_TRANSITIONS(Consts.ENABLE_DEBUG, Consts.ENABLE_LOG_TO_PROTO_DEBUG, false,
            Consts.TAG_WM),
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
    TEST_GROUP(true, true, false, "WindowManagetProtoLogTest");

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

    /**
     * Test function for automated integration tests. Can be also called manually from adb shell.
     */
    @VisibleForTesting
    public static void testProtoLog() {
        ProtoLog.e(ProtoLogGroup.TEST_GROUP,
                "Test completed successfully: %b %d %o %x %e %g %f %% %s.",
                true, 1, 2, 3, 0.4, 0.5, 0.6, "ok");
    }

    private static class Consts {
        private static final String TAG_WM = "WindowManager";

        private static final boolean ENABLE_DEBUG = true;
        private static final boolean ENABLE_LOG_TO_PROTO_DEBUG = true;
    }
}
