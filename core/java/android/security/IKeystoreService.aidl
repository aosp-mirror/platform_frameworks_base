/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security;

import android.security.keymaster.ExportResult;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterCertificateChain;
import android.security.keymaster.KeymasterBlob;
import android.security.keymaster.OperationResult;
import android.security.KeystoreArguments;

/**
 * This must be kept manually in sync with system/security/keystore until AIDL
 * can generate both Java and C++ bindings.
 *
 * @hide
 */
interface IKeystoreService {
    int getState(int userId);
    byte[] get(String name, int uid);
    int insert(String name, in byte[] item, int uid, int flags);
    int del(String name, int uid);
    int exist(String name, int uid);
    String[] list(String namePrefix, int uid);
    int reset();
    int onUserPasswordChanged(int userId, String newPassword);
    int lock(int userId);
    int unlock(int userId, String userPassword);
    int isEmpty(int userId);
    int generate(String name, int uid, int keyType, int keySize, int flags,
        in KeystoreArguments args);
    int import_key(String name, in byte[] data, int uid, int flags);
    byte[] sign(String name, in byte[] data);
    int verify(String name, in byte[] data, in byte[] signature);
    byte[] get_pubkey(String name);
    int grant(String name, int granteeUid);
    int ungrant(String name, int granteeUid);
    long getmtime(String name, int uid);
    int duplicate(String srcKey, int srcUid, String destKey, int destUid);
    int is_hardware_backed(String string);
    int clear_uid(long uid);

    // Keymaster 0.4 methods
    int addRngEntropy(in byte[] data);
    int generateKey(String alias, in KeymasterArguments arguments, in byte[] entropy, int uid,
        int flags, out KeyCharacteristics characteristics);
    int getKeyCharacteristics(String alias, in KeymasterBlob clientId, in KeymasterBlob appId,
        int uid, out KeyCharacteristics characteristics);
    int importKey(String alias, in KeymasterArguments arguments, int format,
        in byte[] keyData, int uid, int flags, out KeyCharacteristics characteristics);
    ExportResult exportKey(String alias, int format, in KeymasterBlob clientId,
        in KeymasterBlob appId, int uid);
    OperationResult begin(IBinder appToken, String alias, int purpose, boolean pruneable,
        in KeymasterArguments params, in byte[] entropy, int uid);
    OperationResult update(IBinder token, in KeymasterArguments params, in byte[] input);
    OperationResult finish(IBinder token, in KeymasterArguments params, in byte[] signature,
        in byte[] entropy);
    int abort(IBinder handle);
    boolean isOperationAuthorized(IBinder token);
    int addAuthToken(in byte[] authToken);
    int onUserAdded(int userId, int parentId);
    int onUserRemoved(int userId);
    int attestKey(String alias, in KeymasterArguments params, out KeymasterCertificateChain chain);
}
