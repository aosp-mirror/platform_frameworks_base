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

import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.NotificationChannel;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.os.SomeArgs;

import java.util.List;

/**
 * A service that helps the user manage notifications.
 * <p>
 * Only one notification assistant can be active at a time. Unlike notification listener services,
 * assistant services can additionally modify certain aspects about notifications
 * (see {@link Adjustment}) before they are posted.
 *<p>
 * A note about managed profiles: Unlike {@link NotificationListenerService listener services},
 * NotificationAssistantServices are allowed to run in managed profiles
 * (see {@link DevicePolicyManager#isManagedProfile(ComponentName)}), so they can access the
 * information they need to create good {@link Adjustment adjustments}. To maintain the contract
 * with {@link NotificationListenerService}, an assistant service will receive all of the
 * callbacks from {@link NotificationListenerService} for the current user, managed profiles of
 * that user, and ones that affect all users. However,
 * {@link #onNotificationEnqueued(StatusBarNotification)} will only be called for notifications
 * sent to the current user, and {@link Adjustment adjuments} will only be accepted for the
 * current user.
 * <p>
 *     All callbacks are called on the main thread.
 * </p>
 *
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

    /**
     * @hide
     */
    protected Handler mHandler;

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
     * A notification was posted by an app. Called before post.
     *
     * @param sbn the new notification
     * @return an adjustment or null to take no action, within 100ms.
     */
    public Adjustment onNotificationEnqueued(StatusBarNotification sbn) {
        return null;
    }

    /**
     * A notification was posted by an app. Called before post.
     *
     * @param sbn the new notification
     * @param channel the channel the notification was posted to
     * @return an adjustment or null to take no action, within 100ms.
     */
    public Adjustment onNotificationEnqueued(StatusBarNotification sbn,
            NotificationChannel channel) {
        return onNotificationEnqueued(sbn);
    }


    /**
     * Implement this method to learn when notifications are removed, how they were interacted with
     * before removal, and why they were removed.
     * <p>
     * This might occur because the user has dismissed the notification using system UI (or another
     * notification listener) or because the app has withdrawn the notification.
     * <p>
     * NOTE: The {@link StatusBarNotification} object you receive will be "light"; that is, the
     * result from {@link StatusBarNotification#getNotification} may be missing some heavyweight
     * fields such as {@link android.app.Notification#contentView} and
     * {@link android.app.Notification#largeIcon}. However, all other fields on
     * {@link StatusBarNotification}, sufficient to match this call with a prior call to
     * {@link #onNotificationPosted(StatusBarNotification)}, will be intact.
     *
     ** @param sbn A data structure encapsulating at least the original information (tag and id)
     *            and source (package name) used to post the {@link android.app.Notification} that
     *            was just removed.
     * @param rankingMap The current ranking map that can be used to retrieve ranking information
     *                   for active notifications.
     * @param stats Stats about how the user interacted with the notification before it was removed.
     * @param reason see {@link #REASON_LISTENER_CANCEL}, etc.
     */
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
            NotificationStats stats, int reason) {
        onNotificationRemoved(sbn, rankingMap, reason);
    }

    /**
     * Implement this to know when a user has seen notifications, as triggered by
     * {@link #setNotificationsShown(String[])}.
     */
    public void onNotificationsSeen(List<String> keys) {

    }

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
        public void onNotificationEnqueuedWithChannel(IStatusBarNotificationHolder sbnHolder,
                NotificationChannel channel) {
            StatusBarNotification sbn;
            try {
                sbn = sbnHolder.get();
            } catch (RemoteException e) {
                Log.w(TAG, "onNotificationEnqueued: Error receiving StatusBarNotification", e);
                return;
            }

            SomeArgs args = SomeArgs.obtain();
            args.arg1 = sbn;
            args.arg2 = channel;
            mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_ENQUEUED,
                    args).sendToTarget();
        }

        @Override
        public void onNotificationSnoozedUntilContext(
                IStatusBarNotificationHolder sbnHolder, String snoozeCriterionId) {
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

        @Override
        public void onNotificationsSeen(List<String> keys) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = keys;
            mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATIONS_SEEN,
                    args).sendToTarget();
        }
    }

    private final class MyHandler extends Handler {
        public static final int MSG_ON_NOTIFICATION_ENQUEUED = 1;
        public static final int MSG_ON_NOTIFICATION_SNOOZED = 2;
        public static final int MSG_ON_NOTIFICATIONS_SEEN = 3;

        public MyHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_NOTIFICATION_ENQUEUED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    StatusBarNotification sbn = (StatusBarNotification) args.arg1;
                    NotificationChannel channel = (NotificationChannel) args.arg2;
                    args.recycle();
                    Adjustment adjustment = onNotificationEnqueued(sbn, channel);
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
                case MSG_ON_NOTIFICATIONS_SEEN: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    List<String> keys = (List<String>) args.arg1;
                    args.recycle();
                    onNotificationsSeen(keys);
                    break;
                }
            }
        }
    }
}
