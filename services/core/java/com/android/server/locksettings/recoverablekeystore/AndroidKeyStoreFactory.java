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

package com.android.server.locksettings.recoverablekeystore;

import android.security.keystore.AndroidKeyStoreProvider;

import java.security.KeyStoreException;
import java.security.NoSuchProviderException;

public interface AndroidKeyStoreFactory {
    KeyStoreProxy getKeyStoreForUid(int uid) throws KeyStoreException, NoSuchProviderException;

    class Impl implements AndroidKeyStoreFactory {
        @Override
        public KeyStoreProxy getKeyStoreForUid(int uid)
                throws KeyStoreException, NoSuchProviderException {
            return new KeyStoreProxyImpl(AndroidKeyStoreProvider.getKeyStoreForUid(uid));
        }
    }
}
