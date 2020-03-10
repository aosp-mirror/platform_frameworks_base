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

import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import com.android.internal.logging.InstanceId;

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
    @Nullable private Notification mNotification;
    @Nullable private Notification.Builder mNotificationBuilder;
    private Notification.BubbleMetadata mBubbleMetadata;
    private UserHandle mUser = UserHandle.of(0);
    private String mOverrideGroupKey;
    private long mPostTime;
    private InstanceId mInstanceId;

    public SbnBuilder() {
    }

    public SbnBuilder(StatusBarNotification source) {
        mPkg = source.getPackageName();
        mOpPkg = source.getOpPkg();
        mId = source.getId();
        mTag = source.getTag();
        mUid = source.getUid();
        mInitialPid = source.getInitialPid();
        mNotification = source.getNotification();
        mUser = source.getUser();
        mOverrideGroupKey = source.getOverrideGroupKey();
        mPostTime = source.getPostTime();
        mInstanceId = source.getInstanceId();
    }

    public StatusBarNotification build() {
        Notification notification;
        if (mNotificationBuilder != null) {
            notification = mNotificationBuilder.build();
        } else if (mNotification != null) {
            notification = mNotification;
        } else {
            notification = new Notification();
        }

        if (mBubbleMetadata != null) {
            notification.setBubbleMetadata(mBubbleMetadata);
        }

        StatusBarNotification result = new StatusBarNotification(
                mPkg,
                mOpPkg,
                mId,
                mTag,
                mUid,
                mInitialPid,
                notification,
                mUser,
                mOverrideGroupKey,
                mPostTime);
        if (mInstanceId != null) {
            result.setInstanceId(mInstanceId);
        }
        return result;
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
        mNotificationBuilder = null;
        return this;
    }

    public SbnBuilder setContentTitle(Context context, String contentTitle) {
        modifyNotification(context).setContentTitle(contentTitle);
        return this;
    }

    public SbnBuilder setContentText(Context context, String contentText) {
        modifyNotification(context).setContentText(contentText);
        return this;
    }

    public SbnBuilder setGroup(Context context, String groupKey) {
        modifyNotification(context).setGroup(groupKey);
        return this;
    }

    public SbnBuilder setGroupSummary(Context context, boolean isGroupSummary) {
        modifyNotification(context).setGroupSummary(isGroupSummary);
        return this;
    }

    public SbnBuilder setFlag(Context context, int mask, boolean value) {
        modifyNotification(context).setFlag(mask, value);
        return this;
    }

    public Notification.Builder modifyNotification(Context context) {
        if (mNotification != null) {
            mNotificationBuilder = new Notification.Builder(context, mNotification);
            mNotification = null;
        } else if (mNotificationBuilder == null) {
            mNotificationBuilder = new Notification.Builder(context);
        }

        return mNotificationBuilder;
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

    public SbnBuilder setBubbleMetadata(Notification.BubbleMetadata data) {
        mBubbleMetadata = data;
        return this;
    }

    public SbnBuilder setInstanceId(InstanceId instanceId) {
        mInstanceId = instanceId;
        return this;
    }
}
