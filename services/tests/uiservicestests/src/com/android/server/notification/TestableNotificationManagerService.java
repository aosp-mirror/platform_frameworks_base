/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.notification;

import android.companion.ICompanionDeviceManager;
import android.content.ComponentName;
import android.content.Context;

import androidx.annotation.Nullable;

import com.android.internal.logging.InstanceIdSequence;

import java.util.HashSet;
import java.util.Set;

public class TestableNotificationManagerService extends NotificationManagerService {
    int countSystemChecks = 0;
    boolean isSystemUid = true;
    int countLogSmartSuggestionsVisible = 0;
    Set<Integer> mChannelToastsSent = new HashSet<>();

    String stringArrayResourceValue;
    @Nullable
    NotificationAssistantAccessGrantedCallback mNotificationAssistantAccessGrantedCallback;

    TestableNotificationManagerService(Context context, NotificationRecordLogger logger,
            InstanceIdSequence notificationInstanceIdSequence) {
        super(context, logger, notificationInstanceIdSequence);
    }

    RankingHelper getRankingHelper() {
        return mRankingHelper;
    }

    @Override
    protected boolean isCallingUidSystem() {
        countSystemChecks++;
        return isSystemUid;
    }

    @Override
    protected boolean isCallerSystemOrPhone() {
        countSystemChecks++;
        return isSystemUid;
    }

    @Override
    protected ICompanionDeviceManager getCompanionManager() {
        return null;
    }

    @Override
    protected void reportUserInteraction(NotificationRecord r) {
        return;
    }

    @Override
    protected void handleSavePolicyFile() {
        return;
    }

    @Override
    void logSmartSuggestionsVisible(NotificationRecord r, int notificationLocation) {
        super.logSmartSuggestionsVisible(r, notificationLocation);
        countLogSmartSuggestionsVisible++;
    }

    @Override
    protected void setNotificationAssistantAccessGrantedForUserInternal(
            ComponentName assistant, int userId, boolean granted, boolean userSet) {
        if (mNotificationAssistantAccessGrantedCallback != null) {
            mNotificationAssistantAccessGrantedCallback.onGranted(assistant, userId, granted,
                    userSet);
            return;
        }
        super.setNotificationAssistantAccessGrantedForUserInternal(assistant, userId, granted,
                userSet);
    }

    @Override
    protected String[] getStringArrayResource(int key) {
        return new String[] {stringArrayResourceValue};
    }

    protected void setStringArrayResourceValue(String value) {
        stringArrayResourceValue = value;
    }

    void setNotificationAssistantAccessGrantedCallback(
            @Nullable NotificationAssistantAccessGrantedCallback callback) {
        this.mNotificationAssistantAccessGrantedCallback = callback;
    }

    interface NotificationAssistantAccessGrantedCallback {
        void onGranted(ComponentName assistant, int userId, boolean granted, boolean userSet);
    }

    @Override
    protected void doChannelWarningToast(int uid, CharSequence toastText) {
        mChannelToastsSent.add(uid);
    }

    // Helper method for testing behavior when turning on/off the review permissions notification.
    protected void setShowReviewPermissionsNotification(boolean setting) {
        mShowReviewPermissionsNotification = setting;
    }

    public class StrongAuthTrackerFake extends NotificationManagerService.StrongAuthTracker {
        private int mGetStrongAuthForUserReturnValue = 0;
        StrongAuthTrackerFake(Context context) {
            super(context);
        }

        public void setGetStrongAuthForUserReturnValue(int val) {
            mGetStrongAuthForUserReturnValue = val;
        }

        @Override
        public int getStrongAuthForUser(int userId) {
            return mGetStrongAuthForUserReturnValue;
        }
    }
}
