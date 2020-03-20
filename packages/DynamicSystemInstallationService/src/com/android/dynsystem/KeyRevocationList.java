/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.dynsystem;

import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;

class KeyRevocationList {

    private static final String TAG = "KeyRevocationList";

    private static final String JSON_ENTRIES = "entries";
    private static final String JSON_PUBLIC_KEY = "public_key";
    private static final String JSON_STATUS = "status";
    private static final String JSON_REASON = "reason";

    private static final String STATUS_REVOKED = "REVOKED";

    @VisibleForTesting
    HashMap<String, RevocationStatus> mEntries;

    static class RevocationStatus {
        final String mStatus;
        final String mReason;

        RevocationStatus(String status, String reason) {
            mStatus = status;
            mReason = reason;
        }
    }

    KeyRevocationList() {
        mEntries = new HashMap<String, RevocationStatus>();
    }

    /**
     * Returns the revocation status of a public key.
     *
     * @return a RevocationStatus for |publicKey|, null if |publicKey| doesn't exist.
     */
    RevocationStatus getRevocationStatusForKey(String publicKey) {
        return mEntries.get(publicKey);
    }

    /** Test if a public key is revoked or not. */
    boolean isRevoked(String publicKey) {
        RevocationStatus entry = getRevocationStatusForKey(publicKey);
        return entry != null && TextUtils.equals(entry.mStatus, STATUS_REVOKED);
    }

    @VisibleForTesting
    void addEntry(String publicKey, String status, String reason) {
        mEntries.put(publicKey, new RevocationStatus(status, reason));
    }

    /**
     * Creates a KeyRevocationList from a JSON String.
     *
     * @param jsonString the revocation list, for example:
     *     <pre>{@code
     *      {
     *        "entries": [
     *          {
     *            "public_key": "00fa2c6637c399afa893fe83d85f3569998707d5",
     *            "status": "REVOKED",
     *            "reason": "Revocation Reason"
     *          }
     *        ]
     *      }
     *     }</pre>
     *
     * @throws JSONException if |jsonString| is malformed.
     */
    static KeyRevocationList fromJsonString(String jsonString) throws JSONException {
        JSONObject jsonObject = new JSONObject(jsonString);
        KeyRevocationList list = new KeyRevocationList();
        Log.d(TAG, "Begin of revocation list");
        if (jsonObject.has(JSON_ENTRIES)) {
            JSONArray entries = jsonObject.getJSONArray(JSON_ENTRIES);
            for (int i = 0; i < entries.length(); ++i) {
                JSONObject entry = entries.getJSONObject(i);
                String publicKey = entry.getString(JSON_PUBLIC_KEY);
                String status = entry.getString(JSON_STATUS);
                String reason = entry.has(JSON_REASON) ? entry.getString(JSON_REASON) : "";
                list.addEntry(publicKey, status, reason);
                Log.d(TAG, "Revocation entry: " + entry.toString());
            }
        }
        Log.d(TAG, "End of revocation list");
        return list;
    }

    /**
     * Creates a KeyRevocationList from a URL.
     *
     * @throws IOException if |url| is inaccessible.
     * @throws JSONException if fetched content is malformed.
     */
    static KeyRevocationList fromUrl(URL url) throws IOException, JSONException {
        Log.d(TAG, "Fetch from URL: " + url.toString());
        // Force "conditional GET"
        // Force validate the cached result with server each time, and use the cached result
        // only if it is validated by server, else fetch new data from server.
        // Ref: https://developer.android.com/reference/android/net/http/HttpResponseCache#force-a-network-response
        URLConnection connection = url.openConnection();
        connection.setUseCaches(true);
        connection.addRequestProperty("Cache-Control", "max-age=0");
        try (InputStream stream = connection.getInputStream()) {
            return fromJsonString(readFully(stream));
        }
    }

    private static String readFully(InputStream in) throws IOException {
        int n;
        byte[] buffer = new byte[4096];
        StringBuilder builder = new StringBuilder();
        while ((n = in.read(buffer, 0, 4096)) > -1) {
            builder.append(new String(buffer, 0, n));
        }
        return builder.toString();
    }
}
