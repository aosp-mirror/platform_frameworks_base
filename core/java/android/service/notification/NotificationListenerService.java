/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.annotation.PrivateApi;
import android.annotation.SdkConstant;
import android.app.INotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * A service that receives calls from the system when new notifications are
 * posted or removed, or their ranking changed.
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@link android.Manifest.permission#BIND_NOTIFICATION_LISTENER_SERVICE} permission
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action. For example:</p>
 * <pre>
 * &lt;service android:name=".NotificationListener"
 *          android:label="&#64;string/service_name"
 *          android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
 *     &lt;intent-filter>
 *         &lt;action android:name="android.service.notification.NotificationListenerService" />
 *     &lt;/intent-filter>
 * &lt;/service></pre>
 */
public abstract class NotificationListenerService extends Service {
    // TAG = "NotificationListenerService[MySubclass]"
    private final String TAG = NotificationListenerService.class.getSimpleName()
            + "[" + getClass().getSimpleName() + "]";

    private INotificationListenerWrapper mWrapper = null;
    private Ranking mRanking;

    private INotificationManager mNoMan;

    /** Only valid after a successful call to (@link registerAsService}. */
    private int mCurrentUser;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE
            = "android.service.notification.NotificationListenerService";

    /**
     * Implement this method to learn about new notifications as they are posted by apps.
     *
     * @param sbn A data structure encapsulating the original {@link android.app.Notification}
     *            object as well as its identifying information (tag and id) and source
     *            (package name).
     */
    public abstract void onNotificationPosted(StatusBarNotification sbn);

    /**
     * Implement this method to learn when notifications are removed.
     * <P>
     * This might occur because the user has dismissed the notification using system UI (or another
     * notification listener) or because the app has withdrawn the notification.
     * <P>
     * NOTE: The {@link StatusBarNotification} object you receive will be "light"; that is, the
     * result from {@link StatusBarNotification#getNotification} may be missing some heavyweight
     * fields such as {@link android.app.Notification#contentView} and
     * {@link android.app.Notification#largeIcon}. However, all other fields on
     * {@link StatusBarNotification}, sufficient to match this call with a prior call to
     * {@link #onNotificationPosted(StatusBarNotification)}, will be intact.
     *
     * @param sbn A data structure encapsulating at least the original information (tag and id)
     *            and source (package name) used to post the {@link android.app.Notification} that
     *            was just removed.
     */
    public abstract void onNotificationRemoved(StatusBarNotification sbn);

    /**
     * Implement this method to learn about when the listener is enabled and connected to
     * the notification manager.  You are safe to call {@link #getActiveNotifications(String[])
     * at this time.
     *
     * @param notificationKeys The notification keys for all currently posted notifications.
     */
    public void onListenerConnected(String[] notificationKeys) {
        // optional
    }

    /**
     * Implement this method to be notified when the notification ranking changes.
     * <P>
     * Call {@link #getCurrentRanking()} to retrieve the new ranking.
     */
    public void onNotificationRankingUpdate() {
        // optional
    }

    private final INotificationManager getNotificationInterface() {
        if (mNoMan == null) {
            mNoMan = INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        }
        return mNoMan;
    }

