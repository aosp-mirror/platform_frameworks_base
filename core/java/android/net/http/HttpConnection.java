/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.net.http;

import android.content.Context;

import java.net.Socket;
import java.io.IOException;

import org.apache.http.HttpHost;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;

/**
 * A requestConnection connecting to a normal (non secure) http server
 * 
 * {@hide}
 */
class HttpConnection extends Connection {

    HttpConnection(Context context, HttpHost host,
                   RequestFeeder requestFeeder) {
        super(context, host, requestFeeder);
    }

    /**
     * Opens the connection to a http server
     *
     * @return the opened low level connection
     * @throws IOException if the connection fails for any reason.
     */
    @Override
    AndroidHttpClientConnection openConnection(Request req) throws IOException {

        // Update the certificate info (connection not secure - set to null)
        EventHandler eventHandler = req.getEventHandler();
        mCertificate = null;
        eventHandler.certificate(mCertificate);

        AndroidHttpClientConnection conn = new AndroidHttpClientConnection();
        BasicHttpParams params = new BasicHttpParams();
        Socket sock = new Socket(mHost.getHostName(), mHost.getPort());
        params.setIntParameter(HttpConnectionParams.SOCKET_BUFFER_SIZE, 8192);
        conn.bind(sock, params);
        return conn;
    }

    /**
     * Closes the low level connection.
     *
     * If an exception is thrown then it is assumed that the
     * connection will have been closed (to the extent possible)
     * anyway and the caller does not need to take any further action.
     *
     */
    void closeConnection() {
        try {
            if (mHttpClientConnection != null && mHttpClientConnection.isOpen()) {
                mHttpClientConnection.close();
            }
        } catch (IOException e) {
            if (HttpLog.LOGV) HttpLog.v(
                    "closeConnection(): failed closing connection " +
                    mHost);
            e.printStackTrace();
        }
    }

    /**
     * Restart a secure connection suspended waiting for user interaction.
     */
    void restartConnection(boolean abort) {
        // not required for plain http connections
    }

    String getScheme() {
        return "http";
    }
}
