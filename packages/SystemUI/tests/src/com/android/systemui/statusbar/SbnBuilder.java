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

package com.android.systemui.statusbar;

import android.app.Notification;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

/**
 * Convenience builder for {@link StatusBarNotification} since its constructor is terrifying.
 *
 * Only for use in tests.
 */
public class SbnBuilder {
    private String mPkg = "test_pkg";
    private String mOpPkg;
    private int mId;
    private String mTag;
    private int mUid;
    private int mInitialPid;
    private Notification mNotification = new Notification();
    private UserHandle mUser = UserHandle.of(0);
    private String mOverrideGroupKey;
    private long mPostTime;

    public StatusBarNotification build() {
        return new StatusBarNotification(
                mPkg,
                mOpPkg,
                mId,
                mTag,
                mUid,
                mInitialPid,
                mNotification,
                mUser,
                mOverrideGroupKey,
                mPostTime);
    }

    public SbnBuilder setPkg(String pkg) {
        mPkg = pkg;
        return this;
    }

    public SbnBuilder setOpPkg(String opPkg) {
        mOpPkg = opPkg;
        return this;
    }

    public SbnBuilder setId(int id) {
        mId = id;
        return this;
    }

    public SbnBuilder setTag(String tag) {
        mTag = tag;
        return this;
    }

    public SbnBuilder setUid(int uid) {
        mUid = uid;
        return this;
    }

    public SbnBuilder setInitialPid(int initialPid) {
        mInitialPid = initialPid;
        return this;
    }

    public SbnBuilder setNotification(Notification notification) {
        mNotification = notification;
        return this;
    }

    public SbnBuilder setUser(UserHandle user) {
        mUser = user;
        return this;
    }

    public SbnBuilder setOverrideGroupKey(String overrideGroupKey) {
        mOverrideGroupKey = overrideGroupKey;
        return this;
    }

    public SbnBuilder setPostTime(long postTime) {
        mPostTime = postTime;
        return this;
    }
}
