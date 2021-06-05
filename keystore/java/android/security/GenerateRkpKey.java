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

package android.security;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * GenerateKey is a helper class to handle interactions between Keystore and the RemoteProvisioner
 * app. There are two cases where Keystore should use this class.
 *
 * (1) : An app generates a new attested key pair, so Keystore calls notifyKeyGenerated to let the
 *       RemoteProvisioner app check if the state of the attestation key pool is getting low enough
 *       to warrant provisioning more attestation certificates early.
 *
 * (2) : An app attempts to generate a new key pair, but the keystore service discovers it is out of
 *       attestation key pairs and cannot provide one for the given application. Keystore can then
 *       make a blocking call on notifyEmpty to allow the RemoteProvisioner app to get another
 *       attestation certificate chain provisioned.
 *
 * In most cases, the proper usage of (1) should preclude the need for (2).
 *
 * @hide
 */
public class GenerateRkpKey {
    private static final String TAG = "GenerateRkpKey";

    private static final int NOTIFY_EMPTY = 0;
    private static final int NOTIFY_KEY_GENERATED = 1;
    private static final int TIMEOUT_MS = 1000;

    private IGenerateRkpKeyService mBinder;
    private Context mContext;
    private CountDownLatch mCountDownLatch;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mBinder = IGenerateRkpKeyService.Stub.asInterface(service);
            mCountDownLatch.countDown();
        }

        @Override public void onBindingDied(ComponentName className) {
            mCountDownLatch.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            mBinder = null;
        }
    };

    /**
     * Constructor which takes a Context object.
     */
    public GenerateRkpKey(Context context) {
        mContext = context;
    }

    private void bindAndSendCommand(int command, int securityLevel) throws RemoteException {
        Intent intent = new Intent(IGenerateRkpKeyService.class.getName());
        ComponentName comp = intent.resolveSystemService(mContext.getPackageManager(), 0);
        if (comp == null) {
            throw new RemoteException("Could not resolve GenerateRkpKeyService.");
        }
        intent.setComponent(comp);
        mCountDownLatch = new CountDownLatch(1);
        if (!mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)) {
            throw new RemoteException("Failed to bind to GenerateRkpKeyService");
        }
        try {
            mCountDownLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted: ", e);
        }
        if (mBinder != null) {
            switch (command) {
                case NOTIFY_EMPTY:
                    mBinder.generateKey(securityLevel);
                    break;
                case NOTIFY_KEY_GENERATED:
                    mBinder.notifyKeyGenerated(securityLevel);
                    break;
                default:
                    Log.e(TAG, "Invalid case for command");
            }
        } else {
            Log.e(TAG, "Binder object is null; failed to bind to GenerateRkpKeyService.");
        }
        mContext.unbindService(mConnection);
    }

    /**
     * Fulfills the use case of (2) described in the class documentation. Blocks until the
     * RemoteProvisioner application can get new attestation keys signed by the server.
     */
    public void notifyEmpty(int securityLevel) throws RemoteException {
        bindAndSendCommand(NOTIFY_EMPTY, securityLevel);
    }

    /**
     * Fulfills the use case of (1) described in the class documentation. Non blocking call.
     */
    public void notifyKeyGenerated(int securityLevel) throws RemoteException {
        bindAndSendCommand(NOTIFY_KEY_GENERATED, securityLevel);
    }
}
