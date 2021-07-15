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

    private IGenerateRkpKeyService mBinder;
    private Context mContext;

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mBinder = IGenerateRkpKeyService.Stub.asInterface(service);
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

    /**
     * Fulfills the use case of (2) described in the class documentation. Blocks until the
     * RemoteProvisioner application can get new attestation keys signed by the server.
     */
    public void notifyEmpty(int securityLevel) throws RemoteException {
        Intent intent = new Intent(IGenerateRkpKeyService.class.getName());
        ComponentName comp = intent.resolveSystemService(mContext.getPackageManager(), 0);
        intent.setComponent(comp);
        if (comp == null || !mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)) {
            throw new RemoteException("Failed to bind to GenerateKeyService");
        }
        if (mBinder != null) {
            mBinder.generateKey(securityLevel);
        }
        mContext.unbindService(mConnection);
    }

    /**
     * FUlfills the use case of (1) described in the class documentation. Non blocking call.
     */
    public void notifyKeyGenerated(int securityLevel) throws RemoteException {
        Intent intent = new Intent(IGenerateRkpKeyService.class.getName());
        ComponentName comp = intent.resolveSystemService(mContext.getPackageManager(), 0);
        intent.setComponent(comp);
        if (comp == null || !mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)) {
            throw new RemoteException("Failed to bind to GenerateKeyService");
        }
        if (mBinder != null) {
            mBinder.notifyKeyGenerated(securityLevel);
        }
        mContext.unbindService(mConnection);
    }
}
