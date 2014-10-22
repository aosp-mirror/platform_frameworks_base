/*  
 * Copyright (C) 2014 The Android Open Source Project
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

import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONException;

/**
 * Asynchronous task to retrieve the music data using MusicProvider.
 */
public class MusicProviderTask extends AsyncTask<Void, Void, Void> {

    private static final String TAG = "MusicProviderTask";

    MusicProvider mMusicProvider;
    MusicProviderTaskListener mMusicProviderTaskListener;

    /**
     * Initialize the task with the provider to download the music data and the
     * listener to be informed when the task is done.
     *
     * @param musicProvider
     * @param listener
     */
    public MusicProviderTask(MusicProvider musicProvider,
            MusicProviderTaskListener listener) {
        mMusicProvider = musicProvider;
        mMusicProviderTaskListener = listener;
    }

    /*
     * (non-Javadoc)
     * @see android.os.AsyncTask#doInBackground(java.lang.Object[])
     */
    @Override
    protected Void doInBackground(Void... arg0) {
        try {
            mMusicProvider.retreiveMedia();
        } catch (JSONException e) {
            Log.e(TAG, "::doInBackground:", e);
        }
        return null;
    }

    /*
     * (non-Javadoc)
     * @see android.os.AsyncTask#onPostExecute(java.lang.Object)
     */
    @Override
    protected void onPostExecute(Void result) {
        mMusicProviderTaskListener.onMusicProviderTaskCompleted();
    }

}
