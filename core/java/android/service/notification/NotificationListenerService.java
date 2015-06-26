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
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.util.ArrayList;
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

    /**
     * {@link #getCurrentInterruptionFilter() Interruption filter} constant -
     *     Normal interruption filter.
     */
    public static final int INTERRUPTION_FILTER_ALL
            = NotificationManager.INTERRUPTION_FILTER_ALL;

    /**
     * {@link #getCurrentInterruptionFilter() Interruption filter} constant -
     *     Priority interruption filter.
     */
    public static final int INTERRUPTION_FILTER_PRIORITY
            = NotificationManager.INTERRUPTION_FILTER_PRIORITY;

    /**
     * {@link #getCurrentInterruptionFilter() Interruption filter} constant -
     *     No interruptions filter.
     */
    public static final int INTERRUPTION_FILTER_NONE
            = NotificationManager.INTERRUPTION_FILTER_NONE;

    /**
     * {@link #getCurrentInterruptionFilter() Interruption filter} constant -
     *     Alarms only interruption filter.
     */
    public static final int INTERRUPTION_FILTER_ALARMS
            = NotificationManager.INTERRUPTION_FILTER_ALARMS;

    /** {@link #getCurrentInterruptionFilter() Interruption filter} constant - returned when
     * the value is unavailable for any reason.  For example, before the notification listener
     * is connected.
     *
     * {@see #onListenerConnected()}
     */
    public static final int INTERRUPTION_FILTER_UNKNOWN
            = NotificationManager.INTERRUPTION_FILTER_UNKNOWN;

    /** {@link #getCurrentListenerHints() Listener hints} constant - the primary device UI
     * should disable notification sound, vibrating and other visual or aural effects.
     * This does not change the interruption filter, only the effects. **/
    public static final int HINT_HOST_DISABLE_EFFECTS = 1;

    /**
     * The full trim of the StatusBarNotification including all its features.
     *
     * @hide
     */
    @SystemApi
    public static final int TRIM_FULL = 0;

    /**
     * A light trim of the StatusBarNotification excluding the following features:
     *
     * <ol>
     *     <li>{@link Notification#tickerView tickerView}</li>
     *     <li>{@link Notification#contentView contentView}</li>
     *     <li>{@link Notification#largeIcon largeIcon}</li>
     *     <li>{@link Notification#bigContentView bigContentView}</li>
     *     <li>{@link Notification#headsUpContentView headsUpContentView}</li>
     *     <li>{@link Notification#EXTRA_LARGE_ICON extras[EXTRA_LARGE_ICON]}</li>
     *     <li>{@link Notification#EXTRA_LARGE_ICON_BIG extras[EXTRA_LARGE_ICON_BIG]}</li>
     *     <li>{@link Notification#EXTRA_PICTURE extras[EXTRA_PICTURE]}</li>
     *     <li>{@link Notification#EXTRA_BIG_TEXT extras[EXTRA_BIG_TEXT]}</li>
     * </ol>
     *
     * @hide
     */
    @SystemApi
    public static final int TRIM_LIGHT = 1;

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

    /**
     * Implement this method to be notified when the
     * {@link #getCurrentInterruptionFilter() interruption filter} changed.
     *
     * @param interruptionFilter The current
     *     {@link #getCurrentInterruptionFilter() interruption filter}.
     */
    public void onInterruptionFilterChanged(int interruptionFilter) {
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
     * instead. Beginning with {@link android.os.Build.VERSION_CODES#LOLLIPOP} this method will no longer
     * cancel the notification. It will continue to cancel the notification for applications
     * whose {@code targetSdkVersion} is earlier than {@link android.os.Build.VERSION_CODES#LOLLIPOP}.
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
                    new String[] { key });
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
     * Inform the notification manager that these notifications have been viewed by the
     * user. This should only be called when there is sufficient confidence that the user is
     * looking at the notifications, such as when the notifications appear on the screen due to
     * an explicit user interaction.
     * @param keys Notifications to mark as seen.
     */
    public final void setNotificationsShown(String[] keys) {
        if (!isBound()) return;
        try {
            getNotificationInterface().setNotificationsShownFromListener(mWrapper, keys);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
    }

    /**
     * Sets the notification trim that will be received via {@link #onNotificationPosted}.
     *
     * <p>
     * Setting a trim other than {@link #TRIM_FULL} enables listeners that don't need access to the
     * full notification features right away to reduce their memory footprint. Full notifications
     * can be requested on-demand via {@link #getActiveNotifications(int)}.
     *
     * <p>
     * Set to {@link #TRIM_FULL} initially.
     *
     * @hide
     *
     * @param trim trim of the notifications to be passed via {@link #onNotificationPosted}.
     *             See <code>TRIM_*</code> constants.
     */
    @SystemApi
    public final void setOnNotificationPostedTrim(int trim) {
        if (!isBound()) return;
        try {
            getNotificationInterface().setOnNotificationPostedTrimFromListener(mWrapper, trim);
        } catch (RemoteException ex) {
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
        return getActiveNotifications(null, TRIM_FULL);
    }

    /**
     * Request the list of outstanding notifications (that is, those that are visible to the
     * current user). Useful when you don't know what's already been posted.
     *
     * @hide
     *
     * @param trim trim of the notifications to be returned. See <code>TRIM_*</code> constants.
     * @return An array of active notifications, sorted in natural order.
     */
    @SystemApi
    public StatusBarNotification[] getActiveNotifications(int trim) {
        return getActiveNotifications(null, trim);
    }

    /**
     * Request one or more notifications by key. Useful if you have been keeping track of
     * notifications but didn't want to retain the bits, and now need to go back and extract
     * more data out of those notifications.
     *
     * @param keys the keys of the notifications to request
     * @return An array of notifications corresponding to the requested keys, in the
     * same order as the key list.
     */
    public StatusBarNotification[] getActiveNotifications(String[] keys) {
        return getActiveNotifications(keys, TRIM_FULL);
    }

    /**
     * Request one or more notifications by key. Useful if you have been keeping track of
     * notifications but didn't want to retain the bits, and now need to go back and extract
     * more data out of those notifications.
     *
     * @hide
     *
     * @param keys the keys of the notifications to request
     * @param trim trim of the notifications to be returned. See <code>TRIM_*</code> constants.
     * @return An array of notifications corresponding to the requested keys, in the
     * same order as the key list.
     */
    @SystemApi
    public StatusBarNotification[] getActiveNotifications(String[] keys, int trim) {
        if (!isBound())
            return null;
        try {
            ParceledListSlice<StatusBarNotification> parceledList = getNotificationInterface()
                    .getActiveNotificationsFromListener(mWrapper, keys, trim);
            List<StatusBarNotification> list = parceledList.getList();
            ArrayList<StatusBarNotification> corruptNotifications = null;
            int N = list.size();
            for (int i = 0; i < N; i++) {
                StatusBarNotification sbn = list.get(i);
                Notification notification = sbn.getNotification();
                try {
                    Builder.rebuild(getContext(), notification);
                    // convert icon metadata to legacy format for older clients
                    createLegacyIconExtras(notification);
                } catch (IllegalArgumentException e) {
                    if (corruptNotifications == null) {
                        corruptNotifications = new ArrayList<>(N);
                    }
                    corruptNotifications.add(sbn);
                    Log.w(TAG, "onNotificationPosted: can't rebuild notification from " +
                            sbn.getPackageName());
                }
            }
            if (corruptNotifications != null) {
                list.removeAll(corruptNotifications);
            }
            return list.toArray(new StatusBarNotification[list.size()]);
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
     * @return Zero or more of the HINT_ constants.
     */
    public final int getCurrentListenerHints() {
        if (!isBound()) return 0;
        try {
            return getNotificationInterface().getHintsFromListener(mWrapper);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
            return 0;
        }
    }

    /**
     * Gets the current notification interruption filter active on the host.
     *
     * <p>
     * The interruption filter defines which notifications are allowed to interrupt the user
     * (e.g. via sound &amp; vibration) and is applied globally. Listeners can find out whether
     * a specific notification matched the interruption filter via
     * {@link Ranking#matchesInterruptionFilter()}.
     * <p>
     * The current filter may differ from the previously requested filter if the notification host
     * does not support or refuses to apply the requested filter, or if another component changed
     * the filter in the meantime.
     * <p>
     * Listen for updates using {@link #onInterruptionFilterChanged(int)}.
     *
     * @return One of the INTERRUPTION_FILTER_ constants, or INTERRUPTION_FILTER_UNKNOWN when
     * unavailable.
     */
    public final int getCurrentInterruptionFilter() {
        if (!isBound()) return INTERRUPTION_FILTER_UNKNOWN;
        try {
            return getNotificationInterface().getInterruptionFilterFromListener(mWrapper);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
            return INTERRUPTION_FILTER_UNKNOWN;
        }
    }

    /**
     * Sets the desired {@link #getCurrentListenerHints() listener hints}.
     *
     * <p>
     * This is merely a request, the host may or may not choose to take action depending
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
     * Sets the desired {@link #getCurrentInterruptionFilter() interruption filter}.
     *
     * <p>
     * This is merely a request, the host may or may not choose to apply the requested
     * interruption filter depending on other listener requests or other global state.
     * <p>
     * Listen for updates using {@link #onInterruptionFilterChanged(int)}.
     *
     * @param interruptionFilter One of the INTERRUPTION_FILTER_ constants.
     */
    public final void requestInterruptionFilter(int interruptionFilter) {
        if (!isBound()) return;
        try {
            getNotificationInterface()
                    .requestInterruptionFilterFromListener(mWrapper, interruptionFilter);
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

    /** Convert new-style Icons to legacy representations for pre-M clients. */
    private void createLegacyIconExtras(Notification n) {
        Icon smallIcon = n.getSmallIcon();
        Icon largeIcon = n.getLargeIcon();
        if (smallIcon != null && smallIcon.getType() == Icon.TYPE_RESOURCE) {
            n.extras.putInt(Notification.EXTRA_SMALL_ICON, smallIcon.getResId());
            n.icon = smallIcon.getResId();
        }
        if (largeIcon != null) {
            Drawable d = largeIcon.loadDrawable(getContext());
            if (d != null && d instanceof BitmapDrawable) {
                final Bitmap largeIconBits = ((BitmapDrawable) d).getBitmap();
                n.extras.putParcelable(Notification.EXTRA_LARGE_ICON, largeIconBits);
                n.largeIcon = largeIconBits;
            }
        }
    }

    private class INotificationListenerWrapper extends INotificationListener.Stub {
        @Override
        public void onNotificationPosted(IStatusBarNotificationHolder sbnHolder,
                NotificationRankingUpdate update) {
            StatusBarNotification sbn;
            try {
                sbn = sbnHolder.get();
            } catch (RemoteException e) {
                Log.w(TAG, "onNotificationPosted: Error receiving StatusBarNotification", e);
                return;
            }

            try {
                Notification.Builder.rebuild(getContext(), sbn.getNotification());
                // convert icon metadata to legacy format for older clients
                createLegacyIconExtras(sbn.getNotification());
            } catch (IllegalArgumentException e) {
                // drop corrupt notification
                sbn = null;
                Log.w(TAG, "onNotificationPosted: can't rebuild notification from " +
                        sbn.getPackageName());
            }

            // protect subclass from concurrent modifications of (@link mNotificationKeys}.
            synchronized (mWrapper) {
                applyUpdate(update);
                try {
                    if (sbn != null) {
                        NotificationListenerService.this.onNotificationPosted(sbn, mRankingMap);
                    } else {
                        // still pass along the ranking map, it may contain other information
                        NotificationListenerService.this.onNotificationRankingUpdate(mRankingMap);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Error running onNotificationPosted", t);
                }
            }
        }
        @Override
        public void onNotificationRemoved(IStatusBarNotificationHolder sbnHolder,
                NotificationRankingUpdate update) {
            StatusBarNotification sbn;
            try {
                sbn = sbnHolder.get();
            } catch (RemoteException e) {
                Log.w(TAG, "onNotificationRemoved: Error receiving StatusBarNotification", e);
                return;
            }
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

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) throws RemoteException {
            try {
                NotificationListenerService.this.onInterruptionFilterChanged(interruptionFilter);
            } catch (Throwable t) {
                Log.w(TAG, "Error running onInterruptionFilterChanged", t);
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
        /** Value signifying that the user has not expressed a per-app visibility override value.
         * @hide */
        public static final int VISIBILITY_NO_OVERRIDE = -1000;

        private String mKey;
        private int mRank = -1;
        private boolean mIsAmbient;
        private boolean mMatchesInterruptionFilter;
        private int mVisibilityOverride;

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
         * Returns the user specificed visibility for the package that posted
         * this notification, or
         * {@link NotificationListenerService.Ranking#VISIBILITY_NO_OVERRIDE} if
         * no such preference has been expressed.
         * @hide
         */
        public int getVisibilityOverride() {
            return mVisibilityOverride;
        }


        /**
         * Returns whether the notification matches the user's interruption
         * filter.
         *
         * @return {@code true} if the notification is allowed by the filter, or
         * {@code false} if it is blocked.
         */
        public boolean matchesInterruptionFilter() {
            return mMatchesInterruptionFilter;
        }

        private void populate(String key, int rank, boolean isAmbient,
                boolean matchesInterruptionFilter, int visibilityOverride) {
            mKey = key;
            mRank = rank;
            mIsAmbient = isAmbient;
            mMatchesInterruptionFilter = matchesInterruptionFilter;
            mVisibilityOverride = visibilityOverride;
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
        private ArrayMap<String, Integer> mVisibilityOverrides;

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
            outRanking.populate(key, rank, isAmbient(key), !isIntercepted(key),
                    getVisibilityOverride(key));
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

        private int getVisibilityOverride(String key) {
            synchronized (this) {
                if (mVisibilityOverrides == null) {
                    buildVisibilityOverridesLocked();
                }
            }
            Integer overide = mVisibilityOverrides.get(key);
            if (overide == null) {
                return Ranking.VISIBILITY_NO_OVERRIDE;
            }
            return overide.intValue();
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

        // Locked by 'this'
        private void buildVisibilityOverridesLocked() {
            Bundle visibilityBundle = mRankingUpdate.getVisibilityOverrides();
            mVisibilityOverrides = new ArrayMap<>(visibilityBundle.size());
            for (String key: visibilityBundle.keySet()) {
               mVisibilityOverrides.put(key, visibilityBundle.getInt(key));
            }
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
