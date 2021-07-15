/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.pm;

public final class DumpState {
    public static final int DUMP_LIBS = 1 << 0;
    public static final int DUMP_FEATURES = 1 << 1;
    public static final int DUMP_ACTIVITY_RESOLVERS = 1 << 2;
    public static final int DUMP_SERVICE_RESOLVERS = 1 << 3;
    public static final int DUMP_RECEIVER_RESOLVERS = 1 << 4;
    public static final int DUMP_CONTENT_RESOLVERS = 1 << 5;
    public static final int DUMP_PERMISSIONS = 1 << 6;
    public static final int DUMP_PACKAGES = 1 << 7;
    public static final int DUMP_SHARED_USERS = 1 << 8;
    public static final int DUMP_MESSAGES = 1 << 9;
    public static final int DUMP_PROVIDERS = 1 << 10;
    public static final int DUMP_VERIFIERS = 1 << 11;
    public static final int DUMP_PREFERRED = 1 << 12;
    public static final int DUMP_PREFERRED_XML = 1 << 13;
    public static final int DUMP_KEYSETS = 1 << 14;
    public static final int DUMP_VERSION = 1 << 15;
    public static final int DUMP_INSTALLS = 1 << 16;
    public static final int DUMP_DOMAIN_VERIFIER = 1 << 17;
    public static final int DUMP_DOMAIN_PREFERRED = 1 << 18;
    public static final int DUMP_FROZEN = 1 << 19;
    public static final int DUMP_DEXOPT = 1 << 20;
    public static final int DUMP_COMPILER_STATS = 1 << 21;
    public static final int DUMP_CHANGES = 1 << 22;
    public static final int DUMP_VOLUMES = 1 << 23;
    public static final int DUMP_SERVICE_PERMISSIONS = 1 << 24;
    public static final int DUMP_APEX = 1 << 25;
    public static final int DUMP_QUERIES = 1 << 26;
    public static final int DUMP_KNOWN_PACKAGES = 1 << 27;
    public static final int DUMP_PER_UID_READ_TIMEOUTS = 1 << 28;
    public static final int DUMP_SNAPSHOT_STATISTICS = 1 << 29;

    public static final int OPTION_SHOW_FILTERS = 1 << 0;
    public static final int OPTION_DUMP_ALL_COMPONENTS = 1 << 1;
    public static final int OPTION_SKIP_PERMISSIONS = 1 << 2;

    private int mTypes;

    private int mOptions;

    private boolean mTitlePrinted;
    private boolean mFullPreferred;
    private boolean mCheckIn;
    private boolean mBrief;

    private String mTargetPackageName;

    private SharedUserSetting mSharedUser;

    public boolean isDumping(int type) {
        if (mTypes == 0 && type != DUMP_PREFERRED_XML) {
            return true;
        }

        return (mTypes & type) != 0;
    }

    public void setDump(int type) {
        mTypes |= type;
    }

    public boolean isOptionEnabled(int option) {
        return (mOptions & option) != 0;
    }

    public void setOptionEnabled(int option) {
        mOptions |= option;
    }

    public boolean onTitlePrinted() {
        final boolean printed = mTitlePrinted;
        mTitlePrinted = true;
        return printed;
    }

    public boolean getTitlePrinted() {
        return mTitlePrinted;
    }

    public void setTitlePrinted(boolean enabled) {
        mTitlePrinted = enabled;
    }

    public SharedUserSetting getSharedUser() {
        return mSharedUser;
    }

    public void setSharedUser(SharedUserSetting user) {
        mSharedUser = user;
    }

    public String getTargetPackageName() {
        return mTargetPackageName;
    }

    public void setTargetPackageName(String packageName) {
        mTargetPackageName = packageName;
    }

    public boolean isFullPreferred() {
        return mFullPreferred;
    }

    public void setFullPreferred(boolean fullPreferred) {
        mFullPreferred = fullPreferred;
    }

    public boolean isCheckIn() {
        return mCheckIn;
    }

    public void setCheckIn(boolean checkIn) {
        mCheckIn = checkIn;
    }

    public boolean isBrief() {
        return mBrief;
    }

    public void setBrief(boolean brief) {
        mBrief = brief;
    }
}
