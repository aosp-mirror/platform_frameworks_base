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

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Message;
import android.provider.Browser;
import android.util.Log;

import java.util.HashMap;
import java.util.Vector;

/**
 * Functions for manipulating the icon database used by WebView.
 * These functions require that a WebView be constructed before being invoked
 * and WebView.getIconDatabase() will return a WebIconDatabase object. This
 * WebIconDatabase object is a single instance and all methods operate on that
 * single object.
 */
public final class WebIconDatabase {
    private static final String LOGTAG = "WebIconDatabase";
    // Global instance of a WebIconDatabase
    private static WebIconDatabase sIconDatabase;
    // EventHandler for handling messages before and after the WebCore thread is
    // ready.
    private final EventHandler mEventHandler = new EventHandler();

    // Class to handle messages before WebCore is ready
    private static class EventHandler extends Handler {
        // Message ids
        static final int OPEN         = 0;
        static final int CLOSE        = 1;
        static final int REMOVE_ALL   = 2;
        static final int REQUEST_ICON = 3;
        static final int RETAIN_ICON  = 4;
        static final int RELEASE_ICON = 5;
        static final int BULK_REQUEST_ICON = 6;
        // Message for dispatching icon request results
        private static final int ICON_RESULT = 10;
        // Actual handler that runs in WebCore thread
        private Handler mHandler;
        // Vector of messages before the WebCore thread is ready
        private Vector<Message> mMessages = new Vector<Message>();
        // Class to handle a result dispatch
        private class IconResult {
            private final String mUrl;
            private final Bitmap mIcon;
            private final IconListener mListener;
            IconResult(String url, Bitmap icon, IconListener l) {
                mUrl = url;
                mIcon = icon;
                mListener = l;
            }
            void dispatch() {
                mListener.onReceivedIcon(mUrl, mIcon);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            // Note: This is the message handler for the UI thread.
            switch (msg.what) {
                case ICON_RESULT:
                    ((IconResult) msg.obj).dispatch();
                    break;
            }
        }

        // Called by WebCore thread to create the actual handler
        private synchronized void createHandler() {
            if (mHandler == null) {
                mHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        // Note: This is the message handler for the WebCore
                        // thread.
                        switch (msg.what) {
                            case OPEN:
                                nativeOpen((String) msg.obj);
                                break;

                            case CLOSE:
                                nativeClose();
                                break;

                            case REMOVE_ALL:
                                nativeRemoveAllIcons();
                                break;

                            case REQUEST_ICON:
                                IconListener l = (IconListener) msg.obj;
                                String url = msg.getData().getString("url");
                                requestIconAndSendResult(url, l);
                                break;

                            case BULK_REQUEST_ICON:
                                bulkRequestIcons(msg);
                                break;

                            case RETAIN_ICON:
                                nativeRetainIconForPageUrl((String) msg.obj);
                                break;

                            case RELEASE_ICON:
                                nativeReleaseIconForPageUrl((String) msg.obj);
                                break;
                        }
                    }
                };
                // Transfer all pending messages
                for (int size = mMessages.size(); size > 0; size--) {
                    mHandler.sendMessage(mMessages.remove(0));
                }
                mMessages = null;
            }
        }

        private synchronized boolean hasHandler() {
            return mHandler != null;
        }

        private synchronized void postMessage(Message msg) {
            if (mMessages != null) {
                mMessages.add(msg);
            } else {
                mHandler.sendMessage(msg);
            }
        }

        private void bulkRequestIcons(Message msg) {
            HashMap map = (HashMap) msg.obj;
            IconListener listener = (IconListener) map.get("listener");
            ContentResolver cr = (ContentResolver) map.get("contentResolver");
            String where = (String) map.get("where");

            Cursor c = null;
            try {
                c = cr.query(
                        Browser.BOOKMARKS_URI,
                        new String[] { Browser.BookmarkColumns.URL },
                        where, null, null);
                if (c.moveToFirst()) {
                    do {
                        String url = c.getString(0);
                        requestIconAndSendResult(url, listener);
                    } while (c.moveToNext());
                }
            } catch (IllegalStateException e) {
                Log.e(LOGTAG, "BulkRequestIcons", e);
            } finally {
                if (c != null) c.close();
            }
        }

