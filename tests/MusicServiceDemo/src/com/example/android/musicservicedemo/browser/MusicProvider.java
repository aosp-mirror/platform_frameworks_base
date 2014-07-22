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

package com.example.android.musicservicedemo.browser;

import android.database.MatrixCursor;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.util.Log;

import com.example.android.musicservicedemo.BrowserService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to get a list of MusicTrack's based on a server-side JSON
 * configuration.
 */
public class MusicProvider {

    private static final String TAG = "MusicProvider";

    private static final String MUSIC_URL = "http://storage.googleapis.com/automotive-media/music.json";

    private static String MUSIC = "music";
    private static String TITLE = "title";
    private static String ALBUM = "album";
    private static String ARTIST = "artist";
    private static String GENRE = "genre";
    private static String SOURCE = "source";
    private static String IMAGE = "image";
    private static String TRACK_NUMBER = "trackNumber";
    private static String TOTAL_TRACK_COUNT = "totalTrackCount";
    private static String DURATION = "duration";

    // Cache for music track data
    private static List<MusicTrack> mMusicList;

    /**
     * Get the cached list of music tracks
     *
     * @return
     * @throws JSONException
     */
    public List<MusicTrack> getMedia() throws JSONException {
        if (null != mMusicList && mMusicList.size() > 0) {
            return mMusicList;
        }
        return null;
    }

    /**
     * Get the list of music tracks from a server and return the list of
     * MusicTrack objects.
     *
     * @return
     * @throws JSONException
     */
    public List<MusicTrack> retreiveMedia() throws JSONException {
        if (null != mMusicList) {
            return mMusicList;
        }
        int slashPos = MUSIC_URL.lastIndexOf('/');
        String path = MUSIC_URL.substring(0, slashPos + 1);
        JSONObject jsonObj = parseUrl(MUSIC_URL);

        try {
            JSONArray videos = jsonObj.getJSONArray(MUSIC);
            if (null != videos) {
                mMusicList = new ArrayList<MusicTrack>();
                for (int j = 0; j < videos.length(); j++) {
                    JSONObject music = videos.getJSONObject(j);
                    String title = music.getString(TITLE);
                    String album = music.getString(ALBUM);
                    String artist = music.getString(ARTIST);
                    String genre = music.getString(GENRE);
                    String source = music.getString(SOURCE);
                    // Media is stored relative to JSON file
                    if (!source.startsWith("http")) {
                        source = path + source;
                    }
                    String image = music.getString(IMAGE);
                    if (!image.startsWith("http")) {
                        image = path + image;
                    }
                    int trackNumber = music.getInt(TRACK_NUMBER);
                    int totalTrackCount = music.getInt(TOTAL_TRACK_COUNT);
                    int duration = music.getInt(DURATION) * 1000; // ms

                    mMusicList.add(new MusicTrack(title, album, artist, genre, source,
                            image, trackNumber, totalTrackCount, duration));
                }
            }
        } catch (NullPointerException e) {
            Log.e(TAG, "retreiveMedia", e);
        }
        return mMusicList;
    }

