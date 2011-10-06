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

import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.util.LinkedList;
import java.util.ListIterator;

/**
 * SslErrorHandler's implementation for Android Java HTTP stack.
 * This class is not needed if the Chromium HTTP stack is used.
 */
class SslErrorHandlerImpl extends SslErrorHandler {
    /* One problem here is that there may potentially be multiple SSL errors
     * coming from multiple loaders. Therefore, we keep a queue of loaders
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

    // Message id for handling the response from the client.
    private static final int HANDLE_RESPONSE = 100;

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case HANDLE_RESPONSE:
                LoadListener loader = (LoadListener) msg.obj;
                synchronized (SslErrorHandlerImpl.this) {
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
    /* package */ SslErrorHandlerImpl() {
        mLoaderQueue = new LinkedList<LoadListener>();
        mSslPrefTable = new Bundle();

        // These are used by client facing SslErrorHandlers.
        mOriginHandler = null;
        mLoadListener = null;
    }

    /**
     * Create a new error handler that will be passed to the client.
     */
    private SslErrorHandlerImpl(SslErrorHandler origin, LoadListener listener) {
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
     * Handles requests from the network stack about whether to proceed with a
     * load given an SSL error(s). We may ask the client what to do, or use a
     * cached response.
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
     * Check the preference table to see if we already have a 'proceed' decision
     * from the client for this host and for an error of equal or greater
     * severity than the supplied error. If so, instruct the loader to proceed
     * and return true. Otherwise return false.
     */
    /* package */ synchronized boolean checkSslPrefTable(LoadListener loader,
            SslError error) {
        final String host = loader.host();
        final int primary = error.getPrimaryError();

        if (DebugFlags.SSL_ERROR_HANDLER) {
            assert host != null;
            assert primary != -1;
        }

        if (mSslPrefTable.containsKey(host) && primary <= mSslPrefTable.getInt(host)) {
            if (!loader.cancelled()) {
                loader.handleSslErrorResponse(true);
            }
            return true;
        }
        return false;
    }

    /**
     * Processes queued SSL-error confirmation requests in
     * a tight loop while there is no need to ask the client.
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
                assert error != null;
            }

            // checkSslPrefTable() will instruct the loader to proceed if we
            // have a cached 'proceed' decision. It does not remove the loader
            // from the queue.
            if (checkSslPrefTable(loader, error)) {
                mLoaderQueue.remove(loader);
                return true;
            }

            // If we can not proceed based on a cached decision, ask the client.
            CallbackProxy proxy = loader.getFrame().getCallbackProxy();
            proxy.onReceivedSslError(new SslErrorHandlerImpl(this, loader), error);
        }

        // the queue must be empty, stop
        return false;
    }

    /**
     * Proceed with this load.
     */
    public void proceed() {
        mOriginHandler.sendMessage(mOriginHandler.obtainMessage(
                HANDLE_RESPONSE, 1, 0, mLoadListener));
    }

    /**
     * Cancel this load and all pending loads for the WebView that had the
     * error.
     */
    public void cancel() {
        mOriginHandler.sendMessage(mOriginHandler.obtainMessage(
                HANDLE_RESPONSE, 0, 0, mLoadListener));
    }

    /**
     * Handles the response from the client about whether to proceed with this
     * load. We save the response to be re-used in the future.
     */
    /* package */ synchronized void handleSslErrorResponse(LoadListener loader,
            SslError error, boolean proceed) {
        if (DebugFlags.SSL_ERROR_HANDLER) {
            assert loader != null;
            assert error != null;
        }

        if (DebugFlags.SSL_ERROR_HANDLER) {
            Log.v(LOGTAG, "SslErrorHandler.handleSslErrorResponse():"
                  + " proceed: " + proceed
                  + " url:" + loader.url());
        }

        if (!loader.cancelled()) {
            if (proceed) {
                // Update the SSL error preference table
                int primary = error.getPrimaryError();
                String host = loader.host();

                if (DebugFlags.SSL_ERROR_HANDLER) {
                    assert host != null;
                    assert primary != -1;
                }
                boolean hasKey = mSslPrefTable.containsKey(host);
                if (!hasKey || primary > mSslPrefTable.getInt(host)) {
                    mSslPrefTable.putInt(host, primary);
                }
            }
            loader.handleSslErrorResponse(proceed);
        }
    }
}
