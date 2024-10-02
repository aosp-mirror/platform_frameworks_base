/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.NonNull;

import com.android.internal.protolog.common.IProtoLogGroup;

public class ProtoLogGroup implements IProtoLogGroup {

    /** The name should be unique across the codebase. */
    @NonNull
    private final String mName;
    @NonNull
    private final String mTag;
    private final boolean mEnabled;
    private boolean mLogToProto;
    private boolean mLogToLogcat;

    public ProtoLogGroup(@NonNull String name) {
        this(name, name);
    }

    public ProtoLogGroup(@NonNull String name, @NonNull String tag) {
        this(name, tag, true);
    }

    public ProtoLogGroup(@NonNull String name, @NonNull String tag, boolean enabled) {
        mName = name;
        mTag = tag;
        mEnabled = enabled;
        mLogToProto = enabled;
        mLogToLogcat = enabled;
    }

    @Override
    public boolean isEnabled() {
        return mEnabled;
    }

    @Deprecated
    @Override
    public boolean isLogToProto() {
        return mLogToProto;
    }

    @Override
    public boolean isLogToLogcat() {
        return mLogToLogcat;
    }

    @Override
    @NonNull
    public String getTag() {
        return mTag;
    }

    @Deprecated
    @Override
    public void setLogToProto(boolean logToProto) {
        mLogToProto = logToProto;
    }

    @Override
    public void setLogToLogcat(boolean logToLogcat) {
        mLogToLogcat = logToLogcat;
    }

    @Override
    @NonNull
    public String name() {
        return mName;
    }

    @Override
    public int getId() {
        return mName.hashCode();
    }
}
