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

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.dagger.qualifiers.LongRunning;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget;
import com.android.systemui.res.R;
import com.android.systemui.screenrecord.ScreenMediaRecorder.ScreenMediaRecorderListener;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;

import java.io.IOException;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * A service which records the device screen and optionally microphone input.
 */
public class RecordingService extends Service implements ScreenMediaRecorderListener {
    public static final int REQUEST_CODE = 2;

    private static final int USER_ID_NOT_SPECIFIED = -1;
    protected static final int NOTIF_BASE_ID = 4273;
    private static final String TAG = "RecordingService";
    private static final String CHANNEL_ID = "screen_record";
    private static final String GROUP_KEY = "screen_record_saved";
    private static final String EXTRA_RESULT_CODE = "extra_resultCode";
    protected static final String EXTRA_PATH = "extra_path";
    private static final String EXTRA_AUDIO_SOURCE = "extra_useAudio";
    private static final String EXTRA_SHOW_TAPS = "extra_showTaps";
    private static final String EXTRA_CAPTURE_TARGET = "extra_captureTarget";

    protected static final String ACTION_START = "com.android.systemui.screenrecord.START";
    protected static final String ACTION_SHOW_START_NOTIF =
            "com.android.systemui.screenrecord.START_NOTIF";
    protected static final String ACTION_STOP = "com.android.systemui.screenrecord.STOP";
    protected static final String ACTION_STOP_NOTIF =
            "com.android.systemui.screenrecord.STOP_FROM_NOTIF";
    protected static final String ACTION_SHARE = "com.android.systemui.screenrecord.SHARE";
    private static final String PERMISSION_SELF = "com.android.systemui.permission.SELF";

    private final RecordingController mController;
    protected final KeyguardDismissUtil mKeyguardDismissUtil;
    private final Handler mMainHandler;
    private ScreenRecordingAudioSource mAudioSource = ScreenRecordingAudioSource.NONE;
    private boolean mShowTaps;
    private boolean mOriginalShowTaps;
    private ScreenMediaRecorder mRecorder;
    private final Executor mLongExecutor;
    private final UiEventLogger mUiEventLogger;
    protected final NotificationManager mNotificationManager;
    protected final UserContextProvider mUserContextTracker;
    protected int mNotificationId = NOTIF_BASE_ID;
    private RecordingServiceStrings mStrings;

    @Inject
    public RecordingService(RecordingController controller, @LongRunning Executor executor,
            @Main Handler handler, UiEventLogger uiEventLogger,
            NotificationManager notificationManager,
            UserContextProvider userContextTracker, KeyguardDismissUtil keyguardDismissUtil) {
        mController = controller;
        mLongExecutor = executor;
        mMainHandler = handler;
        mUiEventLogger = uiEventLogger;
        mNotificationManager = notificationManager;
        mUserContextTracker = userContextTracker;
        mKeyguardDismissUtil = keyguardDismissUtil;
    }

