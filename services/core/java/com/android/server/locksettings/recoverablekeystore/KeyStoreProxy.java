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

import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;

/**
 * Proxies {@link java.security.KeyStore}. As all of its methods are final, it cannot otherwise be
 * mocked for tests.
 *
 * @hide
 */
public interface KeyStoreProxy {

    /** @see KeyStore#containsAlias(String) */
    boolean containsAlias(String alias) throws KeyStoreException;

    /** @see KeyStore#getKey(String, char[]) */
    Key getKey(String alias, char[] password)
            throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException;

    /** @see KeyStore#setEntry(String, KeyStore.Entry, KeyStore.ProtectionParameter) */
    void setEntry(String alias, KeyStore.Entry entry, KeyStore.ProtectionParameter protParam)
            throws KeyStoreException;

    /** @see KeyStore#deleteEntry(String) */
    void deleteEntry(String alias) throws KeyStoreException;
}
