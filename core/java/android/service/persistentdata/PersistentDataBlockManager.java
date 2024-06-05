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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.security.Flags;
import android.service.oemlock.OemLockManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface to the persistent data partition.  Provides access to information about the state
 * of factory reset protection.
 */
@FlaggedApi(Flags.FLAG_FRP_ENFORCEMENT)
@SystemService(Context.PERSISTENT_DATA_BLOCK_SERVICE)
public class PersistentDataBlockManager {
    private static final String TAG = PersistentDataBlockManager.class.getSimpleName();
    private IPersistentDataBlockService sService;

    /**
     * Indicates that the device's bootloader lock state is UNKNOWN.
     *
     * @hide
     */
    @SystemApi
    public static final int FLASH_LOCK_UNKNOWN = -1;
    /**
     * Indicates that the device's bootloader is UNLOCKED.
     *
     * @hide
     */
    @SystemApi
    public static final int FLASH_LOCK_UNLOCKED = 0;
    /**
     * Indicates that the device's bootloader is LOCKED.
     *
     * @hide
     */
    @SystemApi
    public static final int FLASH_LOCK_LOCKED = 1;

    /**
     * @removed mistakenly exposed previously
     *
     * @hide
     */
    @SystemApi
    @IntDef(prefix = { "FLASH_LOCK_" }, value = {
            FLASH_LOCK_UNKNOWN,
            FLASH_LOCK_LOCKED,
            FLASH_LOCK_UNLOCKED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FlashLockState {}

    /**
     * @hide
     */
    public PersistentDataBlockManager(IPersistentDataBlockService service) {
        sService = service;
    }

    /**
     * Writes {@code data} to the persistent partition. Previously written data
     * will be overwritten. This data will persist across factory resets.
     *
     * Returns the number of bytes written or -1 on error. If the block is too big
     * to fit on the partition, returns -MAX_BLOCK_SIZE.
     *
     * {@link #wipe} will block any further {@link #write} operation until reboot,
     * in which case -1 will be returned.
     *
     * @param data the data to write
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("RequiresPermission")
    public int write(@Nullable byte[] data) {
        try {
            return sService.write(data);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the data block stored on the persistent partition.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("RequiresPermission")
    public @Nullable byte[] read() {
        try {
            return sService.read();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the size of the block currently written to the persistent partition.
     *
     * Return -1 on error.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_PDB_STATE)
    public int getDataBlockSize() {
        try {
            return sService.getDataBlockSize();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the maximum size allowed for a data block.
     *
     * Returns -1 on error.
     *
     * @hide
     */
    @SystemApi
    @SuppressLint("RequiresPermission")
    public long getMaximumDataBlockSize() {
        try {
            return sService.getMaximumDataBlockSize();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Zeroes the previously written block in its entirety. Calling this method
     * will erase all data written to the persistent data partition.
     * It will also prevent any further {@link #write} operation until reboot,
     * in order to prevent a potential race condition. See b/30352311.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.OEM_UNLOCK_STATE)
    public void wipe() {
        try {
            sService.wipe();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Writes a byte enabling or disabling the ability to "OEM unlock" the device.
     *
     * @deprecated use {@link OemLockManager#setOemUnlockAllowedByUser(boolean)} instead.
     *
     * @hide
     */
    @SystemApi
    @Deprecated
    @RequiresPermission(android.Manifest.permission.OEM_UNLOCK_STATE)
    public void setOemUnlockEnabled(boolean enabled) {
        try {
            sService.setOemUnlockEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether or not "OEM unlock" is enabled or disabled on this device.
     *
     * @deprecated use {@link OemLockManager#isOemUnlockAllowedByUser()} instead.
     *
     * @hide
     */
    @SystemApi
    @Deprecated
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_OEM_UNLOCK_STATE,
            android.Manifest.permission.OEM_UNLOCK_STATE
    })
    public boolean getOemUnlockEnabled() {
        try {
            return sService.getOemUnlockEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves available information about this device's flash lock state.
     *
     * @return {@link #FLASH_LOCK_LOCKED} if device bootloader is locked,
     * {@link #FLASH_LOCK_UNLOCKED} if device bootloader is unlocked, or {@link #FLASH_LOCK_UNKNOWN}
     * if this information cannot be ascertained on this device.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.READ_OEM_UNLOCK_STATE,
            android.Manifest.permission.OEM_UNLOCK_STATE
    })
    @FlashLockState
    public int getFlashLockState() {
        try {
            return sService.getFlashLockState();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the package name which can access the persistent data partition.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    @RequiresPermission(android.Manifest.permission.ACCESS_PDB_STATE)
    public String getPersistentDataPackageName() {
        try {
            return sService.getPersistentDataPackageName();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if FactoryResetProtection (FRP) is active, meaning the device rebooted and has
     * not been able to deactivate FRP because the deactivation secrets were wiped by an untrusted
     * factory reset.
     */
    @FlaggedApi(Flags.FLAG_FRP_ENFORCEMENT)
    public boolean isFactoryResetProtectionActive() {
        try {
            return sService.isFactoryResetProtectionActive();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Attempt to deactivate FRP with the provided secret.  If the provided secret matches the
     * stored FRP secret, FRP is deactivated and the method returns true.  Otherwise, FRP state
     * remains unchanged and the method returns false.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_FRP_ENFORCEMENT)
    @SystemApi
    @RequiresPermission(android.Manifest.permission.CONFIGURE_FACTORY_RESET_PROTECTION)
    public boolean deactivateFactoryResetProtection(@NonNull byte[] secret) {
        try {
            return sService.deactivateFactoryResetProtection(secret);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Store the provided FRP secret as the secret to be used for future FRP deactivation.  The
     * secret must be 32 bytes in length.  Setting the all-zeros "default" value disables the FRP
     * feature entirely.
     *
     * To ensure that the device doesn't end up in a bad state if a crash occurs, this method
     * should be used in a three-step process:
     *
     * 1.  Generate a new secret and securely store any necessary copies (e.g. by encrypting them
     *     and calling #write with a new data block that contains both the old encrypted secret
     *     copies and the new ones).
     * 2.  Call this method to set the new FRP secret.  This will also write the copy used during
     *     normal boot.
     * 3.  Delete any old FRP secret copies (e.g. by calling #write with a new data block that
     *     contains only the new encrypted secret copies).
     *
     * Note that this method does nothing if FRP is currently active.
     *
     * This method does not require any permission, but can be called only by the
     * PersistentDataBlockService's authorized caller UID.
     *
     * Returns true if the new secret was successfully written.  Returns false if FRP is currently
     * active.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_FRP_ENFORCEMENT)
    @SystemApi
    @SuppressLint("RequiresPermission")
    public boolean setFactoryResetProtectionSecret(@NonNull byte[] secret) {
        try {
            return sService.setFactoryResetProtectionSecret(secret);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
