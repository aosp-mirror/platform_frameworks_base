package com.android.onemedia;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.onemedia.playback.RequestUtils;

/**
 * Keeps track of a notification and updates it automatically for a given
 * MediaSession.
 */
public class NotificationHelper extends BroadcastReceiver {
    private static final String TAG = "NotificationHelper";

    private static final int NOTIFICATION_ID = 433; // John Cage, 1952

    private final Service mService;
    private final MediaSession mSession;
    private final MediaController mController;
    private final MediaController.TransportControls mTransportControls;
    private final SparseArray<PendingIntent> mIntents = new SparseArray<PendingIntent>();

    private PlaybackState mPlaybackState;
    private MediaMetadata mMetadata;

    private boolean mStarted = false;

    public NotificationHelper(Service service, MediaSession session) {
        mService = service;
        mSession = session;
        mController = session.getController();
        mTransportControls = mController.getTransportControls();
        String pkg = mService.getPackageName();

        mIntents.put(R.drawable.ic_pause, PendingIntent.getBroadcast(mService, 100, new Intent(
                com.android.onemedia.playback.RequestUtils.ACTION_PAUSE).setPackage(pkg),
                PendingIntent.FLAG_CANCEL_CURRENT));
        mIntents.put(R.drawable.ic_play_arrow, PendingIntent.getBroadcast(mService, 100,
                new Intent(com.android.onemedia.playback.RequestUtils.ACTION_PLAY).setPackage(pkg),
                PendingIntent.FLAG_CANCEL_CURRENT));
        mIntents.put(R.drawable.ic_skip_previous, PendingIntent.getBroadcast(mService, 100,
                new Intent(com.android.onemedia.playback.RequestUtils.ACTION_PREV).setPackage(pkg),
                PendingIntent.FLAG_CANCEL_CURRENT));
        mIntents.put(R.drawable.ic_skip_next, PendingIntent.getBroadcast(mService, 100,
                new Intent(com.android.onemedia.playback.RequestUtils.ACTION_NEXT).setPackage(pkg),
                PendingIntent.FLAG_CANCEL_CURRENT));
        mIntents.put(R.drawable.ic_fast_rewind, PendingIntent.getBroadcast(mService, 100,
                new Intent(com.android.onemedia.playback.RequestUtils.ACTION_REW).setPackage(pkg),
                PendingIntent.FLAG_CANCEL_CURRENT));
        mIntents.put(R.drawable.ic_fast_forward, PendingIntent.getBroadcast(mService, 100,
                new Intent(com.android.onemedia.playback.RequestUtils.ACTION_FFWD).setPackage(pkg),
                PendingIntent.FLAG_CANCEL_CURRENT));
    }

    /**
     * Posts the notification and starts tracking the session to keep it
     * updated. The notification will automatically be removed if the session is
     * destroyed before {@link #onStop} is called.
     */
    public void onStart() {
        mController.registerCallback(mCb);
        IntentFilter filter = new IntentFilter();
        filter.addAction(RequestUtils.ACTION_FFWD);
        filter.addAction(RequestUtils.ACTION_NEXT);
        filter.addAction(RequestUtils.ACTION_PAUSE);
        filter.addAction(RequestUtils.ACTION_PLAY);
        filter.addAction(RequestUtils.ACTION_PREV);
        filter.addAction(RequestUtils.ACTION_REW);
        mService.registerReceiver(this, filter);

        mMetadata = mController.getMetadata();
        mPlaybackState = mController.getPlaybackState();

        mStarted = true;
        // The notification must be updated after setting started to true
        updateNotification();
    }

