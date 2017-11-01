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
 * limitations under the License
 */

package android.telephony.ims.feature;

import android.app.PendingIntent;
import android.os.Message;

import com.android.ims.ImsCallProfile;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsCallSessionListener;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsUt;

import java.util.ArrayList;
import java.util.List;

/**
 * Base implementation, which implements all methods in IMMTelFeature. Any class wishing to use
 * MMTelFeature should extend this class and implement all methods that the service supports.
 *
 * @hide
 */

public class MMTelFeature extends ImsFeature implements IMMTelFeature {

    @Override
    public int startSession(PendingIntent incomingCallIntent, IImsRegistrationListener listener) {
        return 0;
    }

    @Override
    public void endSession(int sessionId) {
    }

    @Override
    public boolean isConnected(int callSessionType, int callType) {
        return false;
    }

    @Override
    public boolean isOpened() {
        return false;
    }

    @Override
    public void addRegistrationListener(IImsRegistrationListener listener) {
    }

    @Override
    public void removeRegistrationListener(IImsRegistrationListener listener) {
    }

    @Override
    public ImsCallProfile createCallProfile(int sessionId, int callSessionType, int callType) {
        return null;
    }

    @Override
    public IImsCallSession createCallSession(int sessionId, ImsCallProfile profile,
            IImsCallSessionListener listener) {
        return null;
    }

    @Override
    public IImsCallSession getPendingCallSession(int sessionId, String callId) {
        return null;
    }

    @Override
    public IImsUt getUtInterface() {
        return null;
    }

    @Override
    public IImsConfig getConfigInterface() {
        return null;
    }

    @Override
    public void turnOnIms() {
    }

    @Override
    public void turnOffIms() {
    }

    @Override
    public IImsEcbm getEcbmInterface() {
        return null;
    }

    @Override
    public void setUiTTYMode(int uiTtyMode, Message onComplete) {
    }

    @Override
    public IImsMultiEndpoint getMultiEndpointInterface() {
        return null;
    }

    @Override
    public void onFeatureRemoved() {

    }
}
