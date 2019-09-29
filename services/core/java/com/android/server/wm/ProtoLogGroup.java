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
    GENERIC_WM(true, true, false, "WindowManager"),

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
}
