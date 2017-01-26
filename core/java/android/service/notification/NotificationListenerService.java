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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import android.annotation.IntDef;
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
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.widget.RemoteViews;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.SomeArgs;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
 *
 * <p>The service should wait for the {@link #onListenerConnected()} event
 * before performing any operations. The {@link #requestRebind(ComponentName)}
 * method is the <i>only</i> one that is safe to call before {@link #onListenerConnected()}
 * or after {@link #onListenerDisconnected()}.
 * </p>
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

    /** {@link #getCurrentListenerHints() Listener hints} constant - the primary device UI
     * should disable notification sound, but not phone calls.
     * This does not change the interruption filter, only the effects. **/
    public static final int HINT_HOST_DISABLE_NOTIFICATION_EFFECTS = 1 << 1;

    /** {@link #getCurrentListenerHints() Listener hints} constant - the primary device UI
     * should disable phone call sounds, buyt not notification sound.
     * This does not change the interruption filter, only the effects. **/
    public static final int HINT_HOST_DISABLE_CALL_EFFECTS = 1 << 2;

    /**
     * Whether notification suppressed by DND should not interruption visually when the screen is
     * off.
     */
    public static final int SUPPRESSED_EFFECT_SCREEN_OFF =
            NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF;
    /**
     * Whether notification suppressed by DND should not interruption visually when the screen is
     * on.
     */
    public static final int SUPPRESSED_EFFECT_SCREEN_ON =
            NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON;

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

    private final Object mLock = new Object();

    private Handler mHandler;

    /** @hide */
    protected NotificationListenerWrapper mWrapper = null;
    private boolean isConnected = false;

    @GuardedBy("mLock")
    private RankingMap mRankingMap;

    private INotificationManager mNoMan;

    /**
     * Only valid after a successful call to (@link registerAsService}.
     * @hide
     */
    protected int mCurrentUser;

    /**
     * This context is required for system services since NotificationListenerService isn't
     * started as a real Service and hence no context is available..
     * @hide
     */
    protected Context mSystemContext;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE
            = "android.service.notification.NotificationListenerService";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mHandler = new MyHandler(getMainLooper());
    }

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
     * @param sbn A data structure encapsulating at least the original information (tag and id)
     *            and source (package name) used to post the {@link android.app.Notification} that
     *            was just removed.
     */
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // optional
    }

    /**
     * Implement this method to learn when notifications are removed.
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
     * Implement this method to learn about when the listener is disconnected from the
     * notification manager.You will not receive any events after this call, and may only
     * call {@link #requestRebind(ComponentName)} at this time.
     */
    public void onListenerDisconnected() {
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

    /** @hide */
    protected final INotificationManager getNotificationInterface() {
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
     * <p>
     * <b>Note:</b> If your listener allows the user to fire a notification's
     * {@link android.app.Notification#contentIntent} by tapping/clicking/etc., you should call
     * this method at that time <i>if</i> the Notification in question has the
     * {@link android.app.Notification#FLAG_AUTO_CANCEL} flag set.
     *
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
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
     * <p>
     * <b>Note:</b> If your listener allows the user to fire a notification's
     * {@link android.app.Notification#contentIntent} by tapping/clicking/etc., you should call
     * this method at that time <i>if</i> the Notification in question has the
     * {@link android.app.Notification#FLAG_AUTO_CANCEL} flag set.
     * <p>
     *
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
     *
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
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
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
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
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
     *
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
     *
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
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
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
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
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
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
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
                    // convert icon metadata to legacy format for older clients
                    createLegacyIconExtras(notification);
                    // populate remote views for older clients.
                    maybePopulateRemoteViews(notification);
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
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
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
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
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
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
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
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
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
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
     *
     * @return A {@link RankingMap} object providing access to ranking information
     */
    public RankingMap getCurrentRanking() {
        synchronized (mLock) {
            return mRankingMap;
        }
    }

    /**
     * This is not the lifecycle event you are looking for.
     *
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing any operations.
     */
    @Override
    public IBinder onBind(Intent intent) {
        if (mWrapper == null) {
            mWrapper = new NotificationListenerWrapper();
        }
        return mWrapper;
    }

    /** @hide */
    protected boolean isBound() {
        if (mWrapper == null) {
            Log.w(TAG, "Notification listener service not yet bound.");
            return false;
        }
        return true;
    }

    @Override
    public void onDestroy() {
        onListenerDisconnected();
        super.onDestroy();
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
        if (mWrapper == null) {
            mWrapper = new NotificationListenerWrapper();
        }
        mSystemContext = context;
        INotificationManager noMan = getNotificationInterface();
        mHandler = new MyHandler(context.getMainLooper());
        mCurrentUser = currentUser;
        noMan.registerListener(mWrapper, componentName, currentUser);
    }

    /**
     * Directly unregister this service from the Notification Manager.
     *
     * <p>This method will fail for listeners that were not registered
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

    /**
     * Request that the listener be rebound, after a previous call to {@link #requestUnbind}.
     *
     * <p>This method will fail for listeners that have
     * not been granted the permission by the user.
     */
    public static void requestRebind(ComponentName componentName) {
        INotificationManager noMan = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        try {
            noMan.requestBindListener(componentName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Request that the service be unbound.
     *
     * <p>This will no longer receive updates until
     * {@link #requestRebind(ComponentName)} is called.
     * The service will likely be kiled by the system after this call.
     *
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation. I know it's tempting, but you must wait.
     */
    public final void requestUnbind() {
        if (mWrapper != null) {
            INotificationManager noMan = getNotificationInterface();
            try {
                noMan.requestUnbindListener(mWrapper);
                // Disable future messages.
                isConnected = false;
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
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

    /**
     * Populates remote views for pre-N targeting apps.
     */
    private void maybePopulateRemoteViews(Notification notification) {
        if (getContext().getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.N) {
            Builder builder = Builder.recoverBuilder(getContext(), notification);

            // Some styles wrap Notification's contentView, bigContentView and headsUpContentView.
            // First inflate them all, only then set them to avoid recursive wrapping.
            RemoteViews content = builder.createContentView();
            RemoteViews big = builder.createBigContentView();
            RemoteViews headsUp = builder.createHeadsUpContentView();

            notification.contentView = content;
            notification.bigContentView = big;
            notification.headsUpContentView = headsUp;
        }
    }

    /** @hide */
    protected class NotificationListenerWrapper extends INotificationListener.Stub {
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
                // convert icon metadata to legacy format for older clients
                createLegacyIconExtras(sbn.getNotification());
                maybePopulateRemoteViews(sbn.getNotification());
            } catch (IllegalArgumentException e) {
                // warn and drop corrupt notification
                Log.w(TAG, "onNotificationPosted: can't rebuild notification from " +
                        sbn.getPackageName());
                sbn = null;
            }

            // protect subclass from concurrent modifications of (@link mNotificationKeys}.
            synchronized (mLock) {
                applyUpdateLocked(update);
                if (sbn != null) {
                    SomeArgs args = SomeArgs.obtain();
                    args.arg1 = sbn;
                    args.arg2 = mRankingMap;
                    mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_POSTED,
                            args).sendToTarget();
                } else {
                    // still pass along the ranking map, it may contain other information
                    mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_RANKING_UPDATE,
                            mRankingMap).sendToTarget();
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
            synchronized (mLock) {
                applyUpdateLocked(update);
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = sbn;
                args.arg2 = mRankingMap;
                mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_REMOVED,
                        args).sendToTarget();
            }

        }

        @Override
        public void onListenerConnected(NotificationRankingUpdate update) {
            // protect subclass from concurrent modifications of (@link mNotificationKeys}.
            synchronized (mLock) {
                applyUpdateLocked(update);
            }
            isConnected = true;
            mHandler.obtainMessage(MyHandler.MSG_ON_LISTENER_CONNECTED).sendToTarget();
        }

        @Override
        public void onNotificationRankingUpdate(NotificationRankingUpdate update)
                throws RemoteException {
            // protect subclass from concurrent modifications of (@link mNotificationKeys}.
            synchronized (mLock) {
                applyUpdateLocked(update);
                mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_RANKING_UPDATE,
                        mRankingMap).sendToTarget();
            }

        }

        @Override
        public void onListenerHintsChanged(int hints) throws RemoteException {
            mHandler.obtainMessage(MyHandler.MSG_ON_LISTENER_HINTS_CHANGED,
                    hints, 0).sendToTarget();
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) throws RemoteException {
            mHandler.obtainMessage(MyHandler.MSG_ON_INTERRUPTION_FILTER_CHANGED,
                    interruptionFilter, 0).sendToTarget();
        }

        @Override
        public void onNotificationEnqueued(IStatusBarNotificationHolder notificationHolder,
                                           int importance, boolean user) throws RemoteException {
            // no-op in the listener
        }

        @Override
        public void onNotificationVisibilityChanged(String key, long time, boolean visible)
                throws RemoteException {
            // no-op in the listener
        }

        @Override
        public void onNotificationClick(String key, long time) throws RemoteException {
            // no-op in the listener
        }

        @Override
        public void onNotificationActionClick(String key, long time, int actionIndex)
                throws RemoteException {
            // no-op in the listener
        }

        @Override
        public void onNotificationRemovedReason(String key, long time, int reason)
                throws RemoteException {
            // no-op in the listener
        }
    }

    private void applyUpdateLocked(NotificationRankingUpdate update) {
        mRankingMap = new RankingMap(update);
    }

    /** @hide */
    protected Context getContext() {
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
        public static final int VISIBILITY_NO_OVERRIDE = NotificationManager.VISIBILITY_NO_OVERRIDE;

        /**
         * Value signifying that the user has not expressed an importance.
         *
         * This value is for persisting preferences, and should never be associated with
         * an actual notification.
         *
         * @hide
         */
        public static final int IMPORTANCE_UNSPECIFIED = NotificationManager.IMPORTANCE_UNSPECIFIED;

        /**
         * A notification with no importance: shows nowhere, is blocked.
         *
         * @hide
         */
        public static final int IMPORTANCE_NONE = NotificationManager.IMPORTANCE_NONE;

        /**
         * Min notification importance: only shows in the shade, below the fold.
         *
         * @hide
         */
        public static final int IMPORTANCE_MIN = NotificationManager.IMPORTANCE_MIN;

        /**
         * Low notification importance: shows everywhere, but is not intrusive.
         *
         * @hide
         */
        public static final int IMPORTANCE_LOW = NotificationManager.IMPORTANCE_LOW;

        /**
         * Default notification importance: shows everywhere, allowed to makes noise,
         * but does not visually intrude.
         *
         * @hide
         */
        public static final int IMPORTANCE_DEFAULT = NotificationManager.IMPORTANCE_DEFAULT;

        /**
         * Higher notification importance: shows everywhere, allowed to makes noise and peek.
         *
         * @hide
         */
        public static final int IMPORTANCE_HIGH = NotificationManager.IMPORTANCE_HIGH;

        /**
         * Highest notification importance: shows everywhere, allowed to makes noise, peek, and
         * use full screen intents.
         *
         * @hide
         */
        public static final int IMPORTANCE_MAX = NotificationManager.IMPORTANCE_MAX;

        private String mKey;
        private int mRank = -1;
        private boolean mIsAmbient;
        private boolean mMatchesInterruptionFilter;
        private int mVisibilityOverride;
        private int mSuppressedVisualEffects;
        private @NotificationManager.Importance int mImportance;
        private CharSequence mImportanceExplanation;
        // System specified group key.
        private String mOverrideGroupKey;

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
         * Returns the type(s) of visual effects that should be suppressed for this notification.
         * See {@link #SUPPRESSED_EFFECT_SCREEN_OFF}, {@link #SUPPRESSED_EFFECT_SCREEN_ON}.
         */
        public int getSuppressedVisualEffects() {
            return mSuppressedVisualEffects;
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

        /**
         * Returns the importance of the notification, which dictates its
         * modes of presentation, see: {@link NotificationManager#IMPORTANCE_DEFAULT}, etc.
         *
         * @return the rank of the notification
         */
        public @NotificationManager.Importance int getImportance() {
            return mImportance;
        }

        /**
         * If the importance has been overriden by user preference, then this will be non-null,
         * and should be displayed to the user.
         *
         * @return the explanation for the importance, or null if it is the natural importance
         */
        public CharSequence getImportanceExplanation() {
            return mImportanceExplanation;
        }

        /**
         * If the system has overriden the group key, then this will be non-null, and this
         * key should be used to bundle notifications.
         */
        public String getOverrideGroupKey() {
            return mOverrideGroupKey;
        }

        private void populate(String key, int rank, boolean matchesInterruptionFilter,
                int visibilityOverride, int suppressedVisualEffects, int importance,
                CharSequence explanation, String overrideGroupKey) {
            mKey = key;
            mRank = rank;
            mIsAmbient = importance < IMPORTANCE_LOW;
            mMatchesInterruptionFilter = matchesInterruptionFilter;
            mVisibilityOverride = visibilityOverride;
            mSuppressedVisualEffects = suppressedVisualEffects;
            mImportance = importance;
            mImportanceExplanation = explanation;
            mOverrideGroupKey = overrideGroupKey;
        }

        /**
         * {@hide}
         */
        public static String importanceToString(int importance) {
            switch (importance) {
                case IMPORTANCE_UNSPECIFIED:
                    return "UNSPECIFIED";
                case IMPORTANCE_NONE:
                    return "NONE";
                case IMPORTANCE_MIN:
                    return "MIN";
                case IMPORTANCE_LOW:
                    return "LOW";
                case IMPORTANCE_DEFAULT:
                    return "DEFAULT";
                case IMPORTANCE_HIGH:
                    return "HIGH";
                case IMPORTANCE_MAX:
                    return "MAX";
                default:
                    return "UNKNOWN(" + String.valueOf(importance) + ")";
            }
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
        private ArrayMap<String, Integer> mSuppressedVisualEffects;
        private ArrayMap<String, Integer> mImportance;
        private ArrayMap<String, String> mImportanceExplanation;
        private ArrayMap<String, String> mOverrideGroupKeys;

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
            outRanking.populate(key, rank, !isIntercepted(key),
                    getVisibilityOverride(key), getSuppressedVisualEffects(key),
                    getImportance(key), getImportanceExplanation(key), getOverrideGroupKey(key));
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
            Integer override = mVisibilityOverrides.get(key);
            if (override == null) {
                return Ranking.VISIBILITY_NO_OVERRIDE;
            }
            return override.intValue();
        }

        private int getSuppressedVisualEffects(String key) {
            synchronized (this) {
                if (mSuppressedVisualEffects == null) {
                    buildSuppressedVisualEffectsLocked();
                }
            }
            Integer suppressed = mSuppressedVisualEffects.get(key);
            if (suppressed == null) {
                return 0;
            }
            return suppressed.intValue();
        }

        private int getImportance(String key) {
            synchronized (this) {
                if (mImportance == null) {
                    buildImportanceLocked();
                }
            }
            Integer importance = mImportance.get(key);
            if (importance == null) {
                return Ranking.IMPORTANCE_DEFAULT;
            }
            return importance.intValue();
        }

        private String getImportanceExplanation(String key) {
            synchronized (this) {
                if (mImportanceExplanation == null) {
                    buildImportanceExplanationLocked();
                }
            }
            return mImportanceExplanation.get(key);
        }

        private String getOverrideGroupKey(String key) {
            synchronized (this) {
                if (mOverrideGroupKeys == null) {
                    buildOverrideGroupKeys();
                }
            }
            return mOverrideGroupKeys.get(key);
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

        // Locked by 'this'
        private void buildSuppressedVisualEffectsLocked() {
            Bundle suppressedBundle = mRankingUpdate.getSuppressedVisualEffects();
            mSuppressedVisualEffects = new ArrayMap<>(suppressedBundle.size());
            for (String key: suppressedBundle.keySet()) {
                mSuppressedVisualEffects.put(key, suppressedBundle.getInt(key));
            }
        }
        // Locked by 'this'
        private void buildImportanceLocked() {
            String[] orderedKeys = mRankingUpdate.getOrderedKeys();
            int[] importance = mRankingUpdate.getImportance();
            mImportance = new ArrayMap<>(orderedKeys.length);
            for (int i = 0; i < orderedKeys.length; i++) {
                String key = orderedKeys[i];
                mImportance.put(key, importance[i]);
            }
        }

        // Locked by 'this'
        private void buildImportanceExplanationLocked() {
            Bundle explanationBundle = mRankingUpdate.getImportanceExplanation();
            mImportanceExplanation = new ArrayMap<>(explanationBundle.size());
            for (String key: explanationBundle.keySet()) {
                mImportanceExplanation.put(key, explanationBundle.getString(key));
            }
        }

        // Locked by 'this'
        private void buildOverrideGroupKeys() {
            Bundle overrideGroupKeys = mRankingUpdate.getOverrideGroupKeys();
            mOverrideGroupKeys = new ArrayMap<>(overrideGroupKeys.size());
            for (String key: overrideGroupKeys.keySet()) {
                mOverrideGroupKeys.put(key, overrideGroupKeys.getString(key));
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

    private final class MyHandler extends Handler {
        public static final int MSG_ON_NOTIFICATION_POSTED = 1;
        public static final int MSG_ON_NOTIFICATION_REMOVED = 2;
        public static final int MSG_ON_LISTENER_CONNECTED = 3;
        public static final int MSG_ON_NOTIFICATION_RANKING_UPDATE = 4;
        public static final int MSG_ON_LISTENER_HINTS_CHANGED = 5;
        public static final int MSG_ON_INTERRUPTION_FILTER_CHANGED = 6;

        public MyHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        public void handleMessage(Message msg) {
            if (!isConnected) {
                return;
            }
            switch (msg.what) {
                case MSG_ON_NOTIFICATION_POSTED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    StatusBarNotification sbn = (StatusBarNotification) args.arg1;
                    RankingMap rankingMap = (RankingMap) args.arg2;
                    args.recycle();
                    onNotificationPosted(sbn, rankingMap);
                } break;

                case MSG_ON_NOTIFICATION_REMOVED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    StatusBarNotification sbn = (StatusBarNotification) args.arg1;
                    RankingMap rankingMap = (RankingMap) args.arg2;
                    args.recycle();
                    onNotificationRemoved(sbn, rankingMap);
                } break;

                case MSG_ON_LISTENER_CONNECTED: {
                    onListenerConnected();
                } break;

                case MSG_ON_NOTIFICATION_RANKING_UPDATE: {
                    RankingMap rankingMap = (RankingMap) msg.obj;
                    onNotificationRankingUpdate(rankingMap);
                } break;

                case MSG_ON_LISTENER_HINTS_CHANGED: {
                    final int hints = msg.arg1;
                    onListenerHintsChanged(hints);
                } break;

                case MSG_ON_INTERRUPTION_FILTER_CHANGED: {
                    final int interruptionFilter = msg.arg1;
                    onInterruptionFilterChanged(interruptionFilter);
                } break;
            }
        }
    }
}
