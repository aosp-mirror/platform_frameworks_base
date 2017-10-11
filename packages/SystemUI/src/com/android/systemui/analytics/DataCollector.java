/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.analytics;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static com.android.systemui.statusbar.phone.nano.TouchAnalyticsProto.Session;
import static com.android.systemui.statusbar.phone.nano.TouchAnalyticsProto.Session.PhoneEvent;

/**
 * Tracks touch, sensor and phone events when the lockscreen is on. If the phone is unlocked
 * the data containing these events is saved to a file. This data is collected
 * to analyze how a human interaction looks like.
 *
 * A session starts when the screen is turned on.
 * A session ends when the screen is turned off or user unlocks the phone.
 */
public class DataCollector implements SensorEventListener {
    private static final String TAG = "DataCollector";
    private static final String COLLECTOR_ENABLE = "data_collector_enable";
    private static final String COLLECT_BAD_TOUCHES = "data_collector_collect_bad_touches";
    private static final String ALLOW_REJECTED_TOUCH_REPORTS =
            "data_collector_allow_rejected_touch_reports";

    private static final long TIMEOUT_MILLIS = 11000; // 11 seconds.
    public static final boolean DEBUG = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Context mContext;

    // Err on the side of caution, so logging is not started after a crash even tough the screen
    // is off.
    private SensorLoggerSession mCurrentSession = null;

    private boolean mEnableCollector = false;
    private boolean mTimeoutActive = false;
    private boolean mCollectBadTouches = false;
    private boolean mCornerSwiping = false;
    private boolean mTrackingStarted = false;
    private boolean mAllowReportRejectedTouch = false;

    private static DataCollector sInstance = null;

