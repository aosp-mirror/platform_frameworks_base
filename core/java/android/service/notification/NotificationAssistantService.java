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
 * limitations under the License.
 */

package android.service.notification;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.NotificationChannel;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import com.android.internal.os.SomeArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * A service that helps the user manage notifications.
 * @hide
 */
@SystemApi
@TestApi
public abstract class NotificationAssistantService extends NotificationListenerService {
    private static final String TAG = "NotificationAssistants";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE
            = "android.service.notification.NotificationAssistantService";

    private Handler mHandler;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mHandler = new MyHandler(getContext().getMainLooper());
    }

    @Override
    public final IBinder onBind(Intent intent) {
        if (mWrapper == null) {
            mWrapper = new NotificationAssistantServiceWrapper();
        }
        return mWrapper;
    }

    /**
     * A notification was snoozed until a context. For use with
     * {@link Adjustment#KEY_SNOOZE_CRITERIA}. When the device reaches the given context, the
     * assistant should restore the notification with {@link #unsnoozeNotification(String)}.
     *
     * @param sbn the notification to snooze
     * @param snoozeCriterionId the {@link SnoozeCriterion#getId()} representing a device context.
     */
    abstract public void onNotificationSnoozedUntilContext(StatusBarNotification sbn,
            String snoozeCriterionId);

    /**
     * A notification was posted by an app. Called before alert.
     *
     * @param sbn the new notification
     * @return an adjustment or null to take no action, within 100ms.
     */
    abstract public Adjustment onNotificationEnqueued(StatusBarNotification sbn);

    /**
     * Updates a notification.  N.B. this won’t cause
     * an existing notification to alert, but might allow a future update to
     * this notification to alert.
     *
     * @param adjustment the adjustment with an explanation
     */
    public final void adjustNotification(Adjustment adjustment) {
        if (!isBound()) return;
        try {
            getNotificationInterface().applyAdjustmentFromAssistant(mWrapper, adjustment);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Updates existing notifications. Re-ranking won't occur until all adjustments are applied.
     * N.B. this won’t cause an existing notification to alert, but might allow a future update to
     * these notifications to alert.
     *
     * @param adjustments a list of adjustments with explanations
     */
    public final void adjustNotifications(List<Adjustment> adjustments) {
        if (!isBound()) return;
        try {
            getNotificationInterface().applyAdjustmentsFromAssistant(mWrapper, adjustments);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Inform the notification manager about un-snoozing a specific notification.
     * <p>
     * This should only be used for notifications snoozed by this listener using
     * {@link #snoozeNotification(String, String)}. Once un-snoozed, you will get a
     * {@link #onNotificationPosted(StatusBarNotification, RankingMap)} callback for the
     * notification.
     * @param key The key of the notification to snooze
     */
    public final void unsnoozeNotification(String key) {
        if (!isBound()) return;
        try {
            getNotificationInterface().unsnoozeNotificationFromAssistant(mWrapper, key);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
    }

    private class NotificationAssistantServiceWrapper extends NotificationListenerWrapper {
        @Override
        public void onNotificationEnqueued(IStatusBarNotificationHolder sbnHolder) {
            StatusBarNotification sbn;
            try {
                sbn = sbnHolder.get();
            } catch (RemoteException e) {
                Log.w(TAG, "onNotificationEnqueued: Error receiving StatusBarNotification", e);
                return;
            }

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = sbn;
            mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_ENQUEUED,
                    args).sendToTarget();
        }

        @Override
        public void onNotificationSnoozedUntilContext(
                IStatusBarNotificationHolder sbnHolder, String snoozeCriterionId)
                throws RemoteException {
            StatusBarNotification sbn;
            try {
                sbn = sbnHolder.get();
            } catch (RemoteException e) {
                Log.w(TAG, "onNotificationSnoozed: Error receiving StatusBarNotification", e);
                return;
            }

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = sbn;
            args.arg2 = snoozeCriterionId;
            mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_SNOOZED,
                    args).sendToTarget();
        }
    }

    private final class MyHandler extends Handler {
        public static final int MSG_ON_NOTIFICATION_ENQUEUED = 1;
        public static final int MSG_ON_NOTIFICATION_SNOOZED = 2;

        public MyHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_NOTIFICATION_ENQUEUED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    StatusBarNotification sbn = (StatusBarNotification) args.arg1;
                    args.recycle();
                    Adjustment adjustment = onNotificationEnqueued(sbn);
                    if (adjustment != null) {
                        if (!isBound()) return;
                        try {
                            getNotificationInterface().applyEnqueuedAdjustmentFromAssistant(
                                    mWrapper, adjustment);
                        } catch (android.os.RemoteException ex) {
                            Log.v(TAG, "Unable to contact notification manager", ex);
                            throw ex.rethrowFromSystemServer();
                        }
                    }
                    break;
                }
                case MSG_ON_NOTIFICATION_SNOOZED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    StatusBarNotification sbn = (StatusBarNotification) args.arg1;
                    String snoozeCriterionId = (String) args.arg2;
                    args.recycle();
                    onNotificationSnoozedUntilContext(sbn, snoozeCriterionId);
                    break;
                }
            }
        }
    }
}
