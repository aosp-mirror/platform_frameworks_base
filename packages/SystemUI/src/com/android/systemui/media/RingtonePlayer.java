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
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.AudioAttributes;
import android.media.IAudioService;
import android.media.IRingtonePlayer;
import android.media.Ringtone;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemui.SystemUI;

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
    private final HashMap<IBinder, Client> mClients = new HashMap<IBinder, Client>();

    @Override
    public void start() {
        mAsyncPlayer.setUsesWakeLock(mContext);

        mAudioService = IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE));
        try {
            mAudioService.setRingtonePlayer(mCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "Problem registering RingtonePlayer: " + e);
        }
    }

    /**
     * Represents an active remote {@link Ringtone} client.
     */
    private class Client implements IBinder.DeathRecipient {
        private final IBinder mToken;
        private final Ringtone mRingtone;

        public Client(IBinder token, Uri uri, UserHandle user, AudioAttributes aa) {
            mToken = token;

            mRingtone = new Ringtone(getContextForUser(user), false);
            mRingtone.setAudioAttributes(aa);
            mRingtone.setUri(uri);
        }

        @Override
        public void binderDied() {
            if (LOGD) Log.d(TAG, "binderDied() token=" + mToken);
            synchronized (mClients) {
                mClients.remove(mToken);
            }
            mRingtone.stop();
        }
    }

    private IRingtonePlayer mCallback = new IRingtonePlayer.Stub() {
        @Override
        public void play(IBinder token, Uri uri, AudioAttributes aa) throws RemoteException {
            if (LOGD) {
                Log.d(TAG, "play(token=" + token + ", uri=" + uri + ", uid="
                        + Binder.getCallingUid() + ")");
            }
            Client client;
            synchronized (mClients) {
                client = mClients.get(token);
                if (client == null) {
                    final UserHandle user = Binder.getCallingUserHandle();
                    client = new Client(token, uri, user, aa);
                    token.linkToDeath(client, 0);
                    mClients.put(token, client);
                }
            }
            client.mRingtone.play();
        }

        @Override
        public void stop(IBinder token) {
            if (LOGD) Log.d(TAG, "stop(token=" + token + ")");
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
            if (LOGD) Log.d(TAG, "isPlaying(token=" + token + ")");
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
        public void playAsync(Uri uri, UserHandle user, boolean looping, AudioAttributes aa) {
            if (LOGD) Log.d(TAG, "playAsync(uri=" + uri + ", user=" + user + ")");
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Async playback only available from system UID.");
            }

            mAsyncPlayer.play(getContextForUser(user), uri, looping, aa);
        }

        @Override
        public void stopAsync() {
            if (LOGD) Log.d(TAG, "stopAsync()");
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Async playback only available from system UID.");
            }
            mAsyncPlayer.stop();
        }
    };

    private Context getContextForUser(UserHandle user) {
        try {
            return mContext.createPackageContextAsUser(mContext.getPackageName(), 0, user);
        } catch (NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

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
