/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.inputmethod;

import com.android.internal.protolog.common.IProtoLogGroup;

import java.util.UUID;

public enum ImeProtoLogGroup implements IProtoLogGroup {
    // TODO(b/393561240): add info/warn/error log level and replace in IMMS
    IMMS_DEBUG(Consts.ENABLE_DEBUG, false, false,
            InputMethodManagerService.TAG);

    private final boolean mEnabled;
    private volatile boolean mLogToProto;
    private volatile boolean mLogToLogcat;
    private final String mTag;

    ImeProtoLogGroup(boolean enabled, boolean logToProto, boolean logToLogcat, String tag) {
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
    public String getTag() {
        return mTag;
    }

    @Override
    public int getId() {
        return Consts.START_ID + ordinal();
    }

    @Override
    public void setLogToProto(boolean logToProto) {
        mLogToProto = logToProto;
    }

    @Override
    public void setLogToLogcat(boolean logToLogcat) {
        mLogToLogcat = logToLogcat;
    }

    private static class Consts {
        private static final boolean ENABLE_DEBUG = true;
        private static final int START_ID = (int) (
                UUID.nameUUIDFromBytes(ImeProtoLogGroup.class.getName().getBytes())
                        .getMostSignificantBits() % Integer.MAX_VALUE);
    }
}
