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

import android.security.KeystoreArguments;

/**
 * This must be kept manually in sync with system/security/keystore until AIDL
 * can generate both Java and C++ bindings.
 *
 * @hide
 */
interface IKeystoreService {
    int test();
    byte[] get(String name);
    int insert(String name, in byte[] item, int uid, int flags);
    int del(String name, int uid);
    int exist(String name, int uid);
    String[] saw(String namePrefix, int uid);
    int reset();
    int password(String password);
    int lock();
    int unlock(String password);
    int zero();
    int generate(String name, int uid, int keyType, int keySize, int flags,
        in KeystoreArguments args);
    int import_key(String name, in byte[] data, int uid, int flags);
    byte[] sign(String name, in byte[] data);
    int verify(String name, in byte[] data, in byte[] signature);
    byte[] get_pubkey(String name);
    int del_key(String name, int uid);
    int grant(String name, int granteeUid);
    int ungrant(String name, int granteeUid);
    long getmtime(String name);
    int duplicate(String srcKey, int srcUid, String destKey, int destUid);
    int is_hardware_backed(String string);
    int clear_uid(long uid);
    int reset_uid(int uid);
    int sync_uid(int sourceUid, int targetUid);
    int password_uid(String password, int uid);
}
