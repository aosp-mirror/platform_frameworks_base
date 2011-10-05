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
 * This class is not threadsafe. It is used only on the WebCore thread. Also, it
 * is used only by the Chromium HTTP stack.
 */
final class SslCertLookupTable {
    private static SslCertLookupTable sTable;
    // We store the most severe error we're willing to allow for each host.
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

    public void setIsAllowed(SslError sslError) {
        String host;
        try {
            host = new URL(sslError.getUrl()).getHost();
        } catch(MalformedURLException e) {
            return;
        }
        table.putInt(host, sslError.getPrimaryError());
    }

    // We allow the decision to be re-used if it's for the same host and is for
    // an error of equal or greater severity than this error.
    public boolean isAllowed(SslError sslError) {
        String host;
        try {
            host = new URL(sslError.getUrl()).getHost();
        } catch(MalformedURLException e) {
            return false;
        }
        return table.containsKey(host) && sslError.getPrimaryError() <= table.getInt(host);
    }

    public void clear() {
        table.clear();
    }
}
