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

package android.media.session;

import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataEditor;
import android.media.MediaMetadataRetriever;
import android.media.Rating;
import android.media.RemoteControlClient;
import android.media.RemoteControlClient.MetadataEditor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Helper for connecting existing APIs up to the new session APIs. This can be
 * used by RCC, AudioFocus, etc. to create a single session that translates to
 * all those components.
 *
 * @hide
 */
public class MediaSessionLegacyHelper {
    private static final String TAG = "MediaSessionHelper";
    private static final boolean DEBUG = true;

    private static final Object sLock = new Object();
    private static MediaSessionLegacyHelper sInstance;

    private Context mContext;
    private MediaSessionManager mSessionManager;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    // The legacy APIs use PendingIntents to register/unregister media button
    // receivers and these are associated with RCC.
    private ArrayMap<PendingIntent, SessionHolder> mSessions
            = new ArrayMap<PendingIntent, SessionHolder>();

    private MediaSessionLegacyHelper(Context context) {
        mContext = context;
        mSessionManager = (MediaSessionManager) context
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    public static MediaSessionLegacyHelper getHelper(Context context) {
        if (DEBUG) {
            Log.d(TAG, "Attempting to get helper with context " + context);
        }
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new MediaSessionLegacyHelper(context);
            }
        }
        return sInstance;
    }

    public static Bundle getOldMetadata(MediaMetadata metadata, int artworkWidth,
            int artworkHeight) {
        boolean includeArtwork = artworkWidth != -1 && artworkHeight != -1;
        Bundle oldMetadata = new Bundle();
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_ALBUM)) {
            oldMetadata.putString(String.valueOf(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
        }
        if (includeArtwork && metadata.containsKey(MediaMetadata.METADATA_KEY_ART)) {
            Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            oldMetadata.putParcelable(String.valueOf(MediaMetadataEditor.BITMAP_KEY_ARTWORK),
                    scaleBitmapIfTooBig(art, artworkWidth, artworkHeight));
        } else if (includeArtwork && metadata.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ART)) {
            // Fall back to album art if the track art wasn't available
            Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            oldMetadata.putParcelable(String.valueOf(MediaMetadataEditor.BITMAP_KEY_ARTWORK),
                    scaleBitmapIfTooBig(art, artworkWidth, artworkHeight));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)) {
            oldMetadata.putString(String.valueOf(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_ARTIST)) {
            oldMetadata.putString(String.valueOf(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_AUTHOR)) {
            oldMetadata.putString(String.valueOf(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                    metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_COMPILATION)) {
            oldMetadata.putString(String.valueOf(MediaMetadataRetriever.METADATA_KEY_COMPILATION),
                    metadata.getString(MediaMetadata.METADATA_KEY_COMPILATION));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_COMPOSER)) {
            oldMetadata.putString(String.valueOf(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                    metadata.getString(MediaMetadata.METADATA_KEY_COMPOSER));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_DATE)) {
            oldMetadata.putString(String.valueOf(MediaMetadataRetriever.METADATA_KEY_DATE),
                    metadata.getString(MediaMetadata.METADATA_KEY_DATE));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_DISC_NUMBER)) {
            oldMetadata.putLong(String.valueOf(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                    metadata.getLong(MediaMetadata.METADATA_KEY_DISC_NUMBER));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
            oldMetadata.putLong(String.valueOf(MediaMetadataRetriever.METADATA_KEY_DURATION),
                    metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_GENRE)) {
            oldMetadata.putString(String.valueOf(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    metadata.getString(MediaMetadata.METADATA_KEY_GENRE));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_NUM_TRACKS)) {
            oldMetadata.putLong(String.valueOf(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS),
                    metadata.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_RATING)) {
            oldMetadata.putParcelable(String.valueOf(MediaMetadataEditor.RATING_KEY_BY_OTHERS),
                    metadata.getRating(MediaMetadata.METADATA_KEY_RATING));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_USER_RATING)) {
            oldMetadata.putParcelable(String.valueOf(MediaMetadataEditor.RATING_KEY_BY_USER),
                    metadata.getRating(MediaMetadata.METADATA_KEY_USER_RATING));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_TITLE)) {
            oldMetadata.putString(String.valueOf(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_TRACK_NUMBER)) {
            oldMetadata.putLong(
                    String.valueOf(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                    metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_WRITER)) {
            oldMetadata.putString(String.valueOf(MediaMetadataRetriever.METADATA_KEY_WRITER),
                    metadata.getString(MediaMetadata.METADATA_KEY_WRITER));
        }
        if (metadata.containsKey(MediaMetadata.METADATA_KEY_YEAR)) {
            oldMetadata.putString(String.valueOf(MediaMetadataRetriever.METADATA_KEY_YEAR),
                    metadata.getString(MediaMetadata.METADATA_KEY_YEAR));
        }
        return oldMetadata;
    }

    public MediaSession getSession(PendingIntent pi) {
        SessionHolder holder = mSessions.get(pi);
        return holder == null ? null : holder.mSession;
    }

    public void sendMediaButtonEvent(KeyEvent keyEvent, boolean needWakeLock) {
        if (keyEvent == null) {
            Log.w(TAG, "Tried to send a null key event. Ignoring.");
            return;
        }
        mSessionManager.dispatchMediaKeyEvent(keyEvent, needWakeLock);
        if (DEBUG) {
            Log.d(TAG, "dispatched media key " + keyEvent);
        }
    }

    public void sendVolumeKeyEvent(KeyEvent keyEvent, boolean musicOnly) {
        if (keyEvent == null) {
            Log.w(TAG, "Tried to send a null key event. Ignoring.");
            return;
        }
        boolean down = keyEvent.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = keyEvent.getAction() == KeyEvent.ACTION_UP;
        int direction = 0;
        switch (keyEvent.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                direction = AudioManager.ADJUST_RAISE;
                break;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                direction = AudioManager.ADJUST_LOWER;
                break;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                // TODO
                break;
        }
        if ((down || up) && direction != 0) {
            int flags;
            // If this is action up we want to send a beep for non-music events
            if (up) {
                direction = 0;
            }
            if (musicOnly) {
                // This flag is used when the screen is off to only affect
                // active media
                flags = AudioManager.FLAG_ACTIVE_MEDIA_ONLY;
            } else {
                // These flags are consistent with the home screen
                if (up) {
                    flags = AudioManager.FLAG_PLAY_SOUND | AudioManager.FLAG_VIBRATE;
                } else {
                    flags = AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_VIBRATE;
                }
            }

            mSessionManager.dispatchAdjustVolume(AudioManager.USE_DEFAULT_STREAM_TYPE,
                    direction, flags);
        }
    }

    public void sendAdjustVolumeBy(int suggestedStream, int delta, int flags) {
        mSessionManager.dispatchAdjustVolume(suggestedStream, delta, flags);
        if (DEBUG) {
            Log.d(TAG, "dispatched volume adjustment");
        }
    }

    public void addRccListener(PendingIntent pi, MediaSession.Callback listener) {
        if (pi == null) {
            Log.w(TAG, "Pending intent was null, can't add rcc listener.");
            return;
        }
        SessionHolder holder = getHolder(pi, true);
        if (holder == null) {
            return;
        }
        if (holder.mRccListener != null) {
            if (holder.mRccListener == listener) {
                if (DEBUG) {
                    Log.d(TAG, "addRccListener listener already added.");
                }
                // This is already the registered listener, ignore
                return;
            }
        }
        holder.mRccListener = listener;
        holder.mFlags |= MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS;
        holder.mSession.setFlags(holder.mFlags);
        holder.update();
        if (DEBUG) {
            Log.d(TAG, "Added rcc listener for " + pi + ".");
        }
    }

    public void removeRccListener(PendingIntent pi) {
        if (pi == null) {
            return;
        }
        SessionHolder holder = getHolder(pi, false);
        if (holder != null && holder.mRccListener != null) {
            holder.mRccListener = null;
            holder.mFlags &= ~MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS;
            holder.mSession.setFlags(holder.mFlags);
            holder.update();
            if (DEBUG) {
                Log.d(TAG, "Removed rcc listener for " + pi + ".");
            }
        }
    }

    public void addMediaButtonListener(PendingIntent pi, ComponentName mbrComponent,
            Context context) {
        if (pi == null) {
            Log.w(TAG, "Pending intent was null, can't addMediaButtonListener.");
            return;
        }
        SessionHolder holder = getHolder(pi, true);
        if (holder == null) {
            return;
        }
        if (holder.mMediaButtonListener != null) {
            // Already have this listener registered
            if (DEBUG) {
                Log.d(TAG, "addMediaButtonListener already added " + pi);
            }
            return;
        }
        holder.mMediaButtonListener = new MediaButtonListener(pi, context);
        // TODO determine if handling transport performer commands should also
        // set this flag
        holder.mFlags |= MediaSession.FLAG_HANDLES_MEDIA_BUTTONS;
        holder.mSession.setFlags(holder.mFlags);
        holder.mSession.setMediaButtonReceiver(pi);
        holder.update();
        if (DEBUG) {
            Log.d(TAG, "addMediaButtonListener added " + pi);
        }
    }

    public void removeMediaButtonListener(PendingIntent pi) {
        if (pi == null) {
            return;
        }
        SessionHolder holder = getHolder(pi, false);
        if (holder != null && holder.mMediaButtonListener != null) {
            holder.mFlags &= ~MediaSession.FLAG_HANDLES_MEDIA_BUTTONS;
            holder.mSession.setFlags(holder.mFlags);
            holder.mMediaButtonListener = null;

            holder.update();
            if (DEBUG) {
                Log.d(TAG, "removeMediaButtonListener removed " + pi);
            }
        }
    }

    /**
     * Scale a bitmap to fit the smallest dimension by uniformly scaling the
     * incoming bitmap. If the bitmap fits, then do nothing and return the
     * original.
     *
     * @param bitmap
     * @param maxWidth
     * @param maxHeight
     * @return
     */
    private static Bitmap scaleBitmapIfTooBig(Bitmap bitmap, int maxWidth, int maxHeight) {
        if (bitmap != null) {
            final int width = bitmap.getWidth();
            final int height = bitmap.getHeight();
            if (width > maxWidth || height > maxHeight) {
                float scale = Math.min((float) maxWidth / width, (float) maxHeight / height);
                int newWidth = Math.round(scale * width);
                int newHeight = Math.round(scale * height);
                Bitmap.Config newConfig = bitmap.getConfig();
                if (newConfig == null) {
                    newConfig = Bitmap.Config.ARGB_8888;
                }
                Bitmap outBitmap = Bitmap.createBitmap(newWidth, newHeight, newConfig);
                Canvas canvas = new Canvas(outBitmap);
                Paint paint = new Paint();
                paint.setAntiAlias(true);
                paint.setFilterBitmap(true);
                canvas.drawBitmap(bitmap, null,
                        new RectF(0, 0, outBitmap.getWidth(), outBitmap.getHeight()), paint);
                bitmap = outBitmap;
            }
        }
        return bitmap;
    }

    private SessionHolder getHolder(PendingIntent pi, boolean createIfMissing) {
        SessionHolder holder = mSessions.get(pi);
        if (holder == null && createIfMissing) {
            MediaSession session;
            session = new MediaSession(mContext, TAG + "-" + pi.getCreatorPackage());
            session.setActive(true);
            holder = new SessionHolder(session, pi);
            mSessions.put(pi, holder);
        }
        return holder;
    }

    private static void sendKeyEvent(PendingIntent pi, Context context, Intent intent) {
        try {
            pi.send(context, 0, intent);
        } catch (CanceledException e) {
            Log.e(TAG, "Error sending media key down event:", e);
            // Don't bother sending up if down failed
            return;
        }
    }

    private static final class MediaButtonListener extends MediaSession.Callback {
        private final PendingIntent mPendingIntent;
        private final Context mContext;

        public MediaButtonListener(PendingIntent pi, Context context) {
            mPendingIntent = pi;
            mContext = context;
        }

        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
            MediaSessionLegacyHelper.sendKeyEvent(mPendingIntent, mContext, mediaButtonIntent);
            return true;
        }

        @Override
        public void onPlay() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY);
        }

        @Override
        public void onPause() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE);
        }

        @Override
        public void onSkipToNext() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT);
        }

        @Override
        public void onSkipToPrevious() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }

        @Override
        public void onFastForward() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        }

        @Override
        public void onRewind() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_REWIND);
        }

        @Override
        public void onStop() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_STOP);
        }

        private void sendKeyEvent(int keyCode) {
            KeyEvent ke = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
            Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

            intent.putExtra(Intent.EXTRA_KEY_EVENT, ke);
            MediaSessionLegacyHelper.sendKeyEvent(mPendingIntent, mContext, intent);

            ke = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
            intent.putExtra(Intent.EXTRA_KEY_EVENT, ke);
            MediaSessionLegacyHelper.sendKeyEvent(mPendingIntent, mContext, intent);

            if (DEBUG) {
                Log.d(TAG, "Sent " + keyCode + " to pending intent " + mPendingIntent);
            }
        }
    }

    private class SessionHolder {
        public final MediaSession mSession;
        public final PendingIntent mPi;
        public MediaButtonListener mMediaButtonListener;
        public MediaSession.Callback mRccListener;
        public int mFlags;

        public SessionCallback mCb;

        public SessionHolder(MediaSession session, PendingIntent pi) {
            mSession = session;
            mPi = pi;
        }

        public void update() {
            if (mMediaButtonListener == null && mRccListener == null) {
                mSession.setCallback(null);
                mSession.release();
                mCb = null;
                mSessions.remove(mPi);
            } else if (mCb == null) {
                mCb = new SessionCallback();
                mSession.setCallback(mCb);
            }
        }

        private class SessionCallback extends MediaSession.Callback {

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                if (mMediaButtonListener != null) {
                    mMediaButtonListener.onMediaButtonEvent(mediaButtonIntent);
                }
                return true;
            }

            @Override
            public void onPlay() {
                if (mMediaButtonListener != null) {
                    mMediaButtonListener.onPlay();
                }
            }

            @Override
            public void onPause() {
                if (mMediaButtonListener != null) {
                    mMediaButtonListener.onPause();
                }
            }

            @Override
            public void onSkipToNext() {
                if (mMediaButtonListener != null) {
                    mMediaButtonListener.onSkipToNext();
                }
            }

            @Override
            public void onSkipToPrevious() {
                if (mMediaButtonListener != null) {
                    mMediaButtonListener.onSkipToPrevious();
                }
            }

            @Override
            public void onFastForward() {
                if (mMediaButtonListener != null) {
                    mMediaButtonListener.onFastForward();
                }
            }

            @Override
            public void onRewind() {
                if (mMediaButtonListener != null) {
                    mMediaButtonListener.onRewind();
                }
            }

            @Override
            public void onStop() {
                if (mMediaButtonListener != null) {
                    mMediaButtonListener.onStop();
                }
            }

            @Override
            public void onSeekTo(long pos) {
                if (mRccListener != null) {
                    mRccListener.onSeekTo(pos);
                }
            }

            @Override
            public void onSetRating(Rating rating) {
                if (mRccListener != null) {
                    mRccListener.onSetRating(rating);
                }
            }
        }
    }
}
