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

package android.webkit;

import junit.framework.Assert;

import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.util.LinkedList;
import java.util.ListIterator;

/**
 * SslErrorHandler: class responsible for handling SSL errors. This class is
 * passed as a parameter to BrowserCallback.displaySslErrorDialog and is meant
 * to receive the user's response.
 */
public class SslErrorHandler extends Handler {
    /* One problem here is that there may potentially be multiple SSL errors
     * coming from mutiple loaders. Therefore, we keep a queue of loaders
     * that have SSL-related problems and process errors one by one in the
     * order they were received.
     */

    private static final String LOGTAG = "network";

    /**
     * Queue of loaders that experience SSL-related problems.
     */
    private LinkedList<LoadListener> mLoaderQueue;

    /**
     * SSL error preference table.
     */
    private Bundle mSslPrefTable;

    // These are only used in the client facing SslErrorHandler.
    private final SslErrorHandler mOriginHandler;
    private final LoadListener mLoadListener;

    // Message id for handling the response
    private static final int HANDLE_RESPONSE = 100;

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case HANDLE_RESPONSE:
                LoadListener loader = (LoadListener) msg.obj;
                synchronized (SslErrorHandler.this) {
                    handleSslErrorResponse(loader, loader.sslError(),
                            msg.arg1 == 1);
                    mLoaderQueue.remove(loader);
                    fastProcessQueuedSslErrors();
                }
                break;
        }
    }

    /**
     * Creates a new error handler with an empty loader queue.
     */
    /* package */ SslErrorHandler() {
        mLoaderQueue = new LinkedList<LoadListener>();
        mSslPrefTable = new Bundle();

        // These are used by client facing SslErrorHandlers.
        mOriginHandler = null;
        mLoadListener = null;
    }

    /**
     * Create a new error handler that will be passed to the client.
     */
    private SslErrorHandler(SslErrorHandler origin, LoadListener listener) {
        mOriginHandler = origin;
        mLoadListener = listener;
    }

    /**
     * Saves this handler's state into a map.
     * @return True iff succeeds.
     */
    /* package */ synchronized boolean saveState(Bundle outState) {
        boolean success = (outState != null);
        if (success) {
            // TODO?
            outState.putBundle("ssl-error-handler", mSslPrefTable);
        }

        return success;
    }

    /**
     * Restores this handler's state from a map.
     * @return True iff succeeds.
     */
    /* package */ synchronized boolean restoreState(Bundle inState) {
        boolean success = (inState != null);
        if (success) {
            success = inState.containsKey("ssl-error-handler");
            if (success) {
                mSslPrefTable = inState.getBundle("ssl-error-handler");
            }
        }

        return success;
    }

    /**
     * Clears SSL error preference table.
     */
    /* package */ synchronized void clear() {
        mSslPrefTable.clear();
    }

    /**
     * Handles SSL error(s) on the way up to the user.
     */
    /* package */ synchronized void handleSslErrorRequest(LoadListener loader) {
        if (DebugFlags.SSL_ERROR_HANDLER) {
            Log.v(LOGTAG, "SslErrorHandler.handleSslErrorRequest(): " +
                  "url=" + loader.url());
        }

        if (!loader.cancelled()) {
            mLoaderQueue.offer(loader);
            if (loader == mLoaderQueue.peek()) {
                fastProcessQueuedSslErrors();
            }
        }
    }

    /**
     * Check the preference table for a ssl error that has already been shown
     * to the user.
     */
    /* package */ synchronized boolean checkSslPrefTable(LoadListener loader,
            SslError error) {
        final String host = loader.host();
        final int primary = error.getPrimaryError();

        if (DebugFlags.SSL_ERROR_HANDLER) {
            Assert.assertTrue(host != null && primary != 0);
        }

        if (mSslPrefTable.containsKey(host)) {
            if (primary <= mSslPrefTable.getInt(host)) {
                handleSslErrorResponse(loader, error, true);
                return true;
            }
        }
        return false;
    }

    /**
     * Processes queued SSL-error confirmation requests in
     * a tight loop while there is no need to ask the user.
     */
    /* package */void fastProcessQueuedSslErrors() {
        while (processNextLoader());
    }

    /**
     * Processes the next loader in the queue.
     * @return True iff should proceed to processing the
     * following loader in the queue
     */
    private synchronized boolean processNextLoader() {
        LoadListener loader = mLoaderQueue.peek();
        if (loader != null) {
            // if this loader has been cancelled
            if (loader.cancelled()) {
                // go to the following loader in the queue. Make sure this
                // loader has been removed from the queue.
                mLoaderQueue.remove(loader);
                return true;
            }

            SslError error = loader.sslError();

            if (DebugFlags.SSL_ERROR_HANDLER) {
                Assert.assertNotNull(error);
            }

            // checkSslPrefTable will handle the ssl error response if the
            // answer is available. It does not remove the loader from the
            // queue.
            if (checkSslPrefTable(loader, error)) {
                mLoaderQueue.remove(loader);
                return true;
            }

            // if we do not have information on record, ask
            // the user (display a dialog)
            CallbackProxy proxy = loader.getFrame().getCallbackProxy();
            proxy.onReceivedSslError(new SslErrorHandler(this, loader), error);
        }

        // the queue must be empty, stop
        return false;
    }

    /**
     * Proceed with the SSL certificate.
     */
    public void proceed() {
        mOriginHandler.sendMessage(
                mOriginHandler.obtainMessage(
                        HANDLE_RESPONSE, 1, 0, mLoadListener));
    }

    /**
     * Cancel this request and all pending requests for the WebView that had
     * the error.
     */
    public void cancel() {
        mOriginHandler.sendMessage(
                mOriginHandler.obtainMessage(
                        HANDLE_RESPONSE, 0, 0, mLoadListener));
    }

    /**
     * Handles SSL error(s) on the way down from the user.
     */
    /* package */ synchronized void handleSslErrorResponse(LoadListener loader,
            SslError error, boolean proceed) {
        if (DebugFlags.SSL_ERROR_HANDLER) {
            Assert.assertNotNull(loader);
            Assert.assertNotNull(error);
        }

        if (DebugFlags.SSL_ERROR_HANDLER) {
            Log.v(LOGTAG, "SslErrorHandler.handleSslErrorResponse():"
                  + " proceed: " + proceed
                  + " url:" + loader.url());
        }

        if (!loader.cancelled()) {
            if (proceed) {
                // update the user's SSL error preference table
                int primary = error.getPrimaryError();
                String host = loader.host();

                if (DebugFlags.SSL_ERROR_HANDLER) {
                    Assert.assertTrue(host != null && primary != 0);
                }
                boolean hasKey = mSslPrefTable.containsKey(host);
                if (!hasKey ||
                    primary > mSslPrefTable.getInt(host)) {
                    mSslPrefTable.putInt(host, primary);
                }
            }
            loader.handleSslErrorResponse(proceed);
        }
    }
}
