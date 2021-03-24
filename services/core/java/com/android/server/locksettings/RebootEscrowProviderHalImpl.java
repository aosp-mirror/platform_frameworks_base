/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.locksettings;

import android.annotation.Nullable;
import android.hardware.rebootescrow.IRebootEscrow;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.NoSuchElementException;

import javax.crypto.SecretKey;

/**
 * An implementation of the {@link RebootEscrowProviderInterface} by calling the RebootEscrow HAL.
 */
class RebootEscrowProviderHalImpl implements RebootEscrowProviderInterface {
    private static final String TAG = "RebootEscrowProviderHal";

    private final Injector mInjector;

    static class Injector {
        @Nullable
        public IRebootEscrow getRebootEscrow() {
            try {
                return IRebootEscrow.Stub.asInterface(ServiceManager.getService(
                        "android.hardware.rebootescrow.IRebootEscrow/default"));
            } catch (NoSuchElementException e) {
                Slog.i(TAG, "Device doesn't implement RebootEscrow HAL");
            }
            return null;
        }
    }

    RebootEscrowProviderHalImpl() {
        mInjector = new Injector();
    }

    @VisibleForTesting
    RebootEscrowProviderHalImpl(Injector injector) {
        mInjector = injector;
    }

    @Override
    public int getType() {
        return TYPE_HAL;
    }

    @Override
    public boolean hasRebootEscrowSupport() {
        return mInjector.getRebootEscrow() != null;
    }

    @Override
    public RebootEscrowKey getAndClearRebootEscrowKey(SecretKey decryptionKey) {
        IRebootEscrow rebootEscrow = mInjector.getRebootEscrow();
        if (rebootEscrow == null) {
            Slog.w(TAG, "Had reboot escrow data for users, but RebootEscrow HAL is unavailable");
            return null;
        }

        try {
            byte[] escrowKeyBytes = rebootEscrow.retrieveKey();
            if (escrowKeyBytes == null) {
                Slog.w(TAG, "Had reboot escrow data for users, but could not retrieve key");
                return null;
            } else if (escrowKeyBytes.length != 32) {
                Slog.e(TAG, "IRebootEscrow returned key of incorrect size "
                        + escrowKeyBytes.length);
                return null;
            }

            // Make sure we didn't get the null key.
            int zero = 0;
            for (int i = 0; i < escrowKeyBytes.length; i++) {
                zero |= escrowKeyBytes[i];
            }
            if (zero == 0) {
                Slog.w(TAG, "IRebootEscrow returned an all-zeroes key");
                return null;
            }

            // Overwrite the existing key with the null key
            rebootEscrow.storeKey(new byte[32]);

            return RebootEscrowKey.fromKeyBytes(escrowKeyBytes);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not retrieve escrow data");
            return null;
        } catch (ServiceSpecificException e) {
            Slog.w(TAG, "Got service-specific exception: " + e.errorCode);
            return null;
        }
    }

    @Override
    public void clearRebootEscrowKey() {
        IRebootEscrow rebootEscrow = mInjector.getRebootEscrow();
        if (rebootEscrow == null) {
            return;
        }

        try {
            rebootEscrow.storeKey(new byte[32]);
        } catch (RemoteException | ServiceSpecificException e) {
            Slog.w(TAG, "Could not call RebootEscrow HAL to shred key");
        }

    }

    @Override
    public boolean storeRebootEscrowKey(RebootEscrowKey escrowKey, SecretKey encryptionKey) {
        IRebootEscrow rebootEscrow = mInjector.getRebootEscrow();
        if (rebootEscrow == null) {
            Slog.w(TAG, "Escrow marked as ready, but RebootEscrow HAL is unavailable");
            return false;
        }

        try {
            // The HAL interface only accept 32 bytes data. And the encrypted bytes for the escrow
            // key may exceed that limit. So we just store the raw key bytes here.
            rebootEscrow.storeKey(escrowKey.getKeyBytes());
            Slog.i(TAG, "Reboot escrow key stored with RebootEscrow HAL");
            return true;
        } catch (RemoteException | ServiceSpecificException e) {
            Slog.e(TAG, "Failed escrow secret to RebootEscrow HAL", e);
        }
        return false;
    }
}
