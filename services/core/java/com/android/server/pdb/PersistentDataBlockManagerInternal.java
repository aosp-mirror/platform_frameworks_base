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
 * limitations under the License.
 */

package com.android.server.pdb;

/**
 * Internal interface for storing and retrieving persistent data.
 */
public interface PersistentDataBlockManagerInternal {

    /** Stores the handle to a lockscreen credential to be used for Factory Reset Protection. */
    void setFrpCredentialHandle(byte[] handle);

    /**
     * Retrieves handle to a lockscreen credential to be used for Factory Reset Protection.
     *
     * @throws IllegalStateException if the underlying storage is corrupt or inaccessible.
     */
    byte[] getFrpCredentialHandle();

    /** Stores the data used to enable the Test Harness Mode after factory-resetting. */
    void setTestHarnessModeData(byte[] data);

    /**
     * Retrieves the data used to place the device into Test Harness Mode.
     *
     * @throws IllegalStateException if the underlying storage is corrupt or inaccessible.
     */
    byte[] getTestHarnessModeData();

    /** Clear out the Test Harness Mode data. */
    void clearTestHarnessModeData();

    /** Update the OEM unlock enabled bit, bypassing user restriction checks. */
    void forceOemUnlockEnabled(boolean enabled);

    /** Retrieves the UID that can access the persistent data partition. */
    int getAllowedUid();
}
