/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.media;

import android.content.Context;
import android.media.IAudioService;
import android.media.IRingtonePlayer;
import android.media.Ringtone;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.systemui.SystemUI;
import com.google.android.collect.Maps;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;

/**
 * Service that offers to play ringtones by {@link Uri}, since our process has
 * {@link android.Manifest.permission#READ_EXTERNAL_STORAGE}.
 */
public class RingtonePlayer extends SystemUI {
    private static final String TAG = "RingtonePlayer";
    private static final boolean LOGD = false;

    // TODO: support Uri switching under same IBinder

    private IAudioService mAudioService;

    private final NotificationPlayer mAsyncPlayer = new NotificationPlayer(TAG);
    private final HashMap<IBinder, Client> mClients = Maps.newHashMap();

    @Override
    public void start() {
        mAsyncPlayer.setUsesWakeLock(mContext);

        mAudioService = IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE));
        try {
            mAudioService.setRingtonePlayer(mCallback);
        } catch (RemoteException e) {
            Slog.e(TAG, "Problem registering RingtonePlayer: " + e);
        }
    }

    /**
     * Represents an active remote {@link Ringtone} client.
     */
    private class Client implements IBinder.DeathRecipient {
        private final IBinder mToken;
        private final Ringtone mRingtone;

        public Client(IBinder token, Uri uri, int streamType) {
            mToken = token;
            mRingtone = new Ringtone(mContext, false);
            mRingtone.setStreamType(streamType);
            mRingtone.setUri(uri);
        }

        @Override
        public void binderDied() {
            if (LOGD) Slog.d(TAG, "binderDied() token=" + mToken);
            synchronized (mClients) {
                mClients.remove(mToken);
            }
            mRingtone.stop();
        }
    }

    private IRingtonePlayer mCallback = new IRingtonePlayer.Stub() {
        @Override
        public void play(IBinder token, Uri uri, int streamType) throws RemoteException {
            if (LOGD) Slog.d(TAG, "play(token=" + token + ", uri=" + uri + ")");
            Client client;
            synchronized (mClients) {
                client = mClients.get(token);
                if (client == null) {
                    client = new Client(token, uri, streamType);
                    token.linkToDeath(client, 0);
                    mClients.put(token, client);
                }
            }
            client.mRingtone.play();
        }

        @Override
        public void stop(IBinder token) {
            if (LOGD) Slog.d(TAG, "stop(token=" + token + ")");
            Client client;
            synchronized (mClients) {
                client = mClients.remove(token);
            }
            if (client != null) {
                client.mToken.unlinkToDeath(client, 0);
                client.mRingtone.stop();
            }
        }

        @Override
        public boolean isPlaying(IBinder token) {
            if (LOGD) Slog.d(TAG, "isPlaying(token=" + token + ")");
            Client client;
            synchronized (mClients) {
                client = mClients.get(token);
            }
            if (client != null) {
                return client.mRingtone.isPlaying();
            } else {
                return false;
            }
        }

        @Override
        public void playAsync(Uri uri, boolean looping, int streamType) {
            if (LOGD) Slog.d(TAG, "playAsync(uri=" + uri + ")");
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Async playback only available from system UID.");
            }
            mAsyncPlayer.play(mContext, uri, looping, streamType);
        }

        @Override
        public void stopAsync() {
            if (LOGD) Slog.d(TAG, "stopAsync()");
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Async playback only available from system UID.");
            }
            mAsyncPlayer.stop();
        }
    };

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Clients:");
        synchronized (mClients) {
            for (Client client : mClients.values()) {
                pw.print("  mToken=");
                pw.print(client.mToken);
                pw.print(" mUri=");
                pw.println(client.mRingtone.getUri());
            }
        }
    }
}
