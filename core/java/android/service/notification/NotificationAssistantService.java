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
import android.app.Notification;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

/**
 * A service that helps the user manage notifications by modifying the
 * relative importance of notifications.
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@link android.Manifest.permission#BIND_NOTIFICATION_ASSISTANT_SERVICE} permission
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action. For example:</p>
 * <pre>
 * &lt;service android:name=".NotificationAssistant"
 *          android:label="&#64;string/service_name"
 *          android:permission="android.permission.BIND_NOTIFICATION_ASSISTANT_SERVICE">
 *     &lt;intent-filter>
 *         &lt;action android:name="android.service.notification.NotificationAssistantService" />
 *     &lt;/intent-filter>
 * &lt;/service></pre>
 */
public abstract class NotificationAssistantService extends NotificationListenerService {
    private static final String TAG = "NotificationAssistant";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE
            = "android.service.notification.NotificationAssistantService";

    /** Notification was canceled by the status bar reporting a click. */
    public static final int REASON_DELEGATE_CLICK = 1;

    /** Notification was canceled by the status bar reporting a user dismissal. */
    public static final int REASON_DELEGATE_CANCEL = 2;

    /** Notification was canceled by the status bar reporting a user dismiss all. */
    public static final int REASON_DELEGATE_CANCEL_ALL = 3;

    /** Notification was canceled by the status bar reporting an inflation error. */
    public static final int REASON_DELEGATE_ERROR = 4;

    /** Notification was canceled by the package manager modifying the package. */
    public static final int REASON_PACKAGE_CHANGED = 5;

    /** Notification was canceled by the owning user context being stopped. */
    public static final int REASON_USER_STOPPED = 6;

    /** Notification was canceled by the user banning the package. */
    public static final int REASON_PACKAGE_BANNED = 7;

    /** Notification was canceled by the app canceling this specific notification. */
    public static final int REASON_APP_CANCEL = 8;

    /** Notification was canceled by the app cancelling all its notifications. */
    public static final int REASON_APP_CANCEL_ALL = 9;

    /** Notification was canceled by a listener reporting a user dismissal. */
    public static final int REASON_LISTENER_CANCEL = 10;

    /** Notification was canceled by a listener reporting a user dismiss all. */
    public static final int REASON_LISTENER_CANCEL_ALL = 11;

    /** Notification was canceled because it was a member of a canceled group. */
    public static final int REASON_GROUP_SUMMARY_CANCELED = 12;

    /** Notification was canceled because it was an invisible member of a group. */
    public static final int REASON_GROUP_OPTIMIZATION = 13;

    public class Adjustment {
        int mImportance;
        CharSequence mExplanation;
        Uri mReference;

        /**
         * Create a notification importance adjustment.
         *
         * @param importance The final importance of the notification.
         * @param explanation A human-readable justification for the adjustment.
         * @param reference A reference to an external object that augments the
         *                  explanation, such as a
         *                  {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI},
         *                  or null.
         */
        public Adjustment(int importance, CharSequence explanation, Uri reference) {
            mImportance = importance;
            mExplanation = explanation;
            mReference = reference;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (mWrapper == null) {
            mWrapper = new NotificationAssistantWrapper();
        }
        return mWrapper;
    }

    /**
     * A notification was posted by an app. Called before alert.
     *
     * @param sbn the new notification
     * @param importance the initial importance of the notification.
     * @param user true if the initial importance reflects an explicit user preference.
     * @return an adjustment or null to take no action, within 100ms.
     */
    abstract public Adjustment onNotificationEnqueued(StatusBarNotification sbn,
          int importance, boolean user);

    /**
     * The visibility of a notification has changed.
     *
     * @param key the notification key
     * @param time milliseconds since midnight, January 1, 1970 UTC.
     * @param visible true if the notification became visible, false if hidden.
     */
    public void onNotificationVisibilityChanged(String key, long time, boolean visible)
    {
        // Do nothing, Override this to collect visibility statistics.
    }