        private void requestIconAndSendResult(String url, IconListener listener) {
            Bitmap icon = nativeIconForPageUrl(url);
            if (icon != null) {
                sendMessage(obtainMessage(ICON_RESULT,
                            new IconResult(url, icon, listener)));
            }
        }
    }

    /**
     * Interface for receiving icons from the database.
     */
    public interface IconListener {
        /**
         * Called when the icon has been retrieved from the database and the
         * result is non-null.
         * @param url The url passed in the request.
         * @param icon The favicon for the given url.
         */
        public void onReceivedIcon(String url, Bitmap icon);
    }

    /**
     * Open a the icon database and store the icons in the given path.
     * @param path The directory path where the icon database will be stored.
     * @return True if the database was successfully opened or created in
     *         the given path.
     */
    public void open(String path) {
        if (path != null) {
            mEventHandler.postMessage(
                    Message.obtain(null, EventHandler.OPEN, path));
        }
    }

    /**
     * Close the shared instance of the icon database.
     */
    public void close() {
        mEventHandler.postMessage(
                Message.obtain(null, EventHandler.CLOSE));
    }

    /**
     * Removes all the icons in the database.
     */
    public void removeAllIcons() {
        mEventHandler.postMessage(
                Message.obtain(null, EventHandler.REMOVE_ALL));
    }

    /**
     * Request the Bitmap representing the icon for the given page
     * url. If the icon exists, the listener will be called with the result.
     * @param url The page's url.
     * @param listener An implementation on IconListener to receive the result.
     */
    public void requestIconForPageUrl(String url, IconListener listener) {
        if (listener == null || url == null) {
            return;
        }
        Message msg = Message.obtain(null, EventHandler.REQUEST_ICON, listener);
        msg.getData().putString("url", url);
        mEventHandler.postMessage(msg);
    }

    /** {@hide}
     */
    public void bulkRequestIconForPageUrl(ContentResolver cr, String where,
            IconListener listener) {
        if (listener == null) {
            return;
        }

        // Special case situation: we don't want to add this message to the
        // queue if there is no handler because we may never have a real
        // handler to service the messages and the cursor will never get
        // closed.
        if (mEventHandler.hasHandler()) {
            // Don't use Bundle as it is parcelable.
            HashMap<String, Object> map = new HashMap<String, Object>();
            map.put("contentResolver", cr);
            map.put("where", where);
            map.put("listener", listener);
            Message msg =
                    Message.obtain(null, EventHandler.BULK_REQUEST_ICON, map);
            mEventHandler.postMessage(msg);
        }
    }

    /**
     * Retain the icon for the given page url.
     * @param url The page's url.
     */
    public void retainIconForPageUrl(String url) {
        if (url != null) {
            mEventHandler.postMessage(
                    Message.obtain(null, EventHandler.RETAIN_ICON, url));
        }
    }

    /**
     * Release the icon for the given page url.
     * @param url The page's url.
     */
    public void releaseIconForPageUrl(String url) {
        if (url != null) {
            mEventHandler.postMessage(
                    Message.obtain(null, EventHandler.RELEASE_ICON, url));
        }
    }

    /**
     * Get the global instance of WebIconDatabase.
     * @return A single instance of WebIconDatabase. It will be the same
     *         instance for the current process each time this method is
     *         called.
     */
    public static WebIconDatabase getInstance() {
        // XXX: Must be created in the UI thread.
        if (sIconDatabase == null) {
            sIconDatabase = new WebIconDatabase();
        }
        return sIconDatabase;
    }

    /**
     * Create the internal handler and transfer all pending messages.
     * XXX: Called by WebCore thread only!
     */
    /*package*/ void createHandler() {
        mEventHandler.createHandler();
    }

    /**
     * Private constructor to avoid anyone else creating an instance.
     */
    private WebIconDatabase() {}

    // Native functions
    private static native void nativeOpen(String path);
    private static native void nativeClose();
    private static native void nativeRemoveAllIcons();
    private static native Bitmap nativeIconForPageUrl(String url);
    private static native void nativeRetainIconForPageUrl(String url);
    private static native void nativeReleaseIconForPageUrl(String url);
}