    /**
     * Download a JSON file from a server, parse the content and return the JSON
     * object.
     *
     * @param urlString
     * @return
     */
    private JSONObject parseUrl(String urlString) {
        InputStream is = null;
        try {
            java.net.URL url = new java.net.URL(urlString);
            URLConnection urlConnection = url.openConnection();
            is = new BufferedInputStream(urlConnection.getInputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    urlConnection.getInputStream(), "iso-8859-1"), 8);
            StringBuilder sb = new StringBuilder();
            String line = null;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.d(TAG, "Failed to parse the json for media list", e);
            return null;
        } finally {
            if (null != is) {
                try {
                    is.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    public MatrixCursor getRootContainerCurser() {
        MatrixCursor matrixCursor = new MatrixCursor(BrowserService.MEDIA_CONTAINER_PROJECTION);
        Uri.Builder pianoBuilder = new Uri.Builder();
        pianoBuilder.authority(BrowserService.AUTHORITY);
        pianoBuilder.appendPath(BrowserService.PIANO_BASE_PATH);
        matrixCursor.addRow(new Object[] {
                pianoBuilder.build(),
                BrowserService.PIANO_BASE_PATH,
                "subtitle",
                null,
                0
        });

        Uri.Builder voiceBuilder = new Uri.Builder();
        voiceBuilder.authority(BrowserService.AUTHORITY);
        voiceBuilder.appendPath(BrowserService.VOICE_BASE_PATH);
        matrixCursor.addRow(new Object[] {
                voiceBuilder.build(),
                BrowserService.VOICE_BASE_PATH,
                "subtitle",
                null,
                0
        });
        return matrixCursor;
    }

    public MatrixCursor getRootItemCursor(int type) {
        if (type == BrowserService.NOW_PLAYING) {
            MatrixCursor matrixCursor = new MatrixCursor(BrowserService.MEDIA_CONTAINER_PROJECTION);

            try {
                // Just return all of the tracks for now
                List<MusicTrack> musicTracks = retreiveMedia();
                for (MusicTrack musicTrack : musicTracks) {
                    Uri.Builder builder = new Uri.Builder();
                    builder.authority(BrowserService.AUTHORITY);
                    builder.appendPath(BrowserService.NOW_PLAYING_PATH);
                    builder.appendPath(musicTrack.getTitle());
                    matrixCursor.addRow(new Object[] {
                            builder.build(),
                            musicTrack.getTitle(),
                            musicTrack.getArtist(),
                            musicTrack.getImage(),
                            PlaybackState.ACTION_PLAY
                    });
                    Log.d(TAG, "Uri " + builder.build());
                }
            } catch (JSONException e) {
                Log.e(TAG, "::getRootItemCursor:", e);
            }

            Log.d(TAG, "cursor: " + matrixCursor.getCount());
            return matrixCursor;
        } else if (type == BrowserService.PIANO) {
            MatrixCursor matrixCursor = new MatrixCursor(BrowserService.MEDIA_CONTAINER_PROJECTION);

            try {
                List<MusicTrack> musicTracks = retreiveMedia();
                for (MusicTrack musicTrack : musicTracks) {
                    Uri.Builder builder = new Uri.Builder();
                    builder.authority(BrowserService.AUTHORITY);
                    builder.appendPath(BrowserService.PIANO_BASE_PATH);
                    builder.appendPath(musicTrack.getTitle());
                    matrixCursor.addRow(new Object[] {
                            builder.build(),
                            musicTrack.getTitle(),
                            musicTrack.getArtist(),
                            musicTrack.getImage(),
                            PlaybackState.ACTION_PLAY
                    });
                    Log.d(TAG, "Uri " + builder.build());
                }
            } catch (JSONException e) {
                Log.e(TAG, "::getRootItemCursor:", e);
            }

            Log.d(TAG, "cursor: " + matrixCursor.getCount());
            return matrixCursor;
        } else if (type == BrowserService.VOICE) {
            MatrixCursor matrixCursor = new MatrixCursor(BrowserService.MEDIA_CONTAINER_PROJECTION);

            try {
                List<MusicTrack> musicTracks = retreiveMedia();
                for (MusicTrack musicTrack : musicTracks) {
                    Uri.Builder builder = new Uri.Builder();
                    builder.authority(BrowserService.AUTHORITY);
                    builder.appendPath(BrowserService.VOICE_BASE_PATH);
                    builder.appendPath(musicTrack.getTitle());
                    matrixCursor.addRow(new Object[] {
                            builder.build(),
                            musicTrack.getTitle(),
                            musicTrack.getArtist(),
                            musicTrack.getImage(),
                            PlaybackState.ACTION_PLAY
                    });
                    Log.d(TAG, "Uri " + builder.build());
                }
            } catch (JSONException e) {
                Log.e(TAG, "::getRootItemCursor:", e);
            }

            Log.d(TAG, "cursor: " + matrixCursor.getCount());
            return matrixCursor;

        }
        return null;
    }
}
