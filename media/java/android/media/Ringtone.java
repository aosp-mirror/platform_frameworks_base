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

package android.media;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources.NotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;

/**
 * Ringtone provides a quick method for playing a ringtone, notification, or
 * other similar types of sounds.
 * <p>
 * For ways of retrieving {@link Ringtone} objects or to show a ringtone
 * picker, see {@link RingtoneManager}.
 * 
 * @see RingtoneManager
 */
public class Ringtone {
    private static final String TAG = "Ringtone";
    private static final boolean LOGD = false;

    private static final String[] MEDIA_COLUMNS = new String[] {
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.TITLE
    };

    private final Context mContext;
    private final AudioManager mAudioManager;

    /**
     * Flag indicating if we're allowed to fall back to remote playback using
     * {@link #mRemotePlayer}. Typically this is false when we're the remote
     * player and there is nobody else to delegate to.
     */
    private final boolean mAllowRemote;
    private final IRingtonePlayer mRemotePlayer;
    private final Binder mRemoteToken;

    private MediaPlayer mLocalPlayer;

    private Uri mUri;
    private String mTitle;

    private AudioAttributes mAudioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    /** {@hide} */
    public Ringtone(Context context, boolean allowRemote) {
        mContext = context;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mAllowRemote = allowRemote;
        mRemotePlayer = allowRemote ? mAudioManager.getRingtonePlayer() : null;
        mRemoteToken = allowRemote ? new Binder() : null;
    }

    /**
     * Sets the stream type where this ringtone will be played.
     * 
     * @param streamType The stream, see {@link AudioManager}.
     * @deprecated use {@link #setAudioAttributes(AudioAttributes)}
     */
    @Deprecated
    public void setStreamType(int streamType) {
        setAudioAttributes(new AudioAttributes.Builder()
                .setInternalLegacyStreamType(streamType)
                .build());
    }

    /**
     * Gets the stream type where this ringtone will be played.
     * 
     * @return The stream type, see {@link AudioManager}.
     * @deprecated use of stream types is deprecated, see
     *     {@link #setAudioAttributes(AudioAttributes)}
     */
    @Deprecated
    public int getStreamType() {
        return AudioAttributes.toLegacyStreamType(mAudioAttributes);
    }

    /**
     * Sets the {@link AudioAttributes} for this ringtone.
     * @param attributes the non-null attributes characterizing this ringtone.
     */
    public void setAudioAttributes(AudioAttributes attributes)
            throws IllegalArgumentException {
        if (attributes == null) {
            throw new IllegalArgumentException("Invalid null AudioAttributes for Ringtone");
        }
        mAudioAttributes = attributes;
        // The audio attributes have to be set before the media player is prepared.
        // Re-initialize it.
        setUri(mUri);
    }

    /**
     * Returns the {@link AudioAttributes} used by this object.
     * @return the {@link AudioAttributes} that were set with
     *     {@link #setAudioAttributes(AudioAttributes)} or the default attributes if none were set.
     */
    public AudioAttributes getAudioAttributes() {
        return mAudioAttributes;
    }

    /**
     * Returns a human-presentable title for ringtone. Looks in media
     * content provider. If not in either, uses the filename
     * 
     * @param context A context used for querying. 
     */
    public String getTitle(Context context) {
        if (mTitle != null) return mTitle;
        return mTitle = getTitle(context, mUri, true);
    }