    /**
     * Get an intent to start the recording service.
     *
     * @param context    Context from the requesting activity
     * @param resultCode The result code from {@link android.app.Activity#onActivityResult(int, int,
     *                   android.content.Intent)}
     * @param audioSource   The ordinal value of the audio source
     *                      {@link com.android.systemui.screenrecord.ScreenRecordingAudioSource}
     * @param showTaps   True to make touches visible while recording
     * @param captureTarget   pass this parameter to capture a specific part instead
     *                        of the full screen
     */
    public static Intent getStartIntent(Context context, int resultCode,
            int audioSource, boolean showTaps,
            @Nullable MediaProjectionCaptureTarget captureTarget) {
        return new Intent(context, RecordingService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_AUDIO_SOURCE, audioSource)
                .putExtra(EXTRA_SHOW_TAPS, showTaps)
                .putExtra(EXTRA_CAPTURE_TARGET, captureTarget);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return Service.START_NOT_STICKY;
        }
        String action = intent.getAction();
        Log.d(getTag(), "onStartCommand " + action);
        NotificationChannel channel = new NotificationChannel(
                getChannelId(),
                getString(R.string.screenrecord_title),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(getString(R.string.screenrecord_channel_description));
        channel.enableVibration(true);
        mNotificationManager.createNotificationChannel(channel);

        int currentUid = Process.myUid();
        int currentUserId = mUserContextTracker.getUserContext().getUserId();
        UserHandle currentUser = new UserHandle(currentUserId);
        switch (action) {
            case ACTION_START:
                // Get a unique ID for this recording's notifications
                mNotificationId = NOTIF_BASE_ID + (int) SystemClock.uptimeMillis();
                mAudioSource = ScreenRecordingAudioSource
                        .values()[intent.getIntExtra(EXTRA_AUDIO_SOURCE, 0)];
                Log.d(getTag(), "recording with audio source " + mAudioSource);
                mShowTaps = intent.getBooleanExtra(EXTRA_SHOW_TAPS, false);
                MediaProjectionCaptureTarget captureTarget =
                        intent.getParcelableExtra(EXTRA_CAPTURE_TARGET,
                                MediaProjectionCaptureTarget.class);

                mOriginalShowTaps = Settings.System.getInt(
                        getApplicationContext().getContentResolver(),
                        Settings.System.SHOW_TOUCHES, 0) != 0;

                setTapsVisible(mShowTaps);

                mRecorder = new ScreenMediaRecorder(
                        mUserContextTracker.getUserContext(),
                        mMainHandler,
                        currentUid,
                        mAudioSource,
                        captureTarget,
                        this
                );

                if (startRecording()) {
                    updateState(true);
                    createRecordingNotification();
                    mUiEventLogger.log(Events.ScreenRecordEvent.SCREEN_RECORD_START);
                } else {
                    updateState(false);
                    createErrorNotification();
                    stopForeground(STOP_FOREGROUND_DETACH);
                    stopSelf();
                    return Service.START_NOT_STICKY;
                }
                break;
            case ACTION_SHOW_START_NOTIF:
                createRecordingNotification();
                mUiEventLogger.log(Events.ScreenRecordEvent.SCREEN_RECORD_START);
                break;
            case ACTION_STOP_NOTIF:
            case ACTION_STOP:
                // only difference for actions is the log event
                if (ACTION_STOP_NOTIF.equals(action)) {
                    mUiEventLogger.log(Events.ScreenRecordEvent.SCREEN_RECORD_END_NOTIFICATION);
                } else {
                    mUiEventLogger.log(Events.ScreenRecordEvent.SCREEN_RECORD_END_QS_TILE);
                }
                // Check user ID - we may be getting a stop intent after user switch, in which case
                // we want to post the notifications for that user, which is NOT current user
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, USER_ID_NOT_SPECIFIED);
                stopService(userId);
                break;

            case ACTION_SHARE:
                Uri shareUri = intent.getParcelableExtra(EXTRA_PATH, Uri.class);

                Intent shareIntent = new Intent(Intent.ACTION_SEND)
                        .setType("video/mp4")
                        .putExtra(Intent.EXTRA_STREAM, shareUri);
                mKeyguardDismissUtil.executeWhenUnlocked(() -> {
                    String shareLabel = strings().getShareLabel();
                    startActivity(Intent.createChooser(shareIntent, shareLabel)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    // Remove notification
                    mNotificationManager.cancelAsUser(null, mNotificationId, currentUser);
                    return false;
                }, false, false);

                // Close quick shade
                closeSystemDialogs();
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
    }

    @Nullable
    @VisibleForTesting
    protected ScreenMediaRecorder getRecorder() {
        return mRecorder;
    }

    private void updateState(boolean state) {
        int userId = mUserContextTracker.getUserContext().getUserId();
        if (userId == UserHandle.USER_SYSTEM) {
            // Main user has a reference to the correct controller, so no need to use a broadcast
            mController.updateState(state);
        } else {
            Intent intent = new Intent(RecordingController.INTENT_UPDATE_STATE);
            intent.putExtra(RecordingController.EXTRA_STATE, state);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            sendBroadcast(intent, PERMISSION_SELF);
        }
    }

    /**
     * Begin the recording session
     * @return true if successful, false if something went wrong
     */
    private boolean startRecording() {
        try {
            getRecorder().start();
            return true;
        } catch (IOException | RemoteException | RuntimeException e) {
            showErrorToast(R.string.screenrecord_start_error);
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Simple error notification, needed since startForeground must be called to avoid errors
     */
    @VisibleForTesting
    protected void createErrorNotification() {
        Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, strings().getTitle());
        String notificationTitle = strings().getStartError();

        Notification.Builder builder = new Notification.Builder(this, getChannelId())
                .setSmallIcon(R.drawable.ic_screenrecord)
                .setContentTitle(notificationTitle)
                .addExtras(extras);
        startForeground(mNotificationId, builder.build());
    }

    @VisibleForTesting
    protected void showErrorToast(int stringId) {
        Toast.makeText(this, stringId, Toast.LENGTH_LONG).show();
    }

    @VisibleForTesting
    protected void createRecordingNotification() {
        Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, strings().getTitle());

        String notificationTitle = mAudioSource == ScreenRecordingAudioSource.NONE
                ? strings().getOngoingRecording()
                : strings().getOngoingRecordingWithAudio();

        PendingIntent pendingIntent = PendingIntent.getService(
                this,
                REQUEST_CODE,
                getNotificationIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_android),
                strings().getStopLabel(),
                pendingIntent).build();
        Notification.Builder builder = new Notification.Builder(this, getChannelId())
                .setSmallIcon(R.drawable.ic_screenrecord)
                .setContentTitle(notificationTitle)
                .setUsesChronometer(true)
                .setColorized(true)
                .setColor(getResources().getColor(R.color.GM2_red_700))
                .setOngoing(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(stopAction)
                .addExtras(extras);
        startForeground(mNotificationId, builder.build());
    }

    @VisibleForTesting
    protected Notification createProcessingNotification() {
        String notificationTitle = mAudioSource == ScreenRecordingAudioSource.NONE
                ? strings().getOngoingRecording()
                : strings().getOngoingRecordingWithAudio();

        Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, strings().getTitle());

        Notification.Builder builder = new Notification.Builder(this, getChannelId())
                .setContentTitle(notificationTitle)
                .setContentText(
                        strings().getBackgroundProcessingLabel())
                .setSmallIcon(R.drawable.ic_screenrecord)
                .setGroup(GROUP_KEY)
                .addExtras(extras);
        return builder.build();
    }

