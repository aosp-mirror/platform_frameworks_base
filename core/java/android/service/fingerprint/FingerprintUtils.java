/**
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.fingerprint;

import android.content.ContentResolver;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;

/**
 * Utility class for dealing with fingerprints and fingerprint settings.
 * @hide
 */
public
class FingerprintUtils {
    private static final boolean DEBUG = true;
    private static final String TAG = "FingerprintUtils";

    public static int[] getFingerprintIdsForUser(ContentResolver res, int userId) {
        String fingerIdsRaw = Settings.Secure.getStringForUser(res,
                Settings.Secure.USER_FINGERPRINT_IDS, userId);

        int result[] = {};
        if (!TextUtils.isEmpty(fingerIdsRaw)) {
            String[] fingerStringIds = fingerIdsRaw.replace("[","").replace("]","").split(", ");
            result = new int[fingerStringIds.length];
            for (int i = 0; i < result.length; i++) {
                try {
                    result[i] = Integer.decode(fingerStringIds[i]);
                } catch (NumberFormatException e) {
                    if (DEBUG) Log.d(TAG, "Error when parsing finger id " + fingerStringIds[i]);
                }
            }
        }
        return result;
    }

    public static void addFingerprintIdForUser(int fingerId, ContentResolver res, int userId) {
        int[] fingerIds = getFingerprintIdsForUser(res, userId);

        // FingerId 0 has special meaning.
        if (fingerId == 0) return;

        // Don't allow dups
        for (int i = 0; i < fingerIds.length; i++) {
            if (fingerIds[i] == fingerId) return;
        }
        int[] newList = Arrays.copyOf(fingerIds, fingerIds.length + 1);
        newList[fingerIds.length] = fingerId;
        Settings.Secure.putStringForUser(res, Settings.Secure.USER_FINGERPRINT_IDS,
                Arrays.toString(newList), userId);
    }

    public static boolean removeFingerprintIdForUser(int fingerId, ContentResolver res, int userId)
    {
        // FingerId 0 has special meaning. The HAL layer is supposed to remove each finger one
        // at a time and invoke notify() for each fingerId.  If we get called with 0 here, it means
        // something bad has happened.
        if (fingerId == 0) throw new IllegalStateException("Bad fingerId");

        int[] fingerIds = getFingerprintIdsForUser(res, userId);
        int[] resultIds = Arrays.copyOf(fingerIds, fingerIds.length);
        int resultCount = 0;
        for (int i = 0; i < fingerIds.length; i++) {
            if (fingerId != fingerIds[i]) {
                resultIds[resultCount++] = fingerIds[i];
            }
        }
        if (resultCount > 0) {
            Settings.Secure.putStringForUser(res, Settings.Secure.USER_FINGERPRINT_IDS,
                    Arrays.toString(Arrays.copyOf(resultIds, resultCount)), userId);
            return true;
        }
        return false;
    }

};

