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
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.util.ListIterator;
import java.util.LinkedList;

/**
 * HttpAuthHandler implementation is used only by the Android Java HTTP stack.
 * <p>
 * This class is not needed when we're using the Chromium HTTP stack.
 */
class HttpAuthHandlerImpl extends HttpAuthHandler {
    /*
     * It is important that the handler is in Network, because we want to share
     * it accross multiple loaders and windows (like our subwindow and the main
     * window).
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
    private static final int AUTH_PROCEED = 100;
    private static final int AUTH_CANCEL = 200;

    // Use to synchronize when making synchronous calls to
    // onReceivedHttpAuthRequest(). We can't use a single Boolean object for
    // both the lock and the state, because Boolean is immutable.
    Object mRequestInFlightLock = new Object();
    boolean mRequestInFlight;
    String mUsername;
    String mPassword;

    /**
     * Creates a new HTTP authentication handler with an empty
     * loader queue
     *
     * @param network The parent network object
     */
    /* package */ HttpAuthHandlerImpl(Network network) {
        mNetwork = network;
        mLoaderQueue = new LinkedList<LoadListener>();
    }


    @Override
    public void handleMessage(Message msg) {
        LoadListener loader = null;
        synchronized (mLoaderQueue) {
            loader = mLoaderQueue.poll();
        }
        assert(loader.isSynchronous() == false);

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
     * Helper method used to unblock handleAuthRequest(), which in the case of a
     * synchronous request will wait for proxy.onReceivedHttpAuthRequest() to
     * call back to either proceed() or cancel().
     *
     * @param username The username to use for authentication
     * @param password The password to use for authentication
     * @return True if the request is synchronous and handleAuthRequest() has
     * been unblocked
     */
    private boolean handleResponseForSynchronousRequest(String username, String password) {
        LoadListener loader = null;
        synchronized (mLoaderQueue) {
            loader = mLoaderQueue.peek();
        }
        if (loader.isSynchronous()) {
            mUsername = username;
            mPassword = password;
            return true;
        }
        return false;
    }

    private void signalRequestComplete() {
        synchronized (mRequestInFlightLock) {
            assert(mRequestInFlight);
            mRequestInFlight = false;
            mRequestInFlightLock.notify();
        }
    }

    /**
     * Proceed with the authorization with the given credentials
     *
     * May be called on the UI thread, rather than the WebCore thread.
     *
     * @param username The username to use for authentication
     * @param password The password to use for authentication
     */
    public void proceed(String username, String password) {
        if (handleResponseForSynchronousRequest(username, password)) {
            signalRequestComplete();
            return;
        }
        Message msg = obtainMessage(AUTH_PROCEED);
        msg.getData().putString("username", username);
        msg.getData().putString("password", password);
        sendMessage(msg);
        signalRequestComplete();
    }

    /**
     * Cancel the authorization request
     *
     * May be called on the UI thread, rather than the WebCore thread.
     *
     */
    public void cancel() {
        if (handleResponseForSynchronousRequest(null, null)) {
            signalRequestComplete();
            return;
        }
        sendMessage(obtainMessage(AUTH_CANCEL));
        signalRequestComplete();
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
        // The call to proxy.onReceivedHttpAuthRequest() may be asynchronous. If
        // the request is synchronous, we must block here until we have a
        // response.
        if (loader.isSynchronous()) {
            // If there's a request in flight, wait for it to complete. The
            // response will queue a message on this thread.
            waitForRequestToComplete();
            // Make a request to the proxy for this request, jumping the queue.
            // We use the queue so that the loader is present in
            // useHttpAuthUsernamePassword().
            synchronized (mLoaderQueue) {
                mLoaderQueue.addFirst(loader);
            }
            processNextLoader();
            // Wait for this request to complete.
            waitForRequestToComplete();
            // Pop the loader from the queue.
            synchronized (mLoaderQueue) {
                assert(mLoaderQueue.peek() == loader);
                mLoaderQueue.poll();
            }
            // Call back.
            loader.handleAuthResponse(mUsername, mPassword);
            // The message queued by the response from the last asynchronous
            // request, if present, will start the next request.
            return;
        }

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
     * Wait for the request in flight, if any, to complete
     */
    private void waitForRequestToComplete() {
        synchronized (mRequestInFlightLock) {
            while (mRequestInFlight) {
                try {
                    mRequestInFlightLock.wait();
                } catch(InterruptedException e) {
                    Log.e(LOGTAG, "Interrupted while waiting for request to complete");
                }
            }
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
            synchronized (mRequestInFlightLock) {
                assert(mRequestInFlight == false);
                mRequestInFlight = true;
            }

            CallbackProxy proxy = loader.getFrame().getCallbackProxy();

            String hostname = loader.proxyAuthenticate() ?
                mNetwork.getProxyHostname() : loader.host();

            String realm = loader.realm();

            proxy.onReceivedHttpAuthRequest(this, hostname, realm);
        }
    }

    /**
     * Informs the WebView of a new set of credentials.
     * @hide Pending API council review
     */
    public static void onReceivedCredentials(LoadListener loader,
            String host, String realm, String username, String password) {
        CallbackProxy proxy = loader.getFrame().getCallbackProxy();
        proxy.onReceivedHttpAuthCredentials(host, realm, username, password);
    }
}
