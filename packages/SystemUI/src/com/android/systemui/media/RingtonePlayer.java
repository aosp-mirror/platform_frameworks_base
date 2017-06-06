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

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.IAudioService;
import android.media.IRingtonePlayer;
import android.media.Ringtone;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AudioColumns;
import android.util.Log;

import com.android.internal.util.Preconditions;
import com.android.systemui.SystemUI;

import java.io.FileDescriptor;
import java.io.IOException;
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
        public void play(IBinder token, Uri uri, AudioAttributes aa, float volume, boolean looping)
                throws RemoteException {
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
            client.mRingtone.setLooping(looping);
            client.mRingtone.setVolume(volume);
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
        public void setPlaybackProperties(IBinder token, float volume, boolean looping) {
            Client client;
            synchronized (mClients) {
                client = mClients.get(token);
            }
            if (client != null) {
                client.mRingtone.setVolume(volume);
                client.mRingtone.setLooping(looping);
            }
            // else no client for token when setting playback properties but will be set at play()
        }

        @Override
        public void setVolume(IBinder token, float volume) {
            if (LOGD) Log.d(TAG, "setVolume(token=" + token + ", volume=" + volume + ")");
            Client client;
            synchronized (mClients) {
                client = mClients.get(token);
            }
            if (client != null) {
                client.mRingtone.setVolume(volume);
            }
        }

        @Override
        public void playAsync(Uri uri, UserHandle user, boolean looping, AudioAttributes aa) {
            if (LOGD) Log.d(TAG, "playAsync(uri=" + uri + ", user=" + user + ")");
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Async playback only available from system UID.");
            }
            if (UserHandle.ALL.equals(user)) {
                user = UserHandle.SYSTEM;
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

        @Override
        public String getTitle(Uri uri) {
            final UserHandle user = Binder.getCallingUserHandle();
            return Ringtone.getTitle(getContextForUser(user), uri,
                    false /*followSettingsUri*/, false /*allowRemote*/);
        }

        @Override
        public ParcelFileDescriptor openRingtone(Uri uri) {
            final UserHandle user = Binder.getCallingUserHandle();
            final ContentResolver resolver = getContextForUser(user).getContentResolver();

            // Only open the requested Uri if it's a well-known ringtone or
            // other sound from the platform media store, otherwise this opens
            // up arbitrary access to any file on external storage.
            if (uri.toString().startsWith(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString())) {
                try (Cursor c = resolver.query(uri, new String[] {
                        MediaStore.Audio.AudioColumns.IS_RINGTONE,
                        MediaStore.Audio.AudioColumns.IS_ALARM,
                        MediaStore.Audio.AudioColumns.IS_NOTIFICATION
                }, null, null, null)) {
                    if (c.moveToFirst()) {
                        if (c.getInt(0) != 0 || c.getInt(1) != 0 || c.getInt(2) != 0) {
                            try {
                                return resolver.openFileDescriptor(uri, "r");
                            } catch (IOException e) {
                                throw new SecurityException(e);
                            }
                        }
                    }
                }
            }
            throw new SecurityException("Uri is not ringtone, alarm, or notification: " + uri);
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
