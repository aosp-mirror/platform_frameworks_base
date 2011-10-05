/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.webkit;

import android.os.Bundle;
import android.net.http.SslError;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Stores the user's decision of whether to allow or deny an invalid certificate.
 *
 * This class is not threadsafe. It is used only on the WebCore thread.
 */
final class SslCertLookupTable {
    private static SslCertLookupTable sTable;
    private final Bundle table;

    public static SslCertLookupTable getInstance() {
        if (sTable == null) {
            sTable = new SslCertLookupTable();
        }
        return sTable;
    }

    private SslCertLookupTable() {
        table = new Bundle();
    }

    public void setIsAllowed(SslError sslError, boolean allow) {
        // TODO: We should key on just the host. See http://b/5409251.
        String errorString = sslErrorToString(sslError);
        if (errorString != null) {
            table.putBoolean(errorString, allow);
        }
    }

    public boolean isAllowed(SslError sslError) {
        // TODO: We should key on just the host. See http://b/5409251.
        String errorString = sslErrorToString(sslError);
        return errorString == null ? false : table.getBoolean(errorString);
    }

    public void clear() {
        table.clear();
    }

    private static String sslErrorToString(SslError error) {
        String host;
        try {
            host = new URL(error.getUrl()).getHost();
        } catch(MalformedURLException e) {
            return null;
        }
        return "primary error: " + error.getPrimaryError() +
                " certificate: " + error.getCertificate() +
                " on host: " + host;
    }
}
