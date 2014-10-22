/*
 * Copyright (C) 2014 Google Inc. All Rights Reserved.
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

package com.example.android.musicservicedemo;

import android.app.SearchManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.content.res.Resources.NotFoundException;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.browse.MediaBrowser;
import android.service.media.MediaBrowserService;
import android.service.media.MediaBrowserService.BrowserRoot;
import android.media.session.MediaSession;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.example.android.musicservicedemo.browser.MusicProvider;
import com.example.android.musicservicedemo.browser.MusicProviderTask;
import com.example.android.musicservicedemo.browser.MusicProviderTaskListener;
import com.example.android.musicservicedemo.browser.MusicTrack;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service that implements MediaBrowserService and returns our menu hierarchy.
 */
public class BrowserService extends MediaBrowserService {
    private static final String TAG = "BrowserService";

    // URI paths for browsing music
    public static final String BROWSE_ROOT_BASE_PATH = "browse";
    public static final String NOW_PLAYING_PATH = "now_playing";
    public static final String PIANO_BASE_PATH = "piano";
    public static final String VOICE_BASE_PATH = "voice";

    // Content URIs
    public static final String AUTHORITY = "com.example.android.automotive.musicplayer";
    public static final Uri BASE_URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri BROWSE_URI = Uri.withAppendedPath(BASE_URI, BROWSE_ROOT_BASE_PATH);

    // URI matcher constants for browsing paths
    public static final int BROWSE_ROOT = 1;
    public static final int NOW_PLAYING = 2;
    public static final int PIANO = 3;
    public static final int VOICE = 4;

    // Map the the URI paths with the URI matcher constants
    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sUriMatcher.addURI(AUTHORITY, BROWSE_ROOT_BASE_PATH, BROWSE_ROOT);
        sUriMatcher.addURI(AUTHORITY, NOW_PLAYING_PATH, NOW_PLAYING);
        sUriMatcher.addURI(AUTHORITY, PIANO_BASE_PATH, PIANO);
        sUriMatcher.addURI(AUTHORITY, VOICE_BASE_PATH, VOICE);
    }

    // Media metadata that will be provided for a media container
    public static final String[] MEDIA_CONTAINER_PROJECTION = {
            "uri",
            "title",
            "subtitle",
            "image_uri",
            "supported_actions"
    };

    // MusicProvider will download the music catalog
    private MusicProvider mMusicProvider;

    private MediaSession mSession;

    @Override
    public void onCreate() {
        super.onCreate();

        mSession = new MediaSession(this, "com.example.android.musicservicedemo.BrowserService");
        setSessionToken(mSession.getSessionToken());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(BROWSE_URI.toString(), null);
    }

    @Override
    public void onLoadChildren(final String parentId,
            final Result<List<MediaBrowser.MediaItem>> result) {
        new Handler().postDelayed(new Runnable() {
                public void run() {
                    final ArrayList<MediaBrowser.MediaItem> list = new ArrayList();

                    for (int i=0; i<10; i++) {
                        MediaDescription.Builder bob = new MediaDescription.Builder();
                        bob.setTitle("Title " + i);
                        bob.setSubtitle("Summary " + i);
                        bob.setMediaId(Uri.withAppendedPath(BASE_URI,
                                Integer.toString(i)).toString());
                        list.add(new MediaBrowser.MediaItem(bob.build(),
                                MediaBrowser.MediaItem.FLAG_BROWSABLE));
                    }

                    result.sendResult(list);
                }
            }, 2000);
        result.detach();
    }

    /*
    @Override
    public void query(final Query query, final IMetadataResultHandler metadataResultHandler,
            final IErrorHandler errorHandler)
            throws RemoteException {
        Log.d(TAG, "query: " + query);
        Utils.checkNotNull(query);
        Utils.checkNotNull(metadataResultHandler);
        Utils.checkNotNull(errorHandler);

        // Handle async response
        new Thread(new Runnable() {
            public void run() {
                try {
                    // Pre-load the list of music
                    List<MusicTrack> musicTracks = getMusicList();
                    if (musicTracks == null) {
                        notifyListenersOnPlaybackStateUpdate(getCurrentPlaybackState());
                        errorHandler.onError(new Error(Error.UNKNOWN,
                                getString(R.string.music_error)));
                        return;
                    }

                    final Uri uri = query.getUri();
                    int match = sUriMatcher.match(uri);
                    Log.d(TAG, "Queried: " + uri + "; match: " + match);
                    switch (match) {
                        case BROWSE_ROOT:
                        {
                            Log.d(TAG, "Browse_root");

                            try {
                                MatrixCursor matrixCursor = mMusicProvider
                                        .getRootContainerCurser();
                                DataHolder holder = new DataHolder(MEDIA_CONTAINER_PROJECTION,
                                        matrixCursor, null);

                                Log.d(TAG, "on metadata response called " + holder.getCount());
                                metadataResultHandler.onMetadataResponse(holder);
                            } catch (RemoteException e) {
                                Log.w(TAG, "Error delivering metadata in the callback.", e);
                            }
                            break;
                        }
                        case NOW_PLAYING:
                        {
                            try {
                                Log.d(TAG, "query NOW_PLAYING");
                                MatrixCursor matrixCursor = mMusicProvider
                                        .getRootItemCursor(
                                        PIANO);
                                DataHolder holder = new DataHolder(MEDIA_CONTAINER_PROJECTION,
                                        matrixCursor, null);
                                Log.d(TAG, "on metadata response called " + holder.getCount());
                                metadataResultHandler.onMetadataResponse(holder);
                            } catch (RemoteException e) {
                                Log.w(TAG, "Error querying NOW_PLAYING");
                            }
                            break;
                        }
                        case PIANO:
                        {
                            try {
                                Log.d(TAG, "query PIANO");
                                MatrixCursor matrixCursor = mMusicProvider
                                        .getRootItemCursor(
                                        PIANO);
                                DataHolder holder = new DataHolder(MEDIA_CONTAINER_PROJECTION,
                                        matrixCursor, null);
                                Log.d(TAG, "on metadata response called " + holder.getCount());
                                metadataResultHandler.onMetadataResponse(holder);
                            } catch (RemoteException e) {
                                Log.w(TAG, "Error querying PIANO");
                            }
                            break;
                        }
                        case VOICE:
                        {
                            try {
                                Log.d(TAG, "query VOICE");
                                MatrixCursor matrixCursor = mMusicProvider
                                        .getRootItemCursor(
                                        VOICE);
                                DataHolder holder = new DataHolder(MEDIA_CONTAINER_PROJECTION,
                                        matrixCursor, null);
                                Log.d(TAG, "on metadata response called " + holder.getCount());
                                metadataResultHandler.onMetadataResponse(holder);
                            } catch (RemoteException e) {
                                Log.w(TAG, "Error querying VOICE");
                            }
                            break;
                        }
                        default:
                        {
                            Log.w(TAG, "Skipping unmatched URI: " + uri);
                        }
                    }
                } catch (NotFoundException e) {
                    Log.e(TAG, "::run:", e);
                } catch (RemoteException e) {
                    Log.e(TAG, "::run:", e);
                }
            } // end run
        }).start();
    }

    */
}
