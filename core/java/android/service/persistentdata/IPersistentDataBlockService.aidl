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

package android.service.persistentdata;

import android.os.ParcelFileDescriptor;

/**
 * Internal interface through which to communicate to the
 * PersistentDataBlockService. The persistent data block allows writing
 * raw data and setting the OEM unlock enabled/disabled bit contained
 * in the partition.
 *
 * @hide
 */
interface IPersistentDataBlockService {
    int write(in byte[] data);
    byte[] read();
    void wipe();
    int getDataBlockSize();
    long getMaximumDataBlockSize();

    void setOemUnlockEnabled(boolean enabled);
    boolean getOemUnlockEnabled();
    int getFlashLockState();
    boolean hasFrpCredentialHandle();
    String getPersistentDataPackageName();

    /**
     * Returns true if Factory Reset Protection (FRP) is active, meaning the device rebooted and has
     * not been able to transition to the FRP inactive state.
     */
    boolean isFactoryResetProtectionActive();

    /**
     * Attempts to deactivate Factory Reset Protection (FRP) with the provided secret.  If the
     * provided secret matches the stored FRP secret, FRP is deactivated and the method returns
     * true.  Otherwise, FRP state remains unchanged and the method returns false.
     */
    boolean deactivateFactoryResetProtection(in byte[] secret);

    /**
     * Stores the provided Factory Reset Protection (FRP) secret as the secret to be used for future
     * FRP deactivation.  The secret must be 32 bytes in length.  Setting the all-zeros "default"
     * value disables the FRP feature entirely.
     *
     * It's the responsibility of the caller to ensure that copies of the FRP secret are stored
     * securely where they can be recovered and used to deactivate FRP after an untrusted reset.
     * This method will store a copy in /data/system and use that to automatically deactivate FRP
     * until /data is wiped.
     *
     * Note that this method does nothing if FRP is currently active.
     *
     * Returns true if the secret was successfully changed, false otherwise.
     */
    boolean setFactoryResetProtectionSecret(in byte[] secret);
}
