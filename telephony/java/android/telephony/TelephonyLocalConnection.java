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

package android.telephony;

import java.util.UUID;

/**
 * Shim used for code in frameworks/opt/telephony to be able to call code in
 * packages/services/Telephony. A singleton instance of this class is set when the phone process
 * is brought up.
 * @hide
 */
public class TelephonyLocalConnection {
    public interface ConnectionImpl {
        String getCallComposerServerUrlForHandle(int subscriptionId, UUID uuid);
    }
    private static ConnectionImpl sInstance;

    public static String getCallComposerServerUrlForHandle(int subscriptionId, UUID uuid) {
        checkInstance();
        return sInstance.getCallComposerServerUrlForHandle(subscriptionId, uuid);
    }

    private static void checkInstance() {
        if (sInstance == null) {
            throw new IllegalStateException("Connection impl is null!");
        }
    }

    public static void setInstance(ConnectionImpl impl) {
        sInstance = impl;
    }
}
