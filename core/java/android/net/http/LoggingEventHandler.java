/*
 * Copyright (C) 2006 The Android Open Source Project
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

/**
 * A test EventHandler: Logs everything received
 */

package android.net.http;

import android.net.http.Headers;

/**
 * {@hide}
 */
public class LoggingEventHandler implements EventHandler {

    public void requestSent() {
        HttpLog.v("LoggingEventHandler:requestSent()");
    }

    public void status(int major_version,
                       int minor_version,
                       int code, /* Status-Code value */
                       String reason_phrase) {
        if (HttpLog.LOGV) {
            HttpLog.v("LoggingEventHandler:status() major: " + major_version +
                  " minor: " + minor_version +
                  " code: " + code +
                  " reason: " + reason_phrase);
        }
    }

    public void headers(Headers headers) {
        if (HttpLog.LOGV) {
            HttpLog.v("LoggingEventHandler:headers()");
            HttpLog.v(headers.toString());
        }
    }

    public void locationChanged(String newLocation, boolean permanent) {
        if (HttpLog.LOGV) {
            HttpLog.v("LoggingEventHandler: locationChanged() " + newLocation +
                      " permanent " + permanent);
        }
    }

    public void data(byte[] data, int len) {
        if (HttpLog.LOGV) {
            HttpLog.v("LoggingEventHandler: data() " + len + " bytes");
        }
        // HttpLog.v(new String(data, 0, len));
    }
    public void endData() {
        if (HttpLog.LOGV) {
            HttpLog.v("LoggingEventHandler: endData() called");
        }
    }

    public void certificate(SslCertificate certificate) {
         if (HttpLog.LOGV) {
             HttpLog.v("LoggingEventHandler: certificate(): " + certificate);
         }
    }

    public void error(int id, String description) {
        if (HttpLog.LOGV) {
            HttpLog.v("LoggingEventHandler: error() called Id:" + id +
                      " description " + description);
        }
    }

    public boolean handleSslErrorRequest(SslError error) {
        if (HttpLog.LOGV) {
            HttpLog.v("LoggingEventHandler: handleSslErrorRequest():" + error);
        }
        // return false so that the caller thread won't wait forever
        return false;
    }
}
