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

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.IAudioService;
import android.media.IRingtonePlayer;
import android.media.Ringtone;
import android.media.VolumeShaper;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.provider.MediaStore;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;

import javax.inject.Inject;

/**
 * Service that offers to play ringtones by {@link Uri}, since our process has
 * {@link android.Manifest.permission#READ_EXTERNAL_STORAGE}.
 */
@SysUISingleton
public class RingtonePlayer implements CoreStartable {
    private static final String TAG = "RingtonePlayer";
    private static final boolean LOGD = false;
    private final Context mContext;

    // TODO: support Uri switching under same IBinder

    private IAudioService mAudioService;

    private final NotificationPlayer mAsyncPlayer = new NotificationPlayer(TAG);
    private final HashMap<IBinder, Client> mClients = new HashMap<IBinder, Client>();

    @Inject
    public RingtonePlayer(Context context) {
        mContext = context;
    }

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
        private Ringtone mRingtone;

        Client(@NonNull IBinder token, @NonNull Ringtone ringtone) {
            mToken = requireNonNull(token);
            mRingtone = requireNonNull(ringtone);
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
            playRemoteRingtone(token, uri, aa, true, Ringtone.MEDIA_SOUND,
                    null, volume, looping, /* hapticGenerator= */ false,
                    null);
        }

        @Override
        public void playRemoteRingtone(IBinder token, Uri uri, AudioAttributes aa,
                boolean useExactAudioAttributes,
                @Ringtone.RingtoneMedia int enabledMedia, @Nullable VibrationEffect vibrationEffect,
                float volume,
                boolean looping, boolean isHapticGeneratorEnabled,
                @Nullable VolumeShaper.Configuration volumeShaperConfig)
                throws RemoteException {
            if (LOGD) {
                Log.d(TAG, "play(token=" + token + ", uri=" + uri + ", uid="
                        + Binder.getCallingUid() + ")");
            }

            // Don't hold the lock while constructing the ringtone, since it can be slow. The caller
            // shouldn't call play on the same ringtone from 2 threads, so this shouldn't race and
            // waste the build.
            Client client;
            synchronized (mClients) {
                client = mClients.get(token);
            }
            if (client == null) {
                final UserHandle user = Binder.getCallingUserHandle();
                Ringtone ringtone = new Ringtone.Builder(getContextForUser(user), enabledMedia, aa)
                        .setLocalOnly()
                        .setUri(uri)
                        .setLooping(looping)
                        .setInitialSoundVolume(volume)
                        .setUseExactAudioAttributes(useExactAudioAttributes)
                        .setEnableHapticGenerator(isHapticGeneratorEnabled)
                        .setVibrationEffect(vibrationEffect)
                        .setVolumeShaperConfig(volumeShaperConfig)
                        .build();
                if (ringtone == null) {
                    return;
                }
                synchronized (mClients) {
                    client = mClients.get(token);
                    if (client == null) {
                        client = new Client(token, ringtone);
                        token.linkToDeath(client, 0);
                        mClients.put(token, client);
                    }
                }
            }
            // Ensure the client is initialized outside the all-clients lock, as it can be slow.
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
        public void setHapticGeneratorEnabled(IBinder token, boolean hapticGeneratorEnabled) {
            Client client;
            synchronized (mClients) {
                client = mClients.get(token);
            }
            if (client != null) {
                client.mRingtone.setHapticGeneratorEnabled(hapticGeneratorEnabled);
            }
        }

        @Override
        public void setLooping(IBinder token, boolean looping) {
            Client client;
            synchronized (mClients) {
                client = mClients.get(token);
            }
            if (client != null) {
                client.mRingtone.setLooping(looping);
            }
        }

        @Override
        public void setVolume(IBinder token, float volume) {
            Client client;
            synchronized (mClients) {
                client = mClients.get(token);
            }
            if (client != null) {
                client.mRingtone.setVolume(volume);
            }
        }

        @Override
        public void playAsync(Uri uri, UserHandle user, boolean looping, AudioAttributes aa,
                float volume) {
            if (LOGD) Log.d(TAG, "playAsync(uri=" + uri + ", user=" + user + ")");
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Async playback only available from system UID.");
            }
            if (UserHandle.ALL.equals(user)) {
                user = UserHandle.SYSTEM;
            }
            mAsyncPlayer.play(getContextForUser(user), uri, looping, aa, volume);
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
    public void dump(PrintWriter pw, String[] args) {
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