    @VisibleForTesting
    protected Notification createSaveNotification(
            @Nullable ScreenMediaRecorder.SavedRecording recording) {
        Uri uri = recording != null ? recording.getUri() : null;
        Intent viewIntent = new Intent(Intent.ACTION_VIEW)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setDataAndType(uri, "video/mp4");

        Notification.Action shareAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_screenrecord),
                strings().getShareLabel(),
                PendingIntent.getService(
                        this,
                        REQUEST_CODE,
                        getShareIntent(this, uri),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .build();

        Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, strings().getTitle());

        Notification.Builder builder = new Notification.Builder(this, getChannelId())
                .setSmallIcon(R.drawable.ic_screenrecord)
                .setContentTitle(strings().getSaveTitle())
                .setContentText(strings().getSaveText())
                .setContentIntent(PendingIntent.getActivity(
                        this,
                        REQUEST_CODE,
                        viewIntent,
                        PendingIntent.FLAG_IMMUTABLE))
                .addAction(shareAction)
                .setAutoCancel(true)
                .setGroup(GROUP_KEY)
                .addExtras(extras);

        // Add thumbnail if available
        Icon thumbnail = recording != null ? recording.getThumbnail() : null;
        if (thumbnail != null) {
            Notification.BigPictureStyle pictureStyle = new Notification.BigPictureStyle()
                    .bigPicture(thumbnail)
                    .showBigPictureWhenCollapsed(true);
            builder.setStyle(pictureStyle);
        }
        return builder.build();
    }

    /**
     * Adds a group notification so that save notifications from multiple recordings are
     * grouped together, and the foreground service recording notification is not
     */
    private void postGroupNotification(UserHandle currentUser) {
        Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME,
                strings().getTitle());
        Notification groupNotif = new Notification.Builder(this, getChannelId())
                .setSmallIcon(R.drawable.ic_screenrecord)
                .setContentTitle(strings().getSaveTitle())
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setExtras(extras)
                .build();
        mNotificationManager.notifyAsUser(getTag(), NOTIF_BASE_ID, groupNotif, currentUser);
    }

    private void stopService() {
        stopService(USER_ID_NOT_SPECIFIED);
    }

    private void stopService(int userId) {
        if (userId == USER_ID_NOT_SPECIFIED) {
            userId = mUserContextTracker.getUserContext().getUserId();
        }
        Log.d(getTag(), "notifying for user " + userId);
        setTapsVisible(mOriginalShowTaps);
        try {
            if (getRecorder() != null) {
                getRecorder().end();
            }
            saveRecording(userId);
        } catch (RuntimeException exception) {
            if (getRecorder() != null) {
                // RuntimeException could happen if the recording stopped immediately after starting
                // let's release the recorder and delete all temporary files in this case
                getRecorder().release();
            }
            showErrorToast(R.string.screenrecord_start_error);
            Log.e(getTag(), "stopRecording called, but there was an error when ending"
                    + "recording");
            exception.printStackTrace();
            createErrorNotification();
        } catch (Throwable throwable) {
            if (getRecorder() != null) {
                // Something unexpected happen, SystemUI will crash but let's delete
                // the temporary files anyway
                getRecorder().release();
            }
            throw new RuntimeException(throwable);
        }
        updateState(false);
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    private void saveRecording(int userId) {
        UserHandle currentUser = new UserHandle(userId);
        mNotificationManager.notifyAsUser(null, mNotificationId,
                createProcessingNotification(), currentUser);

        mLongExecutor.execute(() -> {
            try {
                Log.d(getTag(), "saving recording");
                Notification notification = createSaveNotification(
                        getRecorder() != null ? getRecorder().save() : null);
                postGroupNotification(currentUser);
                mNotificationManager.notifyAsUser(null, mNotificationId,  notification,
                        currentUser);
            } catch (IOException | IllegalStateException e) {
                Log.e(getTag(), "Error saving screen recording: " + e.getMessage());
                e.printStackTrace();
                showErrorToast(R.string.screenrecord_save_error);
                mNotificationManager.cancelAsUser(null, mNotificationId, currentUser);
            }
        });
    }

    private void setTapsVisible(boolean turnOn) {
        int value = turnOn ? 1 : 0;
        Settings.System.putInt(getContentResolver(), Settings.System.SHOW_TOUCHES, value);
    }

    protected String getTag() {
        return TAG;
    }

    protected String getChannelId() {
        return CHANNEL_ID;
    }

    private RecordingServiceStrings strings() {
        if (mStrings == null) {
            mStrings = provideRecordingServiceStrings();
        }
        return mStrings;
    }

    protected RecordingServiceStrings provideRecordingServiceStrings() {
        return new RecordingServiceStrings(getResources());
    }


    /**
     * Get an intent to stop the recording service.
     * @param context Context from the requesting activity
     * @return
     */
    public static Intent getStopIntent(Context context) {
        return new Intent(context, RecordingService.class)
                .setAction(ACTION_STOP)
                .putExtra(Intent.EXTRA_USER_HANDLE, context.getUserId());
    }

    /**
     * Get the recording notification content intent
     * @param context
     * @return
     */
    protected Intent getNotificationIntent(Context context) {
        return new Intent(context, this.getClass()).setAction(ACTION_STOP_NOTIF);
    }

    private Intent getShareIntent(Context context, Uri path) {
        return new Intent(context, this.getClass()).setAction(ACTION_SHARE)
                .putExtra(EXTRA_PATH, path);
    }

    @Override
    public void onInfo(MediaRecorder mr, int what, int extra) {
        Log.d(getTag(), "Media recorder info: " + what);
        onStartCommand(getStopIntent(this), 0, 0);
    }

    @Override
    public void onStopped() {
        if (mController.isRecording()) {
            Log.d(getTag(), "Stopping recording because the system requested the stop");
            stopService();
        }
    }
}
