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

import android.hardware.face.Face;
import android.hardware.face.FaceAuthenticationFrame;
import android.hardware.face.FaceEnrollFrame;

/**
 * Communication channel from the FaceService back to FaceAuthenticationManager.
 * @hide
 */
oneway interface IFaceServiceReceiver {
    void onEnrollResult(in Face face, int remaining);
    void onAcquired(int acquiredInfo, int vendorCode);
    void onAuthenticationSucceeded(in Face face, int userId, boolean isStrongBiometric);
    void onFaceDetected(int sensorId, int userId, boolean isStrongBiometric);
    void onAuthenticationFailed();
    void onError(int error, int vendorCode);
    void onRemoved(in Face face, int remaining);
    void onFeatureSet(boolean success, int feature);
    void onFeatureGet(boolean success, in int[] features, in boolean[] featureState);
    void onChallengeGenerated(int sensorId, int userId, long challenge);
    void onAuthenticationFrame(in FaceAuthenticationFrame frame);
    void onEnrollmentFrame(in FaceEnrollFrame frame);
}