    /**
     * Removes the notification and stops tracking the session. If the session
     * was destroyed this has no effect.
     */
    public void onStop() {
        mStarted = false;
        mController.unregisterCallback(mCb);
        mService.unregisterReceiver(this);
        updateNotification();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        Log.d(TAG, "Received intent with action " + action);
        if (RequestUtils.ACTION_PAUSE.equals(action)) {
            mTransportControls.pause();
        } else if (RequestUtils.ACTION_PLAY.equals(action)) {
            mTransportControls.play();
        } else if (RequestUtils.ACTION_NEXT.equals(action)) {
            mTransportControls.skipToNext();
        } else if (RequestUtils.ACTION_PREV.equals(action)) {
            mTransportControls.skipToPrevious();
        } else if (RequestUtils.ACTION_REW.equals(action)) {
            mTransportControls.rewind();
        } else if (RequestUtils.ACTION_FFWD.equals(action)) {
            mTransportControls.fastForward();
        }

    }

    private final MediaController.Callback mCb = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            mPlaybackState = state;
            Log.d(TAG, "Received new playback state" + state);
            updateNotification();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            mMetadata = metadata;
            Log.d(TAG, "Received new metadata " + metadata);
            updateNotification();
        }
    };

    NotificationManager mNoMan = null;

    private void updateNotification() {
        if (mNoMan == null) {
            mNoMan = (NotificationManager) mService.getSystemService(Context.NOTIFICATION_SERVICE);
        }
        if (mPlaybackState == null) {
            mNoMan.cancel(NOTIFICATION_ID);
            return;
        }
        if (!mStarted) {
            mNoMan.cancel(NOTIFICATION_ID);
            return;
        }

        String status;
        final int state = mPlaybackState.getState();
        switch (state) {
            case PlaybackState.STATE_PLAYING:
                status = "PLAYING: ";
                break;
            case PlaybackState.STATE_PAUSED:
                status = "PAUSED: ";
                break;
            case PlaybackState.STATE_STOPPED:
                status = "STOPPED: ";
                break;
            case PlaybackState.STATE_ERROR:
                status = "ERROR: ";
                break;
            case PlaybackState.STATE_BUFFERING:
                status = "BUFFERING: ";
                break;
            case PlaybackState.STATE_NONE:
            default:
                status = "";
                break;
        }
        CharSequence title, text;
        Bitmap art;
        if (mMetadata == null) {
            title = status;
            text = "Empty metadata!";
            art = null;
        } else {
            MediaDescription description = mMetadata.getDescription();
            title = description.getTitle();
            text = description.getSubtitle();
            art = description.getIconBitmap();
        }

        String playPauseLabel = "";
        int playPauseIcon;
        if (state == PlaybackState.STATE_PLAYING) {
            playPauseLabel = "Pause";
            playPauseIcon = R.drawable.ic_pause;
        } else {
            playPauseLabel = "Play";
            playPauseIcon = R.drawable.ic_play_arrow;
        }

        final long pos = mPlaybackState.getPosition();
        final long end = mMetadata == null ? 0 : mMetadata
                .getLong(MediaMetadata.METADATA_KEY_DURATION);
        Notification notification = new Notification.Builder(mService)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setShowWhen(false)
                .setContentInfo(DateUtils.formatElapsedTime(pos))
                .setProgress((int) end, (int) pos, false)
                .setLargeIcon(art)
                .addAction(R.drawable.ic_skip_previous, "Previous",
                        mIntents.get(R.drawable.ic_skip_previous))
                .addAction(R.drawable.ic_fast_rewind, "Rewind",
                        mIntents.get(R.drawable.ic_fast_rewind))
                .addAction(playPauseIcon, playPauseLabel,
                        mIntents.get(playPauseIcon))
                .addAction(R.drawable.ic_fast_forward, "Fast Forward",
                        mIntents.get(R.drawable.ic_fast_forward))
                .addAction(R.drawable.ic_skip_next, "Next",
                        mIntents.get(R.drawable.ic_skip_next))
                .setStyle(new Notification.MediaStyle()
                        .setShowActionsInCompactView(2)
                        .setMediaSession(mSession.getSessionToken()))
                .setColor(0xFFDB4437)
                .build();

        mService.startForeground(NOTIFICATION_ID, notification);
    }

}
