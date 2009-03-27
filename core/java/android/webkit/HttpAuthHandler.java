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

package android.webkit;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import java.util.ListIterator;
import java.util.LinkedList;

/**
 * HTTP authentication handler: local handler that takes care
 * of HTTP authentication requests. This class is passed as a
 * parameter to BrowserCallback.displayHttpAuthDialog and is
 * meant to receive the user's response.
 */
public class HttpAuthHandler extends Handler {
    /* It is important that the handler is in Network, because
     * we want to share it accross multiple loaders and windows
     * (like our subwindow and the main window).
     */

    private static final String LOGTAG = "network";

    /**
     * Network.
     */
    private Network mNetwork;

    /**
     * Loader queue.
     */
    private LinkedList<LoadListener> mLoaderQueue;


    // Message id for handling the user response
    private final int AUTH_PROCEED = 100;
    private final int AUTH_CANCEL = 200;

    /**
     * Creates a new HTTP authentication handler with an empty
     * loader queue
     *
     * @param network The parent network object
     */
    /* package */ HttpAuthHandler(Network network) {
        mNetwork = network;
        mLoaderQueue = new LinkedList<LoadListener>();
    }


    @Override
    public void handleMessage(Message msg) {
        LoadListener loader = null;
        synchronized (mLoaderQueue) {
            loader = mLoaderQueue.poll();
        }

        switch (msg.what) {
            case AUTH_PROCEED:
                String username = msg.getData().getString("username");
                String password = msg.getData().getString("password");

                loader.handleAuthResponse(username, password);
                break;

            case AUTH_CANCEL:
                loader.handleAuthResponse(null, null);
                break;
        }

        processNextLoader();
    }


    /**
     * Proceed with the authorization with the given credentials
     *
     * @param username The username to use for authentication
     * @param password The password to use for authentication
     */
    public void proceed(String username, String password) {
        Message msg = obtainMessage(AUTH_PROCEED);
        msg.getData().putString("username", username);
        msg.getData().putString("password", password);
        sendMessage(msg);
    }

    /**
     * Cancel the authorization request
     */
    public void cancel() {
        sendMessage(obtainMessage(AUTH_CANCEL));
    }

    /**
     * @return True if we can use user credentials on record
     * (ie, if we did not fail trying to use them last time)
     */
    public boolean useHttpAuthUsernamePassword() {
        LoadListener loader = null;
        synchronized (mLoaderQueue) {
            loader = mLoaderQueue.peek();
        }
        if (loader != null) {
            return !loader.authCredentialsInvalid();
        }

        return false;
    }

    /**
     * Enqueues the loader, if the loader is the only element
     * in the queue, starts processing the loader
     *
     * @param loader The loader that resulted in this http
     * authentication request
     */
    /* package */ void handleAuthRequest(LoadListener loader) {
        boolean processNext = false;

        synchronized (mLoaderQueue) {
            mLoaderQueue.offer(loader);
            processNext =
                (mLoaderQueue.size() == 1);
        }

        if (processNext) {
            processNextLoader();
        }
    }

    /**
     * Process the next loader in the queue (helper method)
     */
    private void processNextLoader() {
        LoadListener loader = null;
        synchronized (mLoaderQueue) {
            loader = mLoaderQueue.peek();
        }
        if (loader != null) {
            CallbackProxy proxy = loader.getFrame().getCallbackProxy();

            String hostname = loader.proxyAuthenticate() ?
                mNetwork.getProxyHostname() : loader.host();

            String realm = loader.realm();

            proxy.onReceivedHttpAuthRequest(this, hostname, realm);
        }
    }
}
