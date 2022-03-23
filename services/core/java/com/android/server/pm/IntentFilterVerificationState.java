/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.pm.PackageManager;
import android.content.pm.parsing.component.ParsedIntentInfo;
import android.util.ArraySet;
import android.util.Slog;

import java.util.ArrayList;

public class IntentFilterVerificationState {
    static final String TAG = IntentFilterVerificationState.class.getName();

    public final static int STATE_UNDEFINED = 0;
    public final static int STATE_VERIFICATION_PENDING = 1;
    public final static int STATE_VERIFICATION_SUCCESS = 2;
    public final static int STATE_VERIFICATION_FAILURE = 3;

    private int mRequiredVerifierUid = 0;

    private int mState;

    private ArrayList<ParsedIntentInfo> mFilters = new ArrayList<>();
    private ArraySet<String> mHosts = new ArraySet<>();
    private int mUserId;

    private String mPackageName;
    private boolean mVerificationComplete;

    public IntentFilterVerificationState(int verifierUid, int userId, String packageName) {
        mRequiredVerifierUid = verifierUid;
        mUserId = userId;
        mPackageName = packageName;
        mState = STATE_UNDEFINED;
        mVerificationComplete = false;
    }

    public void setState(int state) {
        if (state > STATE_VERIFICATION_FAILURE || state < STATE_UNDEFINED) {
            mState = STATE_UNDEFINED;
        } else {
            mState = state;
        }
    }

    public int getState() {
        return mState;
    }

    public void setPendingState() {
        setState(STATE_VERIFICATION_PENDING);
    }

    public ArrayList<ParsedIntentInfo> getFilters() {
        return mFilters;
    }

    public boolean isVerificationComplete() {
        return mVerificationComplete;
    }

    public boolean isVerified() {
        if (mVerificationComplete) {
            return (mState == STATE_VERIFICATION_SUCCESS);
        }
        return false;
    }

    public int getUserId() {
        return mUserId;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public String getHostsString() {
        StringBuilder sb = new StringBuilder();
        final int count = mHosts.size();
        for (int i=0; i<count; i++) {
            if (i > 0) {
                sb.append(" ");
            }
            String host = mHosts.valueAt(i);
            // "*.example.tld" is validated via https://example.tld
            if (host.startsWith("*.")) {
                host = host.substring(2);
            }
            sb.append(host);
        }
        return sb.toString();
    }

    public boolean setVerifierResponse(int callerUid, int code) {
        if (mRequiredVerifierUid == callerUid) {
            int state = STATE_UNDEFINED;
            if (code == PackageManager.INTENT_FILTER_VERIFICATION_SUCCESS) {
                state = STATE_VERIFICATION_SUCCESS;
            } else if (code == PackageManager.INTENT_FILTER_VERIFICATION_FAILURE) {
                state = STATE_VERIFICATION_FAILURE;
            }
            mVerificationComplete = true;
            setState(state);
            return true;
        }
        Slog.d(TAG, "Cannot set verifier response with callerUid:" + callerUid + " and code:" +
                code + " as required verifierUid is:" + mRequiredVerifierUid);
        return false;
    }

    public void addFilter(ParsedIntentInfo filter) {
        mFilters.add(filter);
        mHosts.addAll(filter.getHostsList());
    }
}
