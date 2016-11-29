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
package com.android.internal.logging.legacy;

import android.util.Log;

/**
 * Parse Android notification keys
 * @hide
 */
public class NotificationKey {

    private static final String TAG = "NotificationKey";

    public int mUser;
    public String mPackageName;
    public int mId;
    public String mTag;
    public int mUid;

    public boolean parse(String key) {
        if (key == null) {
            return false;
        }
        boolean debug = Util.debug();
        String[] parts = key.split("\\|");
        if (parts.length == 5) {
            try {
                mUser = Integer.valueOf(parts[0]);
                mPackageName = parts[1];
                mId = Integer.valueOf(parts[2]);
                mTag = parts[3].equals("null") ? "" : parts[3];
                mUid = Integer.valueOf(parts[4]);
                return true;
            } catch (NumberFormatException e) {
                if (debug) {
                    Log.w(TAG, "could not parse notification key.", e);
                }
                return false;
            }
        }
        if (debug) {
            Log.w(TAG, "wrong number of parts in notification key: " + key);
        }
        return false;
    }
}
