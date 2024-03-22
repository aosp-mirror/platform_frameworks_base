/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;

/**
 * This class provides some constants and helper methods related to Android's Keystore service.
 * This class was originally much larger, but its functionality was superseded by other classes.
 * It now just contains a few remaining pieces for which the users haven't been updated yet.
 * You may be looking for {@link java.security.KeyStore} instead.
 *
 * @hide
 */
public class KeyStore {

    // Used for UID field to indicate the calling UID.
    public static final int UID_SELF = -1;

    private static final KeyStore KEY_STORE = new KeyStore();

    @UnsupportedAppUsage
    public static KeyStore getInstance() {
        return KEY_STORE;
    }
}