    protected final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateConfiguration();
        }
    };

    private DataCollector(Context context) {
        mContext = context;

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(COLLECTOR_ENABLE), false,
                mSettingsObserver,
                UserHandle.USER_ALL);

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(COLLECT_BAD_TOUCHES), false,
                mSettingsObserver,
                UserHandle.USER_ALL);

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(ALLOW_REJECTED_TOUCH_REPORTS), false,
                mSettingsObserver,
                UserHandle.USER_ALL);

        updateConfiguration();
    }

    public static DataCollector getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DataCollector(context);
        }
        return sInstance;
    }

    private void updateConfiguration() {
        mEnableCollector = Build.IS_DEBUGGABLE && 0 != Settings.Secure.getInt(
                mContext.getContentResolver(),
                COLLECTOR_ENABLE, 0);
        mCollectBadTouches = mEnableCollector && 0 != Settings.Secure.getInt(
                mContext.getContentResolver(),
                COLLECT_BAD_TOUCHES, 0);
        mAllowReportRejectedTouch = Build.IS_DEBUGGABLE && 0 != Settings.Secure.getInt(
                mContext.getContentResolver(),
                ALLOW_REJECTED_TOUCH_REPORTS, 0);
    }

    private boolean sessionEntrypoint() {
        if (isEnabled() && mCurrentSession == null) {
            onSessionStart();
            return true;
        }
        return false;
    }

    private void sessionExitpoint(int result) {
        if (mCurrentSession != null) {
            onSessionEnd(result);
        }
    }

    private void onSessionStart() {
        mCornerSwiping = false;
        mTrackingStarted = false;
        mCurrentSession = new SensorLoggerSession(System.currentTimeMillis(), System.nanoTime());
    }

    private void onSessionEnd(int result) {
        SensorLoggerSession session = mCurrentSession;
        mCurrentSession = null;

        if (mEnableCollector) {
            session.end(System.currentTimeMillis(), result);
            queueSession(session);
        }
    }

    public Uri reportRejectedTouch() {
        if (mCurrentSession == null) {
            Toast.makeText(mContext, "Generating rejected touch report failed: session timed out.",
                    Toast.LENGTH_LONG).show();
            return null;
        }
        SensorLoggerSession currentSession = mCurrentSession;

        currentSession.setType(Session.REJECTED_TOUCH_REPORT);
        currentSession.end(System.currentTimeMillis(), Session.SUCCESS);
        Session proto = currentSession.toProto();

        byte[] b = Session.toByteArray(proto);
        File dir = new File(mContext.getExternalCacheDir(), "rejected_touch_reports");
        dir.mkdir();
        File touch = new File(dir, "rejected_touch_report_" + System.currentTimeMillis());

        try {
            new FileOutputStream(touch).write(b);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return Uri.fromFile(touch);
    }

    private void queueSession(final SensorLoggerSession currentSession) {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                byte[] b = Session.toByteArray(currentSession.toProto());
                String dir = mContext.getFilesDir().getAbsolutePath();
                if (currentSession.getResult() != Session.SUCCESS) {
                    if (!mCollectBadTouches) {
                        return;
                    }
                    dir += "/bad_touches";
                } else {
                    dir += "/good_touches";
                }

                File file = new File(dir);
                file.mkdir();
                File touch = new File(file, "trace_" + System.currentTimeMillis());

                try {
                    new FileOutputStream(touch).write(b);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @Override
    public synchronized void onSensorChanged(SensorEvent event) {
        if (isEnabled() && mCurrentSession != null) {
            mCurrentSession.addSensorEvent(event, System.nanoTime());
            enforceTimeout();
        }
    }

    private void enforceTimeout() {
        if (mTimeoutActive) {
            if (System.currentTimeMillis() - mCurrentSession.getStartTimestampMillis()
                    > TIMEOUT_MILLIS) {
                onSessionEnd(Session.UNKNOWN);
                if (DEBUG) {
                    Log.i(TAG, "Analytics timed out.");
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /**
     * @return true if data is being collected - either for data gathering or creating a
     *         rejected touch report.
     */
    public boolean isEnabled() {
        return mEnableCollector || mAllowReportRejectedTouch;
    }

    /**
     * @return true if the full data set for data gathering should be collected - including
     *         extensive sensor data, which is is not normally included with rejected touch reports.
     */
    public boolean isEnabledFull() {
        return mEnableCollector;
    }

    public void onScreenTurningOn() {
        if (sessionEntrypoint()) {
            if (DEBUG) {
                Log.d(TAG, "onScreenTurningOn");
            }
            addEvent(PhoneEvent.ON_SCREEN_ON);
        }
    }

    public void onScreenOnFromTouch() {
        if (sessionEntrypoint()) {
            if (DEBUG) {
                Log.d(TAG, "onScreenOnFromTouch");
            }
            addEvent(PhoneEvent.ON_SCREEN_ON_FROM_TOUCH);
        }
    }

    public void onScreenOff() {
        if (DEBUG) {
            Log.d(TAG, "onScreenOff");
        }
        addEvent(PhoneEvent.ON_SCREEN_OFF);
        sessionExitpoint(Session.FAILURE);
    }

    public void onSucccessfulUnlock() {
        if (DEBUG) {
            Log.d(TAG, "onSuccessfulUnlock");
        }
        addEvent(PhoneEvent.ON_SUCCESSFUL_UNLOCK);
        sessionExitpoint(Session.SUCCESS);
    }

    public void onBouncerShown() {
        if (DEBUG) {
            Log.d(TAG, "onBouncerShown");
        }
        addEvent(PhoneEvent.ON_BOUNCER_SHOWN);
    }

    public void onBouncerHidden() {
        if (DEBUG) {
            Log.d(TAG, "onBouncerHidden");
        }
        addEvent(PhoneEvent.ON_BOUNCER_HIDDEN);
    }

    public void onQsDown() {
        if (DEBUG) {
            Log.d(TAG, "onQsDown");
        }
        addEvent(PhoneEvent.ON_QS_DOWN);
    }

    public void setQsExpanded(boolean expanded) {
        if (DEBUG) {
            Log.d(TAG, "setQsExpanded = " + expanded);
        }
        if (expanded) {
            addEvent(PhoneEvent.SET_QS_EXPANDED_TRUE);
        } else {
            addEvent(PhoneEvent.SET_QS_EXPANDED_FALSE);
        }
    }

    public void onTrackingStarted() {
        if (DEBUG) {
            Log.d(TAG, "onTrackingStarted");
        }
        mTrackingStarted = true;
        addEvent(PhoneEvent.ON_TRACKING_STARTED);
    }

    public void onTrackingStopped() {
        if (mTrackingStarted) {
            if (DEBUG) {
                Log.d(TAG, "onTrackingStopped");
            }
            mTrackingStarted = false;
            addEvent(PhoneEvent.ON_TRACKING_STOPPED);
        }
    }

    public void onNotificationActive() {
        if (DEBUG) {
            Log.d(TAG, "onNotificationActive");
        }
        addEvent(PhoneEvent.ON_NOTIFICATION_ACTIVE);
    }


    public void onNotificationDoubleTap() {
        if (DEBUG) {
            Log.d(TAG, "onNotificationDoubleTap");
        }
        addEvent(PhoneEvent.ON_NOTIFICATION_DOUBLE_TAP);
    }

    public void setNotificationExpanded() {
        if (DEBUG) {
            Log.d(TAG, "setNotificationExpanded");
        }
        addEvent(PhoneEvent.SET_NOTIFICATION_EXPANDED);
    }

    public void onNotificatonStartDraggingDown() {
        if (DEBUG) {
            Log.d(TAG, "onNotificationStartDraggingDown");
        }
        addEvent(PhoneEvent.ON_NOTIFICATION_START_DRAGGING_DOWN);
    }

    public void onNotificatonStopDraggingDown() {
        if (DEBUG) {
            Log.d(TAG, "onNotificationStopDraggingDown");
        }
        addEvent(PhoneEvent.ON_NOTIFICATION_STOP_DRAGGING_DOWN);
    }

    public void onNotificationDismissed() {
        if (DEBUG) {
            Log.d(TAG, "onNotificationDismissed");
        }
        addEvent(PhoneEvent.ON_NOTIFICATION_DISMISSED);
    }

    public void onNotificatonStartDismissing() {
        if (DEBUG) {
            Log.d(TAG, "onNotificationStartDismissing");
        }
        addEvent(PhoneEvent.ON_NOTIFICATION_START_DISMISSING);
    }

    public void onNotificatonStopDismissing() {
        if (DEBUG) {
            Log.d(TAG, "onNotificationStopDismissing");
        }
        addEvent(PhoneEvent.ON_NOTIFICATION_STOP_DISMISSING);
    }

    public void onCameraOn() {
        if (DEBUG) {
            Log.d(TAG, "onCameraOn");
        }
        addEvent(PhoneEvent.ON_CAMERA_ON);
    }

    public void onLeftAffordanceOn() {
        if (DEBUG) {
            Log.d(TAG, "onLeftAffordanceOn");
        }
        addEvent(PhoneEvent.ON_LEFT_AFFORDANCE_ON);
    }

    public void onAffordanceSwipingStarted(boolean rightCorner) {
        if (DEBUG) {
            Log.d(TAG, "onAffordanceSwipingStarted");
        }
        mCornerSwiping = true;
        if (rightCorner) {
            addEvent(PhoneEvent.ON_RIGHT_AFFORDANCE_SWIPING_STARTED);
        } else {
            addEvent(PhoneEvent.ON_LEFT_AFFORDANCE_SWIPING_STARTED);
        }
    }

    public void onAffordanceSwipingAborted() {
        if (mCornerSwiping) {
            if (DEBUG) {
                Log.d(TAG, "onAffordanceSwipingAborted");
            }
            mCornerSwiping = false;
            addEvent(PhoneEvent.ON_AFFORDANCE_SWIPING_ABORTED);
        }
    }

    public void onUnlockHintStarted() {
        if (DEBUG) {
            Log.d(TAG, "onUnlockHintStarted");
        }
        addEvent(PhoneEvent.ON_UNLOCK_HINT_STARTED);
    }

    public void onCameraHintStarted() {
        if (DEBUG) {
            Log.d(TAG, "onCameraHintStarted");
        }
        addEvent(PhoneEvent.ON_CAMERA_HINT_STARTED);
    }

    public void onLeftAffordanceHintStarted() {
        if (DEBUG) {
            Log.d(TAG, "onLeftAffordanceHintStarted");
        }
        addEvent(PhoneEvent.ON_LEFT_AFFORDANCE_HINT_STARTED);
    }

    public void onTouchEvent(MotionEvent event, int width, int height) {
        if (mCurrentSession != null) {
            if (DEBUG) {
                Log.v(TAG, "onTouchEvent(ev.action="
                        + MotionEvent.actionToString(event.getAction()) + ")");
            }
            mCurrentSession.addMotionEvent(event);
            mCurrentSession.setTouchArea(width, height);
            enforceTimeout();
        }
    }

    private void addEvent(int eventType) {
        if (isEnabled() && mCurrentSession != null) {
            mCurrentSession.addPhoneEvent(eventType, System.nanoTime());
        }
    }

    public boolean isReportingEnabled() {
        return mAllowReportRejectedTouch;
    }
}
