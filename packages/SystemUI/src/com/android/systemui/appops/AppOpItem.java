/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.appops;

/**
 * Item to store information of active applications using different APP OPS
 */
public class AppOpItem {

    private int mCode;
    private int mUid;
    private String mPackageName;
    private long mTimeStartedElapsed;
    private StringBuilder mState;
    private boolean mIsDisabled;

    public AppOpItem(int code, int uid, String packageName, long timeStartedElapsed) {
        this.mCode = code;
        this.mUid = uid;
        this.mPackageName = packageName;
        this.mTimeStartedElapsed = timeStartedElapsed;
        mState = new StringBuilder()
                .append("AppOpItem(")
                .append("Op code=").append(code).append(", ")
                .append("UID=").append(uid).append(", ")
                .append("Package name=").append(packageName).append(", ")
                .append("Paused=");
    }

    public int getCode() {
        return mCode;
    }

    public int getUid() {
        return mUid;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public long getTimeStartedElapsed() {
        return mTimeStartedElapsed;
    }

    public void setDisabled(boolean misDisabled) {
        this.mIsDisabled = misDisabled;
    }

    public boolean isDisabled() {
        return mIsDisabled;
    }

    @Override
    public String toString() {
        return mState.append(mIsDisabled).append(")").toString();
    }
}
