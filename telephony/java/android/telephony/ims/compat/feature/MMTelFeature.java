/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony.ims.compat.feature;

import com.android.ims.ImsCallProfile;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsCallSessionListener;
import com.android.ims.internal.ImsCallSession;

/**
 * Compatability layer for older implementations of MMTelFeature.
 *
 * @hide
 */

public class MMTelFeature extends android.telephony.ims.feature.MMTelFeature {

    @Override
    public final IImsCallSession createCallSession(int sessionId, ImsCallProfile profile) {
        return createCallSession(sessionId, profile, null /*listener*/);
    }

    /**
     * Creates an {@link ImsCallSession} with the specified call profile.
     *
     * @param sessionId a session id which is obtained from {@link #startSession}
     * @param profile a call profile to make the call
     * @param listener An implementation of IImsCallSessionListener.
     */
    public IImsCallSession createCallSession(int sessionId, ImsCallProfile profile,
            IImsCallSessionListener listener) {
        return null;
    }
}
