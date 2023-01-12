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

package android.security.rkp;

/**
 * Callback interface for storing an upgraded remotely provisioned key blob.
 * {@link IRegistration}.
 *
 * @hide
 */
oneway interface IStoreUpgradedKeyCallback {
    /**
     * Called in response to {@link IRegistration.storeUpgradedKeyAsync}, indicating
     * a remotely-provisioned key is available.
     */
    void onSuccess();

    /**
     * Called when an error has occurred while trying to store an upgraded
     * remotely provisioned key.
     *
     * @param error A description of what failed, suitable for logging.
     */
    void onError(String error);
}
