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

package com.android.server.pm.verify.domain;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageManager;
import android.util.ArrayMap;
import android.util.SparseIntArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.annotations.GuardedBy;
import com.android.server.pm.SettingsXml;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Reads and writes the old {@link android.content.pm.IntentFilterVerificationInfo} so that it can
 * be migrated in to the new API. Will throw away the state once it's successfully applied so that
 * eventually there will be no legacy state on the device.
 *
 * This attempt is best effort, and if the legacy state is lost that's acceptable. The user setting
 * in the legacy API may have been set incorrectly because it was never made obvious to the user
 * what it actually toggled, so there's a strong argument to prevent migration anyways. The user
 * can just set their preferences again, this time with finer grained control, if the legacy state
 * gets dropped.
 */
public class DomainVerificationLegacySettings {

    public static final String TAG_DOMAIN_VERIFICATIONS_LEGACY = "domain-verifications-legacy";
    public static final String TAG_USER_STATES = "user-states";
    public static final String ATTR_PACKAGE_NAME = "packageName";
    public static final String TAG_USER_STATE = "user-state";
    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_STATE = "state";

    @NonNull
    private final Object mLock = new Object();

    @NonNull
    private final ArrayMap<String, LegacyState> mStates = new ArrayMap<>();

    public void add(@NonNull String packageName, @NonNull IntentFilterVerificationInfo info) {
        synchronized (mLock) {
            getOrCreateStateLocked(packageName).setInfo(info);
        }
    }

    public void add(@NonNull String packageName, @UserIdInt int userId, int state) {
        synchronized (mLock) {
            getOrCreateStateLocked(packageName).addUserState(userId, state);
        }
    }

    public int getUserState(@NonNull String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            LegacyState state = mStates.get(packageName);
            if (state != null) {
                return state.getUserState(userId);
            }
        }
        return PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
    }

    @Nullable
    public SparseIntArray getUserStates(@NonNull String packageName) {
        synchronized (mLock) {
            LegacyState state = mStates.get(packageName);
            if (state != null) {
                // Yes, this returns outside of the lock, but we assume that retrieval generally
                // only happens after all adding has concluded from reading settings.
                return state.getUserStates();
            }
        }
        return null;
    }

    @Nullable
    public IntentFilterVerificationInfo remove(@NonNull String packageName) {
        synchronized (mLock) {
            LegacyState state = mStates.get(packageName);
            if (state != null && !state.isAttached()) {
                state.markAttached();
                return state.getInfo();
            }
        }
        return null;
    }

    @GuardedBy("mLock")
    @NonNull
    private LegacyState getOrCreateStateLocked(@NonNull String packageName) {
        LegacyState state = mStates.get(packageName);
        if (state == null) {
            state = new LegacyState();
            mStates.put(packageName, state);
        }

        return state;
    }

    public void writeSettings(TypedXmlSerializer xmlSerializer) throws IOException {
        try (SettingsXml.Serializer serializer = SettingsXml.serializer(xmlSerializer)) {
            try (SettingsXml.WriteSection ignored =
                         serializer.startSection(TAG_DOMAIN_VERIFICATIONS_LEGACY)) {
                synchronized (mLock) {
                    final int statesSize = mStates.size();
                    for (int stateIndex = 0; stateIndex < statesSize; stateIndex++) {
                        final LegacyState state = mStates.valueAt(stateIndex);
                        final SparseIntArray userStates = state.getUserStates();
                        if (userStates == null) {
                            continue;
                        }

                        final String packageName = mStates.keyAt(stateIndex);
                        try (SettingsXml.WriteSection userStatesSection =
                                     serializer.startSection(TAG_USER_STATES)
                                             .attribute(ATTR_PACKAGE_NAME, packageName)) {
                            final int userStatesSize = userStates.size();
                            for (int userStateIndex = 0; userStateIndex < userStatesSize;
                                    userStateIndex++) {
                                final int userId = userStates.keyAt(userStateIndex);
                                final int userState = userStates.valueAt(userStateIndex);
                                userStatesSection.startSection(TAG_USER_STATE)
                                        .attribute(ATTR_USER_ID, userId)
                                        .attribute(ATTR_STATE, userState)
                                        .finish();
                            }
                        }
                    }
                }
            }
        }
    }

    public void readSettings(TypedXmlPullParser xmlParser)
            throws IOException, XmlPullParserException {
        final SettingsXml.ChildSection child = SettingsXml.parser(xmlParser).children();
        while (child.moveToNext()) {
            if (TAG_USER_STATES.equals(child.getName())) {
                readUserStates(child);
            }
        }
    }

    private void readUserStates(SettingsXml.ReadSection section) {
        String packageName = section.getString(ATTR_PACKAGE_NAME);
        synchronized (mLock) {
            final LegacyState legacyState = getOrCreateStateLocked(packageName);
            final SettingsXml.ChildSection child = section.children();
            while (child.moveToNext()) {
                if (TAG_USER_STATE.equals(child.getName())) {
                    readUserState(child, legacyState);
                }
            }
        }
    }

    private void readUserState(SettingsXml.ReadSection section, LegacyState legacyState) {
        int userId = section.getInt(ATTR_USER_ID);
        int state = section.getInt(ATTR_STATE);
        legacyState.addUserState(userId, state);
    }

    static class LegacyState {
        @Nullable
        private IntentFilterVerificationInfo mInfo;

        @Nullable
        private SparseIntArray mUserStates;

        private boolean attached;

        @Nullable
        public IntentFilterVerificationInfo getInfo() {
            return mInfo;
        }

        public int getUserState(int userId) {
            return mUserStates.get(userId,
                    PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED);
        }

        @Nullable
        public SparseIntArray getUserStates() {
            return mUserStates;
        }

        public void setInfo(@NonNull IntentFilterVerificationInfo info) {
            mInfo = info;
        }

        public void addUserState(@UserIdInt int userId, int state) {
            if (mUserStates == null) {
                mUserStates = new SparseIntArray(1);
            }
            mUserStates.put(userId, state);
        }

        public boolean isAttached() {
            return attached;
        }

        public void markAttached() {
            attached = true;
        }
    }
}
