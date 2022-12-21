/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion.datatransfer.contextsync;

import android.telecom.Call;
import android.telecom.InCallService;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** In-call service to sync call metadata across a user's devices. */
public class CallMetadataSyncInCallService extends InCallService {

    @VisibleForTesting
    final Set<CrossDeviceCall> mCurrentCalls = new HashSet<>();

    @Override
    public void onCreate() {
        super.onCreate();
        mCurrentCalls.addAll(getCalls().stream().map(CrossDeviceCall::new).toList());
    }

    @Override
    public void onCallAdded(Call call) {
        onCallAdded(new CrossDeviceCall(call));
    }

    @VisibleForTesting
    void onCallAdded(CrossDeviceCall call) {
        mCurrentCalls.add(call);
    }

    @Override
    public void onCallRemoved(Call call) {
        mCurrentCalls.removeIf(crossDeviceCall -> crossDeviceCall.getCall().equals(call));
    }

    /** Data holder for a telecom call and additional metadata. */
    public static final class CrossDeviceCall {
        private static final AtomicLong sNextId = new AtomicLong(1);

        private final Call mCall;
        private final long mId;

        public CrossDeviceCall(Call call) {
            mCall = call;
            mId = sNextId.getAndIncrement();
        }

        public Call getCall() {
            return mCall;
        }

        public long getId() {
            return mId;
        }
    }
}
