/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.service.fingerprint;

import android.os.Bundle;
import android.service.fingerprint.IFingerprintServiceReceiver;

/**
 * Communication channel from client to the fingerprint service.
 * @hide
 */
oneway interface IFingerprintService {
    // Any errors resulting from this call will be returned to the listener
    void enroll(IBinder token, long timeout, int userId);
    
    // Any errors resulting from this call will be returned to the listener
    void enrollCancel(IBinder token, int userId);

    // Any errors resulting from this call will be returned to the listener
    void remove(IBinder token, int fingerprintId, int userId);

    // Start listening for fingerprint events.  This has the side effect of starting
    // the hardware if not already started.
    void startListening(IBinder token, IFingerprintServiceReceiver receiver, int userId);

    // Stops listening for fingerprints
    void stopListening(IBinder token, int userId);
}