    private static String getTitle(Context context, Uri uri, boolean followSettingsUri) {
        Cursor cursor = null;
        ContentResolver res = context.getContentResolver();
        
        String title = null;

        if (uri != null) {
            String authority = uri.getAuthority();

            if (Settings.AUTHORITY.equals(authority)) {
                if (followSettingsUri) {
                    Uri actualUri = RingtoneManager.getActualDefaultRingtoneUri(context,
                            RingtoneManager.getDefaultType(uri));
                    String actualTitle = getTitle(context, actualUri, false);
                    title = context
                            .getString(com.android.internal.R.string.ringtone_default_with_actual,
                                    actualTitle);
                }
            } else {
                try {
                    if (MediaStore.AUTHORITY.equals(authority)) {
                        cursor = res.query(uri, MEDIA_COLUMNS, null, null, null);
                    }
                } catch (SecurityException e) {
                    // missing cursor is handled below
                }

                try {
                    if (cursor != null && cursor.getCount() == 1) {
                        cursor.moveToFirst();
                        return cursor.getString(2);
                    } else {
                        title = uri.getLastPathSegment();
                    }
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
        }

        if (title == null) {
            title = context.getString(com.android.internal.R.string.ringtone_unknown);
            
            if (title == null) {
                title = "";
            }
        }
        
        return title;
    }

    /**
     * Set {@link Uri} to be used for ringtone playback. Attempts to open
     * locally, otherwise will delegate playback to remote
     * {@link IRingtonePlayer}.
     *
     * @hide
     */
    public void setUri(Uri uri) {
        destroyLocalPlayer();

        mUri = uri;
        if (mUri == null) {
            return;
        }

        // TODO: detect READ_EXTERNAL and specific content provider case, instead of relying on throwing

        // try opening uri locally before delegating to remote player
        mLocalPlayer = new MediaPlayer();
        try {
            mLocalPlayer.setDataSource(mContext, mUri);
            mLocalPlayer.setAudioAttributes(mAudioAttributes);
            mLocalPlayer.prepare();

        } catch (SecurityException | IOException e) {
            destroyLocalPlayer();
            if (!mAllowRemote) {
                Log.w(TAG, "Remote playback not allowed: " + e);
            }
        }

        if (LOGD) {
            if (mLocalPlayer != null) {
                Log.d(TAG, "Successfully created local player");
            } else {
                Log.d(TAG, "Problem opening; delegating to remote player");
            }
        }
    }

    /** {@hide} */
    public Uri getUri() {
        return mUri;
    }

    /**
     * Plays the ringtone.
     */
    public void play() {
        if (mLocalPlayer != null) {
            // do not play ringtones if stream volume is 0
            // (typically because ringer mode is silent).
            if (mAudioManager.getStreamVolume(
                    AudioAttributes.toLegacyStreamType(mAudioAttributes)) != 0) {
                mLocalPlayer.start();
            }
        } else if (mAllowRemote && (mRemotePlayer != null)) {
            final Uri canonicalUri = mUri.getCanonicalUri();
            try {
                mRemotePlayer.play(mRemoteToken, canonicalUri, mAudioAttributes);
            } catch (RemoteException e) {
                if (!playFallbackRingtone()) {
                    Log.w(TAG, "Problem playing ringtone: " + e);
                }
            }
        } else {
            if (!playFallbackRingtone()) {
                Log.w(TAG, "Neither local nor remote playback available");
            }
        }
    }

    /**
     * Stops a playing ringtone.
     */
    public void stop() {
        if (mLocalPlayer != null) {
            destroyLocalPlayer();
        } else if (mAllowRemote && (mRemotePlayer != null)) {
            try {
                mRemotePlayer.stop(mRemoteToken);
            } catch (RemoteException e) {
                Log.w(TAG, "Problem stopping ringtone: " + e);
            }
        }
    }

    private void destroyLocalPlayer() {
        if (mLocalPlayer != null) {
            mLocalPlayer.reset();
            mLocalPlayer.release();
            mLocalPlayer = null;
        }
    }

    /**
     * Whether this ringtone is currently playing.
     * 
     * @return True if playing, false otherwise.
     */
    public boolean isPlaying() {
        if (mLocalPlayer != null) {
            return mLocalPlayer.isPlaying();
        } else if (mAllowRemote && (mRemotePlayer != null)) {
            try {
                return mRemotePlayer.isPlaying(mRemoteToken);
            } catch (RemoteException e) {
                Log.w(TAG, "Problem checking ringtone: " + e);
                return false;
            }
        } else {
            Log.w(TAG, "Neither local nor remote playback available");
            return false;
        }
    }

    private boolean playFallbackRingtone() {
        if (mAudioManager.getStreamVolume(AudioAttributes.toLegacyStreamType(mAudioAttributes))
                != 0) {
            int ringtoneType = RingtoneManager.getDefaultType(mUri);
            if (ringtoneType == -1 ||
                    RingtoneManager.getActualDefaultRingtoneUri(mContext, ringtoneType) != null) {
                // Default ringtone, try fallback ringtone.
                try {
                    AssetFileDescriptor afd = mContext.getResources().openRawResourceFd(
                            com.android.internal.R.raw.fallbackring);
                    if (afd != null) {
                        mLocalPlayer = new MediaPlayer();
                        if (afd.getDeclaredLength() < 0) {
                            mLocalPlayer.setDataSource(afd.getFileDescriptor());
                        } else {
                            mLocalPlayer.setDataSource(afd.getFileDescriptor(),
                                    afd.getStartOffset(),
                                    afd.getDeclaredLength());
                        }
                        mLocalPlayer.setAudioAttributes(mAudioAttributes);
                        mLocalPlayer.prepare();
                        mLocalPlayer.start();
                        afd.close();
                        return true;
                    } else {
                        Log.e(TAG, "Could not load fallback ringtone");
                    }
                } catch (IOException ioe) {
                    destroyLocalPlayer();
                    Log.e(TAG, "Failed to open fallback ringtone");
                } catch (NotFoundException nfe) {
                    Log.e(TAG, "Fallback ringtone does not exist");
                }
            } else {
                Log.w(TAG, "not playing fallback for " + mUri);
            }
        }
        return false;
    }

    void setTitle(String title) {
        mTitle = title;
    }
}
