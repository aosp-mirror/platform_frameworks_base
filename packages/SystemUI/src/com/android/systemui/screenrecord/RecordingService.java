/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.ThumbnailUtils;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.android.systemui.R;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * A service which records the device screen and optionally microphone input.
 */
public class RecordingService extends Service {
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "RecordingService";
    private static final String CHANNEL_ID = "screen_record";
    private static final String EXTRA_RESULT_CODE = "extra_resultCode";
    private static final String EXTRA_DATA = "extra_data";
    private static final String EXTRA_PATH = "extra_path";
    private static final String EXTRA_USE_AUDIO = "extra_useAudio";
    private static final String EXTRA_SHOW_TAPS = "extra_showTaps";
    private static final int REQUEST_CODE = 2;

    private static final String ACTION_START = "com.android.systemui.screenrecord.START";
    private static final String ACTION_STOP = "com.android.systemui.screenrecord.STOP";
    private static final String ACTION_PAUSE = "com.android.systemui.screenrecord.PAUSE";
    private static final String ACTION_RESUME = "com.android.systemui.screenrecord.RESUME";
    private static final String ACTION_CANCEL = "com.android.systemui.screenrecord.CANCEL";
    private static final String ACTION_SHARE = "com.android.systemui.screenrecord.SHARE";
    private static final String ACTION_DELETE = "com.android.systemui.screenrecord.DELETE";

    private static final int TOTAL_NUM_TRACKS = 1;
    private static final String RECORD_DIR = "Captures"; // TODO: use a translatable string
    private static final int VIDEO_BIT_RATE = 6000000;
    private static final int VIDEO_FRAME_RATE = 30;
    private static final int AUDIO_BIT_RATE = 16;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final String FILE_PROVIDER = "com.android.systemui.fileprovider";

    private MediaProjectionManager mMediaProjectionManager;
    private MediaProjection mMediaProjection;
    private Surface mInputSurface;
    private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;
    private Notification.Builder mRecordingNotificationBuilder;

    private boolean mUseAudio;
    private boolean mShowTaps;
    private File mTempFile;

    /**
     * Get an intent to start the recording service.
     *
     * @param context    Context from the requesting activity
     * @param resultCode The result code from {@link android.app.Activity#onActivityResult(int, int,
     *                   android.content.Intent)}
     * @param data       The data from {@link android.app.Activity#onActivityResult(int, int,
     *                   android.content.Intent)}
     * @param useAudio   True to enable microphone input while recording
     * @param showTaps   True to make touches visible while recording
     */
    public static Intent getStartIntent(Context context, int resultCode, Intent data,
            boolean useAudio, boolean showTaps) {
        return new Intent(context, RecordingService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_USE_AUDIO, useAudio)
                .putExtra(EXTRA_SHOW_TAPS, showTaps);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "RecordingService is starting");
        if (intent == null) {
            return Service.START_NOT_STICKY;
        }
        String action = intent.getAction();

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        switch (action) {
            case ACTION_START:
                int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
                mUseAudio = intent.getBooleanExtra(EXTRA_USE_AUDIO, false);
                mShowTaps = intent.getBooleanExtra(EXTRA_SHOW_TAPS, false);
                Intent data = intent.getParcelableExtra(EXTRA_DATA);
                if (data != null) {
                    mMediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);
                    startRecording();
                }
                break;

            case ACTION_CANCEL:
                stopRecording();

                // Delete temp file
                if (!mTempFile.delete()) {
                    Log.e(TAG, "Error canceling screen recording!");
                    Toast.makeText(this, R.string.screenrecord_delete_error, Toast.LENGTH_LONG)
                            .show();
                } else {
                    Toast.makeText(this, R.string.screenrecord_cancel_success, Toast.LENGTH_LONG)
                            .show();
                }

                // Close quick shade
                sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
                break;

            case ACTION_STOP:
                stopRecording();

