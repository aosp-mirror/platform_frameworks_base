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

package android.net.sip;

import android.app.PendingIntent;
import android.net.sip.ISipSession;
import android.net.sip.ISipSessionListener;
import android.net.sip.SipProfile;

/**
 * {@hide}
 */
interface ISipService {
    void open(in SipProfile localProfile);
    void open3(in SipProfile localProfile,
            in PendingIntent incomingCallPendingIntent,
            in ISipSessionListener listener);
    void close(in String localProfileUri);
    boolean isOpened(String localProfileUri);
    boolean isRegistered(String localProfileUri);
    void setRegistrationListener(String localProfileUri,
            ISipSessionListener listener);

    ISipSession createSession(in SipProfile localProfile,
            in ISipSessionListener listener);
    ISipSession getPendingSession(String callId);

    SipProfile[] getListOfProfiles();
}
