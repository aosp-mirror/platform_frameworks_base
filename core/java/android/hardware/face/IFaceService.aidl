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
 * limitations under the License.
 */
package android.hardware.face;

import android.os.Bundle;
import android.hardware.face.IFaceServiceReceiver;
import android.hardware.face.IFaceServiceLockoutResetCallback;
import android.hardware.face.Face;

/**
 * Communication channel from client to the face service.
 * @hide
 */
interface IFaceService {
    // Authenticate the given sessionId with a face
    void authenticate(IBinder token, long sessionId,
            IFaceServiceReceiver receiver, int flags, String opPackageName);

    // Cancel authentication for the given sessionId
    void cancelAuthentication(IBinder token, String opPackageName);

    // Start face enrollment
    void enroll(IBinder token, in byte [] cryptoToken, int userId, IFaceServiceReceiver receiver,
                int flags, String opPackageName);

    // Cancel enrollment in progress
    void cancelEnrollment(IBinder token);

    // Any errors resulting from this call will be returned to the listener
    void remove(IBinder token, int userId, IFaceServiceReceiver receiver);

    // Get the enrolled face for user.
    Face getEnrolledFace(int userId, String opPackageName);

    // Determine if HAL is loaded and ready
    boolean isHardwareDetected(long deviceId, String opPackageName);

    // Get a pre-enrollment authentication token
    long preEnroll(IBinder token);

    // Finish an enrollment sequence and invalidate the authentication token
    int postEnroll(IBinder token);

    // Determine if a user has enrolled a face
    boolean hasEnrolledFace(int userId, String opPackageName);

    // Gets the number of hardware devices
    // int getHardwareDeviceCount();

    // Gets the unique device id for hardware enumerated at i
    // long getHardwareDevice(int i);

    // Gets the authenticator ID for face
    long getAuthenticatorId(String opPackageName);

    // Reset the timeout when user authenticates with strong auth (e.g. PIN, pattern or password)
    void resetTimeout(in byte [] cryptoToken);

    // Add a callback which gets notified when the face lockout period expired.
    void addLockoutResetCallback(IFaceServiceLockoutResetCallback callback);

    // Explicitly set the active user (for enrolling work profile)
    void setActiveUser(int uid);
}