                // Move temp file to user directory
                File recordDir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        RECORD_DIR);
                recordDir.mkdirs();

                String fileName = new SimpleDateFormat("'screen-'yyyyMMdd-HHmmss'.mp4'")
                        .format(new Date());
                Path path = new File(recordDir, fileName).toPath();

                try {
                    Files.move(mTempFile.toPath(), path);
                    Notification notification = createSaveNotification(path);
                    notificationManager.notify(NOTIFICATION_ID, notification);
                } catch (IOException e) {
                    e.printStackTrace();
                    Toast.makeText(this, R.string.screenrecord_delete_error, Toast.LENGTH_LONG)
                            .show();
                }
                break;

            case ACTION_PAUSE:
                mMediaRecorder.pause();
                setNotificationActions(true, notificationManager);
                break;

            case ACTION_RESUME:
                mMediaRecorder.resume();
                setNotificationActions(false, notificationManager);
                break;

            case ACTION_SHARE:
                File shareFile = new File(intent.getStringExtra(EXTRA_PATH));
                Uri shareUri = FileProvider.getUriForFile(this, FILE_PROVIDER, shareFile);

                Intent shareIntent = new Intent(Intent.ACTION_SEND)
                        .setType("video/mp4")
                        .putExtra(Intent.EXTRA_STREAM, shareUri);
                String shareLabel = getResources().getString(R.string.screenrecord_share_label);

                // Close quick shade
                sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));

                // Remove notification
                notificationManager.cancel(NOTIFICATION_ID);

                startActivity(Intent.createChooser(shareIntent, shareLabel)
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case ACTION_DELETE:
                // Close quick shade
                sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));

                File file = new File(intent.getStringExtra(EXTRA_PATH));
                if (file.delete()) {
                    Toast.makeText(
                            this,
                            R.string.screenrecord_delete_description,
                            Toast.LENGTH_LONG).show();

                    // Remove notification
                    notificationManager.cancel(NOTIFICATION_ID);
                } else {
                    Log.e(TAG, "Error deleting screen recording!");
                    Toast.makeText(this, R.string.screenrecord_delete_error, Toast.LENGTH_LONG)
                            .show();
                }
                break;
        }
        return Service.START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mMediaProjectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    /**
     * Begin the recording session
     */
    private void startRecording() {
        try {
            mTempFile = File.createTempFile("temp", ".mp4");
            Log.d(TAG, "Writing video output to: " + mTempFile.getAbsolutePath());

            setTapsVisible(mShowTaps);

            // Set up media recorder
            mMediaRecorder = new MediaRecorder();
            if (mUseAudio) {
                mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            // Set up video
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int screenWidth = metrics.widthPixels;
            int screenHeight = metrics.heightPixels;
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setVideoSize(screenWidth, screenHeight);
            mMediaRecorder.setVideoFrameRate(VIDEO_FRAME_RATE);
            mMediaRecorder.setVideoEncodingBitRate(VIDEO_BIT_RATE);

            // Set up audio
            if (mUseAudio) {
                mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                mMediaRecorder.setAudioChannels(TOTAL_NUM_TRACKS);
                mMediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE);
                mMediaRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE);
            }

            mMediaRecorder.setOutputFile(mTempFile);
            mMediaRecorder.prepare();

            // Create surface
            mInputSurface = mMediaRecorder.getSurface();
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                    "Recording Display",
                    screenWidth,
                    screenHeight,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mInputSurface,
                    null,
                    null);

            mMediaRecorder.start();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        createRecordingNotification();
    }

    private void createRecordingNotification() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.screenrecord_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(getString(R.string.screenrecord_channel_description));
        channel.enableVibration(true);
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(channel);

        mRecordingNotificationBuilder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_android)
                .setContentTitle(getResources().getString(R.string.screenrecord_name))
                .setUsesChronometer(true)
                .setOngoing(true);
        setNotificationActions(false, notificationManager);
        Notification notification = mRecordingNotificationBuilder.build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void setNotificationActions(boolean isPaused, NotificationManager notificationManager) {
        String pauseString = getResources()
                .getString(isPaused ? R.string.screenrecord_resume_label
                        : R.string.screenrecord_pause_label);
        Intent pauseIntent = isPaused ? getResumeIntent(this) : getPauseIntent(this);

        mRecordingNotificationBuilder.setActions(
                new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_android),
                        getResources().getString(R.string.screenrecord_stop_label),
                        PendingIntent
                                .getService(this, REQUEST_CODE, getStopIntent(this),
                                        PendingIntent.FLAG_UPDATE_CURRENT))
                        .build(),
                new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_android), pauseString,
                        PendingIntent.getService(this, REQUEST_CODE, pauseIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT))
                        .build(),
                new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_android),
                        getResources().getString(R.string.screenrecord_cancel_label),
                        PendingIntent
                                .getService(this, REQUEST_CODE, getCancelIntent(this),
                                        PendingIntent.FLAG_UPDATE_CURRENT))
                        .build());
        notificationManager.notify(NOTIFICATION_ID, mRecordingNotificationBuilder.build());
    }

    private Notification createSaveNotification(Path path) {
        Uri saveUri = FileProvider.getUriForFile(this, FILE_PROVIDER, path.toFile());
        Log.d(TAG, "Screen recording saved to " + path.toString());

        Intent viewIntent = new Intent(Intent.ACTION_VIEW)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setDataAndType(saveUri, "video/mp4");

        Notification.Action shareAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_android),
                getResources().getString(R.string.screenrecord_share_label),
                PendingIntent.getService(
                        this,
                        REQUEST_CODE,
                        getShareIntent(this, path.toString()),
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .build();

        Notification.Action deleteAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_android),
                getResources().getString(R.string.screenrecord_delete_label),
                PendingIntent.getService(
                        this,
                        REQUEST_CODE,
                        getDeleteIntent(this, path.toString()),
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .build();

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_android)
                .setContentTitle(getResources().getString(R.string.screenrecord_name))
                .setContentText(getResources().getString(R.string.screenrecord_save_message))
                .setContentIntent(PendingIntent.getActivity(
                        this,
                        REQUEST_CODE,
                        viewIntent,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION))
                .addAction(shareAction)
                .addAction(deleteAction)
                .setAutoCancel(true);

        // Add thumbnail if available
        Bitmap thumbnailBitmap = ThumbnailUtils.createVideoThumbnail(path.toString(),
                MediaStore.Video.Thumbnails.MINI_KIND);
        if (thumbnailBitmap != null) {
            Notification.BigPictureStyle pictureStyle = new Notification.BigPictureStyle()
                    .bigPicture(thumbnailBitmap)
                    .bigLargeIcon((Bitmap) null);
            builder.setLargeIcon(thumbnailBitmap).setStyle(pictureStyle);
        }
        return builder.build();
    }

    private void stopRecording() {
        setTapsVisible(false);
        mMediaRecorder.stop();
        mMediaRecorder.release();
        mMediaRecorder = null;
        mMediaProjection.stop();
        mMediaProjection = null;
        mInputSurface.release();
        mVirtualDisplay.release();
        stopSelf();
    }

    private void setTapsVisible(boolean turnOn) {
        int value = turnOn ? 1 : 0;
        Settings.System.putInt(getApplicationContext().getContentResolver(),
                Settings.System.SHOW_TOUCHES, value);
    }

    private static Intent getStopIntent(Context context) {
        return new Intent(context, RecordingService.class).setAction(ACTION_STOP);
    }

    private static Intent getPauseIntent(Context context) {
        return new Intent(context, RecordingService.class).setAction(ACTION_PAUSE);
    }

    private static Intent getResumeIntent(Context context) {
        return new Intent(context, RecordingService.class).setAction(ACTION_RESUME);
    }

    private static Intent getCancelIntent(Context context) {
        return new Intent(context, RecordingService.class).setAction(ACTION_CANCEL);
    }

    private static Intent getShareIntent(Context context, String path) {
        return new Intent(context, RecordingService.class).setAction(ACTION_SHARE)
                .putExtra(EXTRA_PATH, path);
    }

    private static Intent getDeleteIntent(Context context, String path) {
        return new Intent(context, RecordingService.class).setAction(ACTION_DELETE)
                .putExtra(EXTRA_PATH, path);
    }
}