    /**
     * The user clicked on a notification.
     *
     * @param key the notification key
     * @param time milliseconds since midnight, January 1, 1970 UTC.
     */
    public void onNotificationClick(String key, long time)
    {
        // Do nothing, Override this to collect click statistics
    }

    /**
     * The user clicked on a notification action.
     *
     * @param key the notification key
     * @param time milliseconds since midnight, January 1, 1970 UTC.
     * @param actionIndex the index of the action button that was pressed.
     */
    public void onNotificationActionClick(String key, long time, int actionIndex)
    {
        // Do nothing, Override this to collect action button click statistics
    }

    /**
     * A notification was removed.

     * @param key the notification key
     * @param time milliseconds since midnight, January 1, 1970 UTC.
     * @param reason see {@link #REASON_LISTENER_CANCEL}, etc.
     */
    public void onNotificationRemoved(String key, long time, int reason) {
        // Do nothing, Override this to collect dismissal statistics
    }

    /**
     * Change the importance of an existing notification.  N.B. this won’t cause
     * an existing notification to alert, but might allow a future update to
     * this notification to alert.
     *
     * @param key the notification key
     * @param adjustment the new importance with an explanation
     */
    public final void adjustImportance(String key, Adjustment adjustment)
    {
        if (!isBound()) return;
        try {
            getNotificationInterface().setImportanceFromAssistant(mWrapper, key,
                    adjustment.mImportance, adjustment.mExplanation);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
    }

    /**
     * Add an annotation to a an existing notification. The delete intent will
     * be fired when the host notification is deleted, or when this annotation
     * is removed or replaced.
     *
     * @param key the key of the notification to be annotated
     * @param annotation the new annotation object
     */
    public final void setAnnotation(String key, Notification annotation)
    {
        // TODO: pack up the annotation and send it to the NotificationManager.
    }

    /**
     * Remove the annotation from a notification.
     *
     * @param key the key of the notification to be cleansed of annotatons
     */
    public final void clearAnnotation(String key)
    {
        // TODO: ask the NotificationManager to clear the annotation.
    }

    private class NotificationAssistantWrapper extends NotificationListenerWrapper {
        @Override
        public void onNotificationEnqueued(IStatusBarNotificationHolder sbnHolder,
                                           int importance, boolean user) throws RemoteException {
            StatusBarNotification sbn;
            try {
                sbn = sbnHolder.get();
            } catch (RemoteException e) {
                Log.w(TAG, "onNotificationEnqueued: Error receiving StatusBarNotification", e);
                return;
            }

            try {
                Adjustment adjustment =
                    NotificationAssistantService.this.onNotificationEnqueued(sbn, importance, user);
                if (adjustment != null) {
                    adjustImportance(sbn.getKey(), adjustment);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error running onNotificationEnqueued", t);
            }
        }

        @Override
        public void onNotificationVisibilityChanged(String key, long time, boolean visible)
                throws RemoteException {
            try {
                NotificationAssistantService.this.onNotificationVisibilityChanged(key, time,
                        visible);
            } catch (Throwable t) {
                Log.w(TAG, "Error running onNotificationVisibilityChanged", t);
            }
        }

        @Override
        public void onNotificationClick(String key, long time) throws RemoteException {
            try {
                NotificationAssistantService.this.onNotificationClick(key, time);
            } catch (Throwable t) {
                Log.w(TAG, "Error running onNotificationClick", t);
            }
        }

        @Override
        public void onNotificationActionClick(String key, long time, int actionIndex)
                throws RemoteException {
            try {
                NotificationAssistantService.this.onNotificationActionClick(key, time, actionIndex);
            } catch (Throwable t) {
                Log.w(TAG, "Error running onNotificationActionClick", t);
            }
        }

        @Override
        public void onNotificationRemovedReason(String key, long time, int reason)
                throws RemoteException {
            try {
                NotificationAssistantService.this.onNotificationRemoved(key, time, reason);
            } catch (Throwable t) {
                Log.w(TAG, "Error running onNotificationRemoved", t);
            }
        }
    }
}
