/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterDefs;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @hide
 */
public abstract class KeymasterUtils {
    private KeymasterUtils() {}

    public static KeymasterException getKeymasterException(int keymasterErrorCode) {
        switch (keymasterErrorCode) {
            case KeymasterDefs.KM_ERROR_INVALID_AUTHORIZATION_TIMEOUT:
                // The name of this parameter significantly differs between Keymaster and framework
                // APIs. Use the framework wording to make life easier for developers.
                return new KeymasterException(keymasterErrorCode,
                        "Invalid user authentication validity duration");
            default:
                return new KeymasterException(keymasterErrorCode,
                        KeymasterDefs.getErrorMessage(keymasterErrorCode));
        }
    }

    public static CryptoOperationException getCryptoOperationException(KeymasterException e) {
        switch (e.getErrorCode()) {
            case KeymasterDefs.KM_ERROR_KEY_EXPIRED:
                return new KeyExpiredException();
            case KeymasterDefs.KM_ERROR_KEY_NOT_YET_VALID:
                return new KeyNotYetValidException();
            case KeymasterDefs.KM_ERROR_KEY_USER_NOT_AUTHENTICATED:
                return new UserNotAuthenticatedException();
            default:
                return new CryptoOperationException("Crypto operation failed", e);
        }
    }

    public static CryptoOperationException getCryptoOperationException(int keymasterErrorCode) {
        return getCryptoOperationException(getKeymasterException(keymasterErrorCode));
    }

    public static Integer getInt(KeyCharacteristics keyCharacteristics, int tag) {
        if (keyCharacteristics.hwEnforced.containsTag(tag)) {
            return keyCharacteristics.hwEnforced.getInt(tag, -1);
        } else if (keyCharacteristics.swEnforced.containsTag(tag)) {
            return keyCharacteristics.swEnforced.getInt(tag, -1);
        } else {
            return null;
        }
    }

    public static List<Integer> getInts(KeyCharacteristics keyCharacteristics, int tag) {
        List<Integer> result = new ArrayList<Integer>();
        result.addAll(keyCharacteristics.hwEnforced.getInts(tag));
        result.addAll(keyCharacteristics.swEnforced.getInts(tag));
        return result;
    }

    public static Date getDate(KeyCharacteristics keyCharacteristics, int tag) {
        Date result = keyCharacteristics.hwEnforced.getDate(tag, null);
        if (result == null) {
            result = keyCharacteristics.swEnforced.getDate(tag, null);
        }
        return result;
    }

    public static boolean getBoolean(KeyCharacteristics keyCharacteristics, int tag) {
        if (keyCharacteristics.hwEnforced.containsTag(tag)) {
            return keyCharacteristics.hwEnforced.getBoolean(tag, false);
        } else {
            return keyCharacteristics.swEnforced.getBoolean(tag, false);
        }
    }
}
