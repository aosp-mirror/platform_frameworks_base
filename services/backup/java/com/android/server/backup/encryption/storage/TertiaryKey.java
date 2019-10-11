/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.encryption.storage;

/** Wrapped bytes of a tertiary key. */
public class TertiaryKey {
    private final String mSecondaryKeyAlias;
    private final String mPackageName;
    private final byte[] mWrappedKeyBytes;

    /**
     * Creates a new instance.
     *
     * @param secondaryKeyAlias Alias of the secondary used to wrap the key.
     * @param packageName The package name of the app to which the key belongs.
     * @param wrappedKeyBytes The wrapped key bytes.
     */
    public TertiaryKey(String secondaryKeyAlias, String packageName, byte[] wrappedKeyBytes) {
        mSecondaryKeyAlias = secondaryKeyAlias;
        mPackageName = packageName;
        mWrappedKeyBytes = wrappedKeyBytes;
    }

    /** Returns the alias of the secondary key used to wrap this tertiary key. */
    public String getSecondaryKeyAlias() {
        return mSecondaryKeyAlias;
    }

    /** Returns the package name of the application this key relates to. */
    public String getPackageName() {
        return mPackageName;
    }

    /** Returns the wrapped bytes of the key. */
    public byte[] getWrappedKeyBytes() {
        return mWrappedKeyBytes;
    }
}
