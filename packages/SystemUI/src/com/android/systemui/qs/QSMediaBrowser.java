/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.List;

/**
 * Media browser for managing resumption in QS media controls
 */
public class QSMediaBrowser {

    /** Maximum number of controls to show on boot */
    public static final int MAX_RESUMPTION_CONTROLS = 5;

    /** Delimiter for saved component names */
    public static final String DELIMITER = ":";

    private static final String TAG = "QSMediaBrowser";
    private final Context mContext;
    private final Callback mCallback;
    private MediaBrowser mMediaBrowser;
    private ComponentName mComponentName;

    /**
     * Initialize a new media browser
     * @param context the context
     * @param callback used to report media items found
     * @param componentName Component name of the MediaBrowserService this browser will connect to
     */
    public QSMediaBrowser(Context context, Callback callback, ComponentName componentName) {
        mContext = context;
        mCallback = callback;
        mComponentName = componentName;

        Bundle rootHints = new Bundle();
        rootHints.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true);
        mMediaBrowser = new MediaBrowser(mContext,
                mComponentName,
                mConnectionCallback,
                rootHints);
    }

    /**
     * Connects to the MediaBrowserService and looks for valid media. If a media item is returned
     * by the service, QSMediaBrowser.Callback#addTrack will be called with its MediaDescription
     */
    public void findRecentMedia() {
        Log.d(TAG, "Connecting to " + mComponentName);
        mMediaBrowser.connect();
    }

    private final MediaBrowser.SubscriptionCallback mSubscriptionCallback =
            new MediaBrowser.SubscriptionCallback() {
        @Override
        public void onChildrenLoaded(String parentId,
                List<MediaBrowser.MediaItem> children) {
            if (children.size() == 0) {
                Log.e(TAG, "No children found");
                return;
            }
            // We ask apps to return a playable item as the first child when sending
            // a request with EXTRA_RECENT; if they don't, no resume controls
            MediaBrowser.MediaItem child = children.get(0);
            MediaDescription desc = child.getDescription();
            if (child.isPlayable()) {
                mCallback.addTrack(desc, mMediaBrowser.getServiceComponent(), QSMediaBrowser.this);
            } else {
                Log.e(TAG, "Child found but not playable for " + mComponentName);
            }
            mMediaBrowser.disconnect();
        }

        @Override
        public void onError(String parentId) {
            Log.e(TAG, "Subscribe error for " + mComponentName + ": " + parentId);
            mMediaBrowser.disconnect();
        }

        @Override
        public void onError(String parentId, Bundle options) {
            Log.e(TAG, "Subscribe error for " + mComponentName + ": " + parentId
                    + ", options: " + options);
            mMediaBrowser.disconnect();
        }
    };

    private final MediaBrowser.ConnectionCallback mConnectionCallback =
            new MediaBrowser.ConnectionCallback() {
        /**
         * Invoked after {@link MediaBrowser#connect()} when the request has successfully completed.
         * For resumption controls, apps are expected to return a playable media item as the first
         * child. If there are no children or it isn't playable it will be ignored.
         */
        @Override
        public void onConnected() {
            if (mMediaBrowser.isConnected()) {
                mCallback.onConnected();
                Log.d(TAG, "Service connected for " + mComponentName);
                String root = mMediaBrowser.getRoot();
                mMediaBrowser.subscribe(root, mSubscriptionCallback);
            }
        }

        /**
         * Invoked when the client is disconnected from the media browser.
         */
        @Override
        public void onConnectionSuspended() {
            Log.d(TAG, "Connection suspended for " + mComponentName);
        }

        /**
         * Invoked when the connection to the media browser failed.
         */
        @Override
        public void onConnectionFailed() {
            Log.e(TAG, "Connection failed for " + mComponentName);
            mCallback.onError();
        }
    };

    /**
     * Connects to the MediaBrowserService and starts playback
     */
    public void restart() {
        if (mMediaBrowser.isConnected()) {
            mMediaBrowser.disconnect();
        }
        Bundle rootHints = new Bundle();
        rootHints.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true);
        mMediaBrowser = new MediaBrowser(mContext, mComponentName,
                new MediaBrowser.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        Log.d(TAG, "Connected for restart " + mMediaBrowser.isConnected());
                        MediaSession.Token token = mMediaBrowser.getSessionToken();
                        MediaController controller = new MediaController(mContext, token);
                        controller.getTransportControls();
                        controller.getTransportControls().prepare();
                        controller.getTransportControls().play();
                    }
                }, rootHints);
        mMediaBrowser.connect();
    }

    /**
     * Get the media session token
     * @return the token, or null if the MediaBrowser is null or disconnected
     */
    public MediaSession.Token getToken() {
        if (mMediaBrowser == null || !mMediaBrowser.isConnected()) {
            return null;
        }
        return mMediaBrowser.getSessionToken();
    }

    /**
     * Get an intent to launch the app associated with this browser service
     * @return
     */
    public PendingIntent getAppIntent() {
        PackageManager pm = mContext.getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(mComponentName.getPackageName());
        return PendingIntent.getActivity(mContext, 0, launchIntent, 0);
    }

    /**
     * Used to test if SystemUI is allowed to connect to the given component as a MediaBrowser
     * @param mContext the context
     * @param callback methods onConnected or onError will be called to indicate whether the
     *                 connection was successful or not
     * @param mComponentName Component name of the MediaBrowserService this browser will connect to
     */
    public static MediaBrowser testConnection(Context mContext, Callback callback,
            ComponentName mComponentName) {
        final MediaBrowser.ConnectionCallback mConnectionCallback =
                new MediaBrowser.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        Log.d(TAG, "connected");
                        callback.onConnected();
                    }

                    @Override
                    public void onConnectionSuspended() {
                        Log.d(TAG, "suspended");
                        callback.onError();
                    }

                    @Override
                    public void onConnectionFailed() {
                        Log.d(TAG, "failed");
                        callback.onError();
                    }
                };
        Bundle rootHints = new Bundle();
        rootHints.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true);
        MediaBrowser browser = new MediaBrowser(mContext,
                mComponentName,
                mConnectionCallback,
                rootHints);
        browser.connect();
        return browser;
    }

    /**
     * Interface to handle results from QSMediaBrowser
     */
    public static class Callback {
        /**
         * Called when the browser has successfully connected to the service
         */
        public void onConnected() {
        }

        /**
         * Called when the browser encountered an error connecting to the service
         */
        public void onError() {
        }

        /**
         * Called when the browser finds a suitable track to add to the media carousel
         * @param track media info for the item
         * @param component component of the MediaBrowserService which returned this
         * @param browser reference to the browser
         */
        public void addTrack(MediaDescription track, ComponentName component,
                QSMediaBrowser browser) {
        }
    }
}
