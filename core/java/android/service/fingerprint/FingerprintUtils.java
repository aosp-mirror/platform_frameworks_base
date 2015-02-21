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

import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility class for dealing with fingerprints and fingerprint settings.
 * @hide
 */
public
class FingerprintUtils {
    private static final boolean DEBUG = true;
    private static final String TAG = "FingerprintUtils";

    private static int[] toIntArray(List<Integer> list) {
        if (list == null) {
            return null;
        }
        int[] arr = new int[list.size()];
        int i = 0;
        for (int elem : list) {
            arr[i] = elem;
            i++;
        }
        return arr;
    }

    public static int[] getFingerprintIdsForUser(ContentResolver res, int userId) {
        String fingerIdsRaw = Settings.Secure.getStringForUser(res,
                Settings.Secure.USER_FINGERPRINT_IDS, userId);
        ArrayList<Integer> tmp = new ArrayList<Integer>();
        if (!TextUtils.isEmpty(fingerIdsRaw)) {
            String[] fingerStringIds = fingerIdsRaw.replace("[","").replace("]","").split(", ");
            int length = fingerStringIds.length;
            for (int i = 0; i < length; i++) {
                try {
                    tmp.add(Integer.decode(fingerStringIds[i]));
                } catch (NumberFormatException e) {
                    if (DEBUG) Log.w(TAG, "Error parsing finger id: '" + fingerStringIds[i] + "'");
                }
            }
        }
        return toIntArray(tmp);
    }

    public static void addFingerprintIdForUser(int fingerId, ContentResolver res, int userId) {
        // FingerId 0 has special meaning.
        if (fingerId == 0) {
            Log.w(TAG, "Tried to add fingerId 0");
            return;
        }

        int[] fingerIds = getFingerprintIdsForUser(res, userId);

        // Don't allow dups
        if (ArrayUtils.contains(fingerIds, fingerId)) {
            Log.w(TAG, "finger already added " + fingerId);
            return;
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
        if (fingerId == 0) throw new IllegalArgumentException("fingerId can't be 0");

        final int[] fingerIds = getFingerprintIdsForUser(res, userId);
        if (ArrayUtils.contains(fingerIds, fingerId)) {
            final int[] result = ArrayUtils.removeInt(fingerIds, fingerId);
            Settings.Secure.putStringForUser(res, Settings.Secure.USER_FINGERPRINT_IDS,
                    Arrays.toString(result), userId);
            return true;
        }
        return false;
    }

};

