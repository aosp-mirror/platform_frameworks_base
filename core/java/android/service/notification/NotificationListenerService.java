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

import android.annotation.SystemApi;
import android.annotation.SdkConstant;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.util.Collections;
import java.util.List;

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

    /** {@link #getCurrentListenerHints() Listener hints} constant - default state. */
    public static final int HINTS_NONE = 0;

    /** Bitmask range for {@link #getCurrentListenerHints() Listener hints} host interruption level
     * constants.  */
    public static final int HOST_INTERRUPTION_LEVEL_MASK = 0x3;

    /** {@link #getCurrentListenerHints() Listener hints} constant - Normal interruption level. */
    public static final int HINT_HOST_INTERRUPTION_LEVEL_ALL = 1;

    /** {@link #getCurrentListenerHints() Listener hints} constant - Priority interruption level. */
    public static final int HINT_HOST_INTERRUPTION_LEVEL_PRIORITY = 2;

    /** {@link #getCurrentListenerHints() Listener hints} constant - No interruptions level. */
    public static final int HINT_HOST_INTERRUPTION_LEVEL_NONE = 3;

    /** {@link #getCurrentListenerHints() Listener hints} constant - the primary device UI
     * should disable notification sound, vibrating and other visual or aural effects.
     * This does not change the interruption level, only the effects. **/
    public static final int HINT_HOST_DISABLE_EFFECTS = 1 << 2;

    private INotificationListenerWrapper mWrapper = null;
    private RankingMap mRankingMap;

    private INotificationManager mNoMan;

    /** Only valid after a successful call to (@link registerAsService}. */
    private int mCurrentUser;


    // This context is required for system services since NotificationListenerService isn't
    // started as a real Service and hence no context is available.
    private Context mSystemContext;

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
    public void onNotificationPosted(StatusBarNotification sbn) {
        // optional
    }

    /**
     * Implement this method to learn about new notifications as they are posted by apps.
     *
     * @param sbn A data structure encapsulating the original {@link android.app.Notification}
     *            object as well as its identifying information (tag and id) and source
     *            (package name).
     * @param rankingMap The current ranking map that can be used to retrieve ranking information
     *                   for active notifications, including the newly posted one.
     */
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        onNotificationPosted(sbn);
    }

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
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // optional
    }

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
     * @param rankingMap The current ranking map that can be used to retrieve ranking information
     *                   for active notifications.
     *
     */
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        onNotificationRemoved(sbn);
    }

    /**
     * Implement this method to learn about when the listener is enabled and connected to
     * the notification manager.  You are safe to call {@link #getActiveNotifications()}
     * at this time.
     */
    public void onListenerConnected() {
        // optional
    }

    /**
     * Implement this method to be notified when the notification ranking changes.
     *
     * @param rankingMap The current ranking map that can be used to retrieve ranking information
     *                   for active notifications.
     */
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        // optional
    }

    /**
     * Implement this method to be notified when the
     * {@link #getCurrentListenerHints() Listener hints} change.
     *
     * @param hints The current {@link #getCurrentListenerHints() listener hints}.
     */
    public void onListenerHintsChanged(int hints) {
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
        if (!isBound()) return null;
        try {
            ParceledListSlice<StatusBarNotification> parceledList =
                    getNotificationInterface().getActiveNotificationsFromListener(mWrapper);
            List<StatusBarNotification> list = parceledList.getList();

            int N = list.size();
            for (int i = 0; i < N; i++) {
                Notification notification = list.get(i).getNotification();
                Builder.rebuild(getContext(), notification);
            }
            return list.toArray(new StatusBarNotification[N]);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
        return null;
    }

    /**
     * Gets the set of hints representing current state.
     *
     * <p>
     * The current state may differ from the requested state if the hint represents state
     * shared across all listeners or a feature the notification host does not support or refuses
     * to grant.
     *
     * @return One or more of the HINT_ constants.
     */
    public final int getCurrentListenerHints() {
        if (!isBound()) return HINTS_NONE;
        try {
            return getNotificationInterface().getHintsFromListener(mWrapper);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
            return HINTS_NONE;
        }
    }

    /**
     * Sets the desired {@link #getCurrentListenerHints() listener hints}.
     *
     * <p>
     * This is merely a request, the host may or not choose to take action depending
     * on other listener requests or other global state.
     * <p>
     * Listen for updates using {@link #onListenerHintsChanged(int)}.
     *
     * @param hints One or more of the HINT_ constants.
     */
    public final void requestListenerHints(int hints) {
        if (!isBound()) return;
        try {
            getNotificationInterface().requestHintsFromListener(mWrapper, hints);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
    }

    /**
     * Returns current ranking information.
     *
     * <p>
     * The returned object represents the current ranking snapshot and only
     * applies for currently active notifications.
     * <p>
     * Generally you should use the RankingMap that is passed with events such
     * as {@link #onNotificationPosted(StatusBarNotification, RankingMap)},
     * {@link #onNotificationRemoved(StatusBarNotification, RankingMap)}, and
     * so on. This method should only be used when needing access outside of
     * such events, for example to retrieve the RankingMap right after
     * initialization.
     *
     * @return A {@link RankingMap} object providing access to ranking information
     */
    public RankingMap getCurrentRanking() {
        return mRankingMap;
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
     * @param context Context required for accessing resources. Since this service isn't
     *    launched as a real Service when using this method, a context has to be passed in.
     * @param componentName the component that will consume the notification information
     * @param currentUser the user to use as the stream filter
     * @hide
     */
    @SystemApi
    public void registerAsSystemService(Context context, ComponentName componentName,
            int currentUser) throws RemoteException {
        mSystemContext = context;
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
    @SystemApi
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
            Notification.Builder.rebuild(getContext(), sbn.getNotification());

            // protect subclass from concurrent modifications of (@link mNotificationKeys}.
            synchronized (mWrapper) {
                applyUpdate(update);
                try {
                    NotificationListenerService.this.onNotificationPosted(sbn, mRankingMap);
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
                    NotificationListenerService.this.onNotificationRemoved(sbn, mRankingMap);
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
                    NotificationListenerService.this.onListenerConnected();
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
                    NotificationListenerService.this.onNotificationRankingUpdate(mRankingMap);
                } catch (Throwable t) {
                    Log.w(TAG, "Error running onNotificationRankingUpdate", t);
                }
            }
        }
        @Override
        public void onListenerHintsChanged(int hints) throws RemoteException {
            try {
                NotificationListenerService.this.onListenerHintsChanged(hints);
            } catch (Throwable t) {
                Log.w(TAG, "Error running onListenerHintsChanged", t);
            }
        }
    }

    private void applyUpdate(NotificationRankingUpdate update) {
        mRankingMap = new RankingMap(update);
    }

    private Context getContext() {
        if (mSystemContext != null) {
            return mSystemContext;
        }
        return this;
    }

    /**
     * Stores ranking related information on a currently active notification.
     *
     * <p>
     * Ranking objects aren't automatically updated as notification events
     * occur. Instead, ranking information has to be retrieved again via the
     * current {@link RankingMap}.
     */
    public static class Ranking {
        private String mKey;
        private int mRank = -1;
        private boolean mIsAmbient;
        private boolean mMeetsInterruptionFilter;

        public Ranking() {}

        /**
         * Returns the key of the notification this Ranking applies to.
         */
        public String getKey() {
            return mKey;
        }

        /**
         * Returns the rank of the notification.
         *
         * @return the rank of the notification, that is the 0-based index in
         *     the list of active notifications.
         */
        public int getRank() {
            return mRank;
        }

        /**
         * Returns whether the notification is an ambient notification, that is
         * a notification that doesn't require the user's immediate attention.
         */
        public boolean isAmbient() {
            return mIsAmbient;
        }

        /**
         * Returns whether the notification meets the user's interruption
         * filter.
         */
        public boolean meetsInterruptionFilter() {
            return mMeetsInterruptionFilter;
        }

        private void populate(String key, int rank, boolean isAmbient,
                boolean meetsInterruptionFilter) {
            mKey = key;
            mRank = rank;
            mIsAmbient = isAmbient;
            mMeetsInterruptionFilter = meetsInterruptionFilter;
        }
    }

    /**
     * Provides access to ranking information on currently active
     * notifications.
     *
     * <p>
     * Note that this object represents a ranking snapshot that only applies to
     * notifications active at the time of retrieval.
     */
    public static class RankingMap implements Parcelable {
        private final NotificationRankingUpdate mRankingUpdate;
        private ArrayMap<String,Integer> mRanks;
        private ArraySet<Object> mIntercepted;

        private RankingMap(NotificationRankingUpdate rankingUpdate) {
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
         * Populates outRanking with ranking information for the notification
         * with the given key.
         *
         * @return true if a valid key has been passed and outRanking has
         *     been populated; false otherwise
         */
        public boolean getRanking(String key, Ranking outRanking) {
            int rank = getRank(key);
            outRanking.populate(key, rank, isAmbient(key), !isIntercepted(key));
            return rank >= 0;
        }

        private int getRank(String key) {
            synchronized (this) {
                if (mRanks == null) {
                    buildRanksLocked();
                }
            }
            Integer rank = mRanks.get(key);
            return rank != null ? rank : -1;
        }

        private boolean isAmbient(String key) {
            int firstAmbientIndex = mRankingUpdate.getFirstAmbientIndex();
            if (firstAmbientIndex < 0) {
                return false;
            }
            int rank = getRank(key);
            return rank >= 0 && rank >= firstAmbientIndex;
        }

        private boolean isIntercepted(String key) {
            synchronized (this) {
                if (mIntercepted == null) {
                    buildInterceptedSetLocked();
                }
            }
            return mIntercepted.contains(key);
        }

        // Locked by 'this'
        private void buildRanksLocked() {
            String[] orderedKeys = mRankingUpdate.getOrderedKeys();
            mRanks = new ArrayMap<>(orderedKeys.length);
            for (int i = 0; i < orderedKeys.length; i++) {
                String key = orderedKeys[i];
                mRanks.put(key, i);
            }
        }

        // Locked by 'this'
        private void buildInterceptedSetLocked() {
            String[] dndInterceptedKeys = mRankingUpdate.getInterceptedKeys();
            mIntercepted = new ArraySet<>(dndInterceptedKeys.length);
            Collections.addAll(mIntercepted, dndInterceptedKeys);
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

        public static final Creator<RankingMap> CREATOR = new Creator<RankingMap>() {
            @Override
            public RankingMap createFromParcel(Parcel source) {
                NotificationRankingUpdate rankingUpdate = source.readParcelable(null);
                return new RankingMap(rankingUpdate);
            }

            @Override
            public RankingMap[] newArray(int size) {
                return new RankingMap[size];
            }
        };
    }
}