    /**
     * Inform the notification manager about dismissal of a single notification.
     * <p>
     * Use this if your listener has a user interface that allows the user to dismiss individual
     * notifications, similar to the behavior of Android's status bar and notification panel.
     * It should be called after the user dismisses a single notification using your UI;
     * upon being informed, the notification manager will actually remove the notification
     * and you will get an {@link #onNotificationRemoved(StatusBarNotification)} callback.
     * <P>
     * <b>Note:</b> If your listener allows the user to fire a notification's
     * {@link android.app.Notification#contentIntent} by tapping/clicking/etc., you should call
     * this method at that time <i>if</i> the Notification in question has the
     * {@link android.app.Notification#FLAG_AUTO_CANCEL} flag set.
     *
     * @param pkg Package of the notifying app.
     * @param tag Tag of the notification as specified by the notifying app in
     *     {@link android.app.NotificationManager#notify(String, int, android.app.Notification)}.
     * @param id  ID of the notification as specified by the notifying app in
     *     {@link android.app.NotificationManager#notify(String, int, android.app.Notification)}.
     * <p>
     * @deprecated Use {@link #cancelNotification(String key)}
     * instead. Beginning with {@link android.os.Build.VERSION_CODES#L} this method will no longer
     * cancel the notification. It will continue to cancel the notification for applications
     * whose {@code targetSdkVersion} is earlier than {@link android.os.Build.VERSION_CODES#L}.
     */
    public final void cancelNotification(String pkg, String tag, int id) {
        if (!isBound()) return;
        try {
            getNotificationInterface().cancelNotificationFromListener(
                    mWrapper, pkg, tag, id);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
    }

    /**
     * Inform the notification manager about dismissal of a single notification.
     * <p>
     * Use this if your listener has a user interface that allows the user to dismiss individual
     * notifications, similar to the behavior of Android's status bar and notification panel.
     * It should be called after the user dismisses a single notification using your UI;
     * upon being informed, the notification manager will actually remove the notification
     * and you will get an {@link #onNotificationRemoved(StatusBarNotification)} callback.
     * <P>
     * <b>Note:</b> If your listener allows the user to fire a notification's
     * {@link android.app.Notification#contentIntent} by tapping/clicking/etc., you should call
     * this method at that time <i>if</i> the Notification in question has the
     * {@link android.app.Notification#FLAG_AUTO_CANCEL} flag set.
     * <p>
     * @param key Notification to dismiss from {@link StatusBarNotification#getKey()}.
     */
    public final void cancelNotification(String key) {
        if (!isBound()) return;
        try {
            getNotificationInterface().cancelNotificationsFromListener(mWrapper,
                    new String[] {key});
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
    }

    /**
     * Inform the notification manager about dismissal of all notifications.
     * <p>
     * Use this if your listener has a user interface that allows the user to dismiss all
     * notifications, similar to the behavior of Android's status bar and notification panel.
     * It should be called after the user invokes the "dismiss all" function of your UI;
     * upon being informed, the notification manager will actually remove all active notifications
     * and you will get multiple {@link #onNotificationRemoved(StatusBarNotification)} callbacks.
     *
     * {@see #cancelNotification(String, String, int)}
     */
    public final void cancelAllNotifications() {
        cancelNotifications(null /*all*/);
    }

    /**
     * Inform the notification manager about dismissal of specific notifications.
     * <p>
     * Use this if your listener has a user interface that allows the user to dismiss
     * multiple notifications at once.
     *
     * @param keys Notifications to dismiss, or {@code null} to dismiss all.
     *
     * {@see #cancelNotification(String, String, int)}
     */
    public final void cancelNotifications(String[] keys) {
        if (!isBound()) return;
        try {
            getNotificationInterface().cancelNotificationsFromListener(mWrapper, keys);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
    }

    /**
     * Request the list of outstanding notifications (that is, those that are visible to the
     * current user). Useful when you don't know what's already been posted.
     *
     * @return An array of active notifications, sorted in natural order.
     */
    public StatusBarNotification[] getActiveNotifications() {
        return getActiveNotifications(null /*all*/);
    }

    /**
     * Request the list of notification keys in their current ranking order.
     * <p>
     * You can use the notification keys for subsequent retrieval via
     * {@link #getActiveNotifications(String[]) or dismissal via
     * {@link #cancelNotifications(String[]).
     *
     * @return An array of active notification keys, in their ranking order.
     */
    public String[] getActiveNotificationKeys() {
        return mRanking.getOrderedKeys();
    }

    /**
     * Request the list of outstanding notifications (that is, those that are visible to the
     * current user). Useful when you don't know what's already been posted.
     *
     * @param keys A specific list of notification keys, or {@code null} for all.
     * @return An array of active notifications, sorted in natural order
     *   if {@code keys} is {@code null}.
     */
    public StatusBarNotification[] getActiveNotifications(String[] keys) {
        if (!isBound()) return null;
        try {
            return getNotificationInterface().getActiveNotificationsFromListener(mWrapper, keys);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
        return null;
    }

    /**
     * Returns current ranking information.
     *
     * <p>
     * The returned object represents the current ranking snapshot and only
     * applies for currently active notifications. Hence you must retrieve a
     * new Ranking after each notification event such as
     * {@link #onNotificationPosted(StatusBarNotification)},
     * {@link #onNotificationRemoved(StatusBarNotification)}, etc.
     *
     * @return A {@link NotificationListenerService.Ranking} object providing
     *     access to ranking information
     */
    public Ranking getCurrentRanking() {
        return mRanking;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (mWrapper == null) {
            mWrapper = new INotificationListenerWrapper();
        }
        return mWrapper;
    }

    private boolean isBound() {
        if (mWrapper == null) {
            Log.w(TAG, "Notification listener service not yet bound.");
            return false;
        }
        return true;
    }

    /**
     * Directly register this service with the Notification Manager.
     *
     * <p>Only system services may use this call. It will fail for non-system callers.
     * Apps should ask the user to add their listener in Settings.
     *
     * @param componentName the component that will consume the notification information
     * @param currentUser the user to use as the stream filter
     * @hide
     */
    @PrivateApi
    public void registerAsSystemService(ComponentName componentName, int currentUser)
            throws RemoteException {
        if (mWrapper == null) {
            mWrapper = new INotificationListenerWrapper();
        }
        INotificationManager noMan = getNotificationInterface();
        noMan.registerListener(mWrapper, componentName, currentUser);
        mCurrentUser = currentUser;
    }

    /**
     * Directly unregister this service from the Notification Manager.
     *
     * <P>This method will fail for listeners that were not registered
     * with (@link registerAsService).
     * @hide
     */
    @PrivateApi
    public void unregisterAsSystemService() throws RemoteException {
        if (mWrapper != null) {
            INotificationManager noMan = getNotificationInterface();
            noMan.unregisterListener(mWrapper, mCurrentUser);
        }
    }

    private class INotificationListenerWrapper extends INotificationListener.Stub {
        @Override
        public void onNotificationPosted(StatusBarNotification sbn,
                NotificationRankingUpdate update) {
            // protect subclass from concurrent modifications of (@link mNotificationKeys}.
            synchronized (mWrapper) {
                applyUpdate(update);
                try {
                    NotificationListenerService.this.onNotificationPosted(sbn);
                } catch (Throwable t) {
                    Log.w(TAG, "Error running onNotificationPosted", t);
                }
            }
        }
        @Override
        public void onNotificationRemoved(StatusBarNotification sbn,
                NotificationRankingUpdate update) {
            // protect subclass from concurrent modifications of (@link mNotificationKeys}.
            synchronized (mWrapper) {
                applyUpdate(update);
                try {
                    NotificationListenerService.this.onNotificationRemoved(sbn);
                } catch (Throwable t) {
                    Log.w(TAG, "Error running onNotificationRemoved", t);
                }
            }
        }
        @Override
        public void onListenerConnected(NotificationRankingUpdate update) {
            // protect subclass from concurrent modifications of (@link mNotificationKeys}.
            synchronized (mWrapper) {
                applyUpdate(update);
                try {
                    NotificationListenerService.this.onListenerConnected(
                            mRanking.getOrderedKeys());
                } catch (Throwable t) {
                    Log.w(TAG, "Error running onListenerConnected", t);
                }
            }
        }
        @Override
        public void onNotificationRankingUpdate(NotificationRankingUpdate update)
                throws RemoteException {
            // protect subclass from concurrent modifications of (@link mNotificationKeys}.
            synchronized (mWrapper) {
                applyUpdate(update);
                try {
                    NotificationListenerService.this.onNotificationRankingUpdate();
                } catch (Throwable t) {
                    Log.w(TAG, "Error running onNotificationRankingUpdate", t);
                }
            }
        }
    }

    private void applyUpdate(NotificationRankingUpdate update) {
        mRanking = new Ranking(update);
    }

    /**
     * Provides access to ranking information on currently active
     * notifications.
     *
     * <p>
     * Note that this object represents a ranking snapshot that only applies to
     * notifications active at the time of retrieval.
     */
    public static class Ranking implements Parcelable {
        private final NotificationRankingUpdate mRankingUpdate;

        private Ranking(NotificationRankingUpdate rankingUpdate) {
            mRankingUpdate = rankingUpdate;
        }

        /**
         * Request the list of notification keys in their current ranking
         * order.
         *
         * @return An array of active notification keys, in their ranking order.
         */
        public String[] getOrderedKeys() {
            return mRankingUpdate.getOrderedKeys();
        }

        /**
         * Returns the rank of the notification with the given key, that is the
         * index of <code>key</code> in the array of keys returned by
         * {@link #getOrderedKeys()}.
         *
         * @return The rank of the notification with the given key; -1 when the
         *      given key is unknown.
         */
        public int getIndexOfKey(String key) {
            // TODO: Optimize.
            String[] orderedKeys = mRankingUpdate.getOrderedKeys();
            for (int i = 0; i < orderedKeys.length; i++) {
                if (orderedKeys[i].equals(key)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Returns whether the notification with the given key was intercepted
         * by &quot;Do not disturb&quot;.
         */
        public boolean isInterceptedByDoNotDisturb(String key) {
            // TODO: Optimize.
            for (String interceptedKey : mRankingUpdate.getDndInterceptedKeys()) {
                if (interceptedKey.equals(key)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns whether the notification with the given key is an ambient
         * notification, that is a notification that doesn't require the user's
         * immediate attention.
         */
        public boolean isAmbient(String key) {
            // TODO: Optimize.
            int firstAmbientIndex = mRankingUpdate.getFirstAmbientIndex();
            if (firstAmbientIndex < 0) {
                return false;
            }
            String[] orderedKeys = mRankingUpdate.getOrderedKeys();
            for (int i = firstAmbientIndex; i < orderedKeys.length; i++) {
                if (orderedKeys[i].equals(key)) {
                    return true;
                }
            }
            return false;
        }

        // ----------- Parcelable

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeParcelable(mRankingUpdate, flags);
        }

        public static final Creator<Ranking> CREATOR = new Creator<Ranking>() {
            @Override
            public Ranking createFromParcel(Parcel source) {
                NotificationRankingUpdate rankingUpdate = source.readParcelable(null);
                return new Ranking(rankingUpdate);
            }

            @Override
            public Ranking[] newArray(int size) {
                return new Ranking[size];
            }
        };
    }
}
