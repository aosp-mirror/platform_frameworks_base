/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.telephony.sip;

import com.android.internal.telephony.*;
import java.util.List;

// TODO: remove this class after integrating with CallManager
class CallProxy extends Call {
    private Call mTarget;

    void setTarget(Call target) {
        mTarget = target;
    }

    @Override
    public List<Connection> getConnections() {
        return mTarget.getConnections();
    }

    @Override
    public Phone getPhone() {
        return mTarget.getPhone();
    }

    @Override
    public boolean isMultiparty() {
        return mTarget.isMultiparty();
    }

    @Override
    public void hangup() throws CallStateException {
        mTarget.hangup();
    }

    @Override
    public boolean hasConnection(Connection c) {
        return mTarget.hasConnection(c);
    }

    @Override
    public boolean hasConnections() {
        return mTarget.hasConnections();
    }

    @Override
    public State getState() {
        return mTarget.getState();
    }

    @Override
    public boolean isIdle() {
        return mTarget.isIdle();
    }

    @Override
    public Connection getEarliestConnection() {
        return mTarget.getEarliestConnection();
    }

    @Override
    public long getEarliestCreateTime() {
        return mTarget.getEarliestCreateTime();
    }

    @Override
    public long getEarliestConnectTime() {
        return mTarget.getEarliestConnectTime();
    }

    @Override
    public boolean isDialingOrAlerting() {
        return mTarget.isDialingOrAlerting();
    }

    @Override
    public boolean isRinging() {
        return mTarget.isRinging();
    }

    @Override
    public Connection getLatestConnection() {
        return mTarget.getLatestConnection();
    }

    @Override
    public boolean isGeneric() {
        return mTarget.isGeneric();
    }

    @Override
    public void setGeneric(boolean generic) {
        mTarget.setGeneric(generic);
    }

    @Override
    public void hangupIfAlive() {
        mTarget.hangupIfAlive();
    }
}
