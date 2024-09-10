/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.content.pm.PackageManager;

import com.android.os.dnd.DNDPolicyProto;

import com.google.protobuf.InvalidProtocolBufferException;

import java.util.ArrayList;
import java.util.List;

/**
 * ZenModeEventLoggerFake extends ZenModeEventLogger for ease of verifying logging output. This
 * class behaves exactly the same as its parent class except that instead of actually logging, it
 * stores the full information at time of log whenever something would be logged.
 */
public class ZenModeEventLoggerFake extends ZenModeEventLogger {
    // A record of the contents of each event we'd log, stored by recording the ChangeState object
    // at the time of the log.
    private List<ZenStateChanges> mChanges = new ArrayList<>();

    public ZenModeEventLoggerFake(PackageManager pm) {
        super(pm);
    }

    @Override
    void logChanges() {
        // current change state being logged
        mChanges.add(mChangeState.copy());
    }

    // Reset the state of the logger (remove all changes).
    public void reset() {
        mChanges = new ArrayList<>();
    }

    // Returns the number of changes logged.
    public int numLoggedChanges() {
        return mChanges.size();
    }

    // is index i out of range for the set of changes we have
    private boolean outOfRange(int i) {
        return i < 0 || i >= mChanges.size();
    }

    // Throw an exception if provided index is out of range
    private void checkInRange(int i) throws IllegalArgumentException {
        if (outOfRange(i)) {
            throw new IllegalArgumentException("invalid index for logged event: " + i);
        }
    }

    // Get the UiEvent ID of the i'th logged event.
    public int getEventId(int i) throws IllegalArgumentException {
        checkInRange(i);
        return mChanges.get(i).getEventId().getId();
    }

    // Get the previous zen mode associated with the change at event i.
    public int getPrevZenMode(int i) throws IllegalArgumentException {
        checkInRange(i);
        return mChanges.get(i).mPrevZenMode;
    }

    // Get the new zen mode associated with the change at event i.
    public int getNewZenMode(int i) throws IllegalArgumentException {
        checkInRange(i);
        return mChanges.get(i).mNewZenMode;
    }

    // Get the changed rule type associated with event i.
    public int getChangedRuleType(int i) throws IllegalArgumentException {
        checkInRange(i);
        return mChanges.get(i).getChangedRuleType();
    }

    public int getNumRulesActive(int i) throws IllegalArgumentException {
        checkInRange(i);
        return mChanges.get(i).getNumRulesActive();
    }

    public boolean getFromSystemOrSystemUi(int i) throws IllegalArgumentException {
        // While this isn't a logged output value, it's still helpful to check in tests.
        checkInRange(i);
        return mChanges.get(i).isFromSystemOrSystemUi();
    }

    public boolean getIsUserAction(int i) throws IllegalArgumentException {
        checkInRange(i);
        return mChanges.get(i).getIsUserAction();
    }

    public int getPackageUid(int i) throws IllegalArgumentException {
        checkInRange(i);
        return mChanges.get(i).getPackageUid();
    }

    // Get the DNDPolicyProto (unmarshaled from bytes) associated with event i.
    // Note that in creation of the log, we use a notification.proto mirror of DNDPolicyProto,
    // but here we use the actual logging-side proto to make sure they continue to match.
    public DNDPolicyProto getPolicyProto(int i) throws IllegalArgumentException {
        checkInRange(i);
        byte[] policyBytes = mChanges.get(i).getDNDPolicyProto();
        if (policyBytes == null) {
            return null;
        }
        try {
            return DNDPolicyProto.parseFrom(policyBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException("Couldn't parse DNDPolicyProto!", e);
        }
    }

    public boolean getAreChannelsBypassing(int i) throws IllegalArgumentException {
        checkInRange(i);
        return mChanges.get(i).getAreChannelsBypassing();
    }

    public int[] getActiveRuleTypes(int i) throws IllegalArgumentException {
        checkInRange(i);
        return mChanges.get(i).getActiveRuleTypes();
    }

    public int getChangeOrigin(int i) throws IllegalArgumentException {
        checkInRange(i);
        return mChanges.get(i).getChangeOrigin();
    }
}
