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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.ActivityManager;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.Person;
import android.app.Service;
import android.companion.CompanionDeviceManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
 *     &lt;meta-data
 *               android:name="android.service.notification.default_filter_types"
 *               android:value="conversations,alerting">
 *           &lt;/meta-data>
 *     &lt;meta-data
 *               android:name="android.service.notification.disabled_filter_types"
 *               android:value="ongoing,silent">
 *           &lt;/meta-data>
 * &lt;/service></pre>
 *
 * <p>The service should wait for the {@link #onListenerConnected()} event
 * before performing any operations. The {@link #requestRebind(ComponentName)}
 * method is the <i>only</i> one that is safe to call before {@link #onListenerConnected()}
 * or after {@link #onListenerDisconnected()}.
 * </p>
 * <p> Notification listeners cannot get notification access or be bound by the system on
 * {@linkplain ActivityManager#isLowRamDevice() low-RAM} devices running Android Q (and below).
 * The system also ignores notification listeners running in a work profile. A
 * {@link android.app.admin.DevicePolicyManager} might block notifications originating from a work
 * profile.</p>
 * <p>
 *     From {@link Build.VERSION_CODES#N} onward all callbacks are called on the main thread. Prior
 *     to N, there is no guarantee on what thread the callback will happen.
 * </p>
 */
public abstract class NotificationListenerService extends Service {

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final String TAG = getClass().getSimpleName();

    /**
     * The name of the {@code meta-data} tag containing a comma separated list of default
     * integer notification types that should be provided to this listener. See
     * {@link #FLAG_FILTER_TYPE_ONGOING},
     * {@link #FLAG_FILTER_TYPE_CONVERSATIONS}, {@link #FLAG_FILTER_TYPE_ALERTING),
     * and {@link #FLAG_FILTER_TYPE_SILENT}.
     * <p>This value will only be read if the app has not previously specified a default type list,
     * and if the user has not overridden the allowed types.</p>
     * <p>An absent value means 'allow all types'.
     * A present but empty value means 'allow no types'.</p>
     *
     */
    public static final String META_DATA_DEFAULT_FILTER_TYPES
            = "android.service.notification.default_filter_types";

    /**
     * The name of the {@code meta-data} tag containing a comma separated list of default
     * integer notification types that this listener never wants to receive. See
     * {@link #FLAG_FILTER_TYPE_ONGOING},
     * {@link #FLAG_FILTER_TYPE_CONVERSATIONS}, {@link #FLAG_FILTER_TYPE_ALERTING),
     * and {@link #FLAG_FILTER_TYPE_SILENT}.
     * <p>Types provided in this list will appear as 'off' and 'disabled' in the user interface,
     * so users don't enable a type that the listener will never bridge to their paired devices.</p>
     *
     */
    public static final String META_DATA_DISABLED_FILTER_TYPES
            = "android.service.notification.disabled_filter_types";

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
     * should disable phone call sounds, but not notification sound.
     * This does not change the interruption filter, only the effects. **/
    public static final int HINT_HOST_DISABLE_CALL_EFFECTS = 1 << 2;

    /**
     * Whether notification suppressed by DND should not interruption visually when the screen is
     * off.
     *
     * @deprecated Use the more specific visual effects in {@link NotificationManager.Policy}.
     */
    @Deprecated
    public static final int SUPPRESSED_EFFECT_SCREEN_OFF =
            NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF;
    /**
     * Whether notification suppressed by DND should not interruption visually when the screen is
     * on.
     *
     * @deprecated Use the more specific visual effects in {@link NotificationManager.Policy}.
     */
    @Deprecated
    public static final int SUPPRESSED_EFFECT_SCREEN_ON =
            NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON;


    // Notification cancellation reasons

    /** Notification was canceled by the status bar reporting a notification click. */
    public static final int REASON_CLICK = 1;
    /** Notification was canceled by the status bar reporting a user dismissal. */
    public static final int REASON_CANCEL = 2;
    /** Notification was canceled by the status bar reporting a user dismiss all. */
    public static final int REASON_CANCEL_ALL = 3;
    /** Notification was canceled by the status bar reporting an inflation error. */
    public static final int REASON_ERROR = 4;
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
    /** Notification was canceled by the device administrator suspending the package. */
    public static final int REASON_PACKAGE_SUSPENDED = 14;
    /** Notification was canceled by the owning managed profile being turned off. */
    public static final int REASON_PROFILE_TURNED_OFF = 15;
    /** Autobundled summary notification was canceled because its group was unbundled */
    public static final int REASON_UNAUTOBUNDLED = 16;
    /** Notification was canceled by the user banning the channel. */
    public static final int REASON_CHANNEL_BANNED = 17;
    /** Notification was snoozed. */
    public static final int REASON_SNOOZED = 18;
    /** Notification was canceled due to timeout */
    public static final int REASON_TIMEOUT = 19;
    /** Notification was canceled due to the backing channel being deleted */
    public static final int REASON_CHANNEL_REMOVED = 20;
    /** Notification was canceled due to the app's storage being cleared */
    public static final int REASON_CLEAR_DATA = 21;

    /**
     * @hide
     */
    @IntDef(prefix = "REASON_", value = {
            REASON_CLICK,
            REASON_CANCEL,
            REASON_CANCEL_ALL,
            REASON_ERROR,
            REASON_PACKAGE_CHANGED,
            REASON_USER_STOPPED,
            REASON_PACKAGE_BANNED,
            REASON_APP_CANCEL,
            REASON_APP_CANCEL_ALL,
            REASON_LISTENER_CANCEL,
            REASON_LISTENER_CANCEL_ALL,
            REASON_GROUP_SUMMARY_CANCELED,
            REASON_GROUP_OPTIMIZATION,
            REASON_PACKAGE_SUSPENDED,
            REASON_PROFILE_TURNED_OFF,
            REASON_UNAUTOBUNDLED,
            REASON_CHANNEL_BANNED,
            REASON_SNOOZED,
            REASON_TIMEOUT
    })
    public @interface NotificationCancelReason{};

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = { "FLAG_FILTER_TYPE_" }, value = {
            FLAG_FILTER_TYPE_CONVERSATIONS,
            FLAG_FILTER_TYPE_ALERTING,
            FLAG_FILTER_TYPE_SILENT,
            FLAG_FILTER_TYPE_ONGOING
    })
    public @interface NotificationFilterTypes {}
    /**
     * A flag value indicating that this notification listener can see conversation type
     * notifications.
     */
    public static final int FLAG_FILTER_TYPE_CONVERSATIONS = 1;
    /**
     * A flag value indicating that this notification listener can see altering type notifications.
     */
    public static final int FLAG_FILTER_TYPE_ALERTING = 2;
    /**
     * A flag value indicating that this notification listener can see silent type notifications.
     */
    public static final int FLAG_FILTER_TYPE_SILENT = 4;
    /**
     * A flag value indicating that this notification listener can see important
     * ( > {@link NotificationManager#IMPORTANCE_MIN}) ongoing type notifications.
     */
    public static final int FLAG_FILTER_TYPE_ONGOING = 8;

    /**
     * The full trim of the StatusBarNotification including all its features.
     *
     * @hide
     * @removed
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
     * @removed
     */
    @SystemApi
    public static final int TRIM_LIGHT = 1;


    /** @hide */
    @IntDef(prefix = { "NOTIFICATION_CHANNEL_OR_GROUP_" }, value = {
            NOTIFICATION_CHANNEL_OR_GROUP_ADDED,
            NOTIFICATION_CHANNEL_OR_GROUP_UPDATED,
            NOTIFICATION_CHANNEL_OR_GROUP_DELETED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ChannelOrGroupModificationTypes {}

    /**
     * Channel or group modification reason provided to
     * {@link #onNotificationChannelModified(String, UserHandle,NotificationChannel, int)} or
     * {@link #onNotificationChannelGroupModified(String, UserHandle, NotificationChannelGroup,
     * int)}- the provided object was created.
     */
    public static final int NOTIFICATION_CHANNEL_OR_GROUP_ADDED = 1;

    /**
     * Channel or group modification reason provided to
     * {@link #onNotificationChannelModified(String, UserHandle, NotificationChannel, int)} or
     * {@link #onNotificationChannelGroupModified(String, UserHandle,NotificationChannelGroup, int)}
     * - the provided object was updated.
     */
    public static final int NOTIFICATION_CHANNEL_OR_GROUP_UPDATED = 2;

    /**
     * Channel or group modification reason provided to
     * {@link #onNotificationChannelModified(String, UserHandle, NotificationChannel, int)} or
     * {@link #onNotificationChannelGroupModified(String, UserHandle, NotificationChannelGroup,
     * int)}- the provided object was deleted.
     */
    public static final int NOTIFICATION_CHANNEL_OR_GROUP_DELETED = 3;

    private final Object mLock = new Object();

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private Handler mHandler;

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    protected NotificationListenerWrapper mWrapper = null;
    private boolean isConnected = false;

    @GuardedBy("mLock")
    private RankingMap mRankingMap;

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    protected INotificationManager mNoMan;

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
     * Implement this method to learn when notifications are removed and why.
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
     * @param reason see {@link #REASON_LISTENER_CANCEL}, etc.
     */
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
            int reason) {
        onNotificationRemoved(sbn, rankingMap);
    }

    /**
     * NotificationStats are not populated for notification listeners, so fall back to
     * {@link #onNotificationRemoved(StatusBarNotification, RankingMap, int)}.
     *
     * @hide
     */
    @SystemApi
    public void onNotificationRemoved(@NonNull StatusBarNotification sbn,
            @NonNull RankingMap rankingMap, @NonNull NotificationStats stats, int reason) {
        onNotificationRemoved(sbn, rankingMap, reason);
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
     * Implement this method to be notified when the behavior of silent notifications in the status
     * bar changes. See {@link NotificationManager#shouldHideSilentStatusBarIcons()}.
     *
     * @param hideSilentStatusIcons whether or not status bar icons should be hidden for silent
     *                              notifications
     */
    public void onSilentStatusBarIconsVisibilityChanged(boolean hideSilentStatusIcons) {
        // optional
    }

    /**
     * Implement this method to learn about notification channel modifications.
     *
     * <p>The caller must have {@link CompanionDeviceManager#getAssociations() an associated
     * device} in order to receive this callback.
     *
     * @param pkg The package the channel belongs to.
     * @param user The user on which the change was made.
     * @param channel The channel that has changed.
     * @param modificationType One of {@link #NOTIFICATION_CHANNEL_OR_GROUP_ADDED},
     *                   {@link #NOTIFICATION_CHANNEL_OR_GROUP_UPDATED},
     *                   {@link #NOTIFICATION_CHANNEL_OR_GROUP_DELETED}.
     */
    public void onNotificationChannelModified(String pkg, UserHandle user,
            NotificationChannel channel, @ChannelOrGroupModificationTypes int modificationType) {
        // optional
    }

    /**
     * Implement this method to learn about notification channel group modifications.
     *
     * <p>The caller must have {@link CompanionDeviceManager#getAssociations() an associated
     * device} in order to receive this callback.
     *
     * @param pkg The package the group belongs to.
     * @param user The user on which the change was made.
     * @param group The group that has changed.
     * @param modificationType One of {@link #NOTIFICATION_CHANNEL_OR_GROUP_ADDED},
     *                   {@link #NOTIFICATION_CHANNEL_OR_GROUP_UPDATED},
     *                   {@link #NOTIFICATION_CHANNEL_OR_GROUP_DELETED}.
     */
    public void onNotificationChannelGroupModified(String pkg, UserHandle user,
            NotificationChannelGroup group, @ChannelOrGroupModificationTypes int modificationType) {
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
    @Deprecated
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
     * Inform the notification manager about snoozing a specific notification.
     * <p>
     * Use this if your listener has a user interface that allows the user to snooze a notification
     * until a given {@link SnoozeCriterion}. It should be called after the user snoozes a single
     * notification using your UI; upon being informed, the notification manager will actually
     * remove the notification and you will get an
     * {@link #onNotificationRemoved(StatusBarNotification)} callback. When the snoozing period
     * expires, you will get a {@link #onNotificationPosted(StatusBarNotification, RankingMap)}
     * callback for the notification.
     * @param key The key of the notification to snooze
     * @param snoozeCriterionId The{@link SnoozeCriterion#getId()} of a context to snooze the
     *                          notification until.
     * @hide
     * @removed
     */
    @SystemApi
    public final void snoozeNotification(String key, String snoozeCriterionId) {
        if (!isBound()) return;
        try {
            getNotificationInterface().snoozeNotificationUntilContextFromListener(
                    mWrapper, key, snoozeCriterionId);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
    }

    /**
     * Inform the notification manager about snoozing a specific notification.
     * <p>
     * Use this if your listener has a user interface that allows the user to snooze a notification
     * for a time. It should be called after the user snoozes a single notification using
     * your UI; upon being informed, the notification manager will actually remove the notification
     * and you will get an {@link #onNotificationRemoved(StatusBarNotification)} callback. When the
     * snoozing period expires, you will get a
     * {@link #onNotificationPosted(StatusBarNotification, RankingMap)} callback for the
     * notification.
     * @param key The key of the notification to snooze
     * @param durationMs A duration to snooze the notification for, in milliseconds.
     */
    public final void snoozeNotification(String key, long durationMs) {
        if (!isBound()) return;
        try {
            getNotificationInterface().snoozeNotificationUntilFromListener(
                    mWrapper, key, durationMs);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
    }

    /**
     * Lets an app migrate notification filters from its app into the OS.
     *
     * <p>This call will be ignored if the app has already migrated these settings or the user
     * has set filters in the UI. This method is intended for user specific settings; if an app has
     * already specified defaults types in its manifest with
     * {@link #META_DATA_DEFAULT_FILTER_TYPES}, the defaultTypes option will be ignored.</p>
     * @param defaultTypes A value representing the types of notifications that this listener should
     * receive by default
     * @param disallowedPkgs A list of package names whose notifications should not be seen by this
     * listener, by default, because the listener does not process or display them, or because a
     * user had previously disallowed these packages in the listener app's UI
     */
    public final void migrateNotificationFilter(@NotificationFilterTypes int defaultTypes,
            @Nullable List<String> disallowedPkgs) {
        if (!isBound()) return;
        try {
            getNotificationInterface().migrateNotificationFilter(
                    mWrapper, defaultTypes, disallowedPkgs);
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
     * Updates a notification channel for a given package for a given user. This should only be used
     * to reflect changes a user has made to the channel via the listener's user interface.
     *
     * <p>This method will throw a security exception if you don't have access to notifications
     * for the given user.</p>
     * <p>The caller must have {@link CompanionDeviceManager#getAssociations() an associated
     * device} in order to use this method.
     *
     * @param pkg The package the channel belongs to.
     * @param user The user the channel belongs to.
     * @param channel the channel to update.
     */
    public final void updateNotificationChannel(@NonNull String pkg, @NonNull UserHandle user,
            @NonNull NotificationChannel channel) {
        if (!isBound()) return;
        try {
            getNotificationInterface().updateNotificationChannelFromPrivilegedListener(
                    mWrapper, pkg, user, channel);
        } catch (RemoteException e) {
            Log.v(TAG, "Unable to contact notification manager", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all notification channels belonging to the given package for a given user.
     *
     * <p>This method will throw a security exception if you don't have access to notifications
     * for the given user.</p>
     * <p>The caller must have {@link CompanionDeviceManager#getAssociations() an associated
     * device} or be the {@link NotificationAssistantService notification assistant} in order to
     * use this method.
     *
     * @param pkg The package to retrieve channels for.
     */
    public final List<NotificationChannel> getNotificationChannels(@NonNull String pkg,
            @NonNull UserHandle user) {
        if (!isBound()) return null;
        try {

            return getNotificationInterface().getNotificationChannelsFromPrivilegedListener(
                    mWrapper, pkg, user).getList();
        } catch (RemoteException e) {
            Log.v(TAG, "Unable to contact notification manager", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all notification channel groups belonging to the given package for a given user.
     *
     * <p>This method will throw a security exception if you don't have access to notifications
     * for the given user.</p>
     * <p>The caller must have {@link CompanionDeviceManager#getAssociations() an associated
     * device} or be the {@link NotificationAssistantService notification assistant} in order to
     * use this method.
     *
     * @param pkg The package to retrieve channel groups for.
     */
    public final List<NotificationChannelGroup> getNotificationChannelGroups(@NonNull String pkg,
            @NonNull UserHandle user) {
        if (!isBound()) return null;
        try {

            return getNotificationInterface().getNotificationChannelGroupsFromPrivilegedListener(
                    mWrapper, pkg, user).getList();
        } catch (RemoteException e) {
            Log.v(TAG, "Unable to contact notification manager", e);
            throw e.rethrowFromSystemServer();
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
     * @removed
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
        StatusBarNotification[] activeNotifications = getActiveNotifications(null, TRIM_FULL);
        return activeNotifications != null ? activeNotifications : new StatusBarNotification[0];
    }

    /**
     * Like {@link #getActiveNotifications()}, but returns the list of currently snoozed
     * notifications, for all users this listener has access to.
     *
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
     *
     * @return An array of snoozed notifications, sorted in natural order.
     */
    public final StatusBarNotification[] getSnoozedNotifications() {
        try {
            ParceledListSlice<StatusBarNotification> parceledList = getNotificationInterface()
                    .getSnoozedNotificationsFromListener(mWrapper, TRIM_FULL);
            return cleanUpNotificationList(parceledList);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
        return null;
    }

    /**
     * Request the list of outstanding notifications (that is, those that are visible to the
     * current user). Useful when you don't know what's already been posted.
     *
     * @hide
     * @removed
     *
     * @param trim trim of the notifications to be returned. See <code>TRIM_*</code> constants.
     * @return An array of active notifications, sorted in natural order.
     */
    @SystemApi
    public StatusBarNotification[] getActiveNotifications(int trim) {
        StatusBarNotification[] activeNotifications = getActiveNotifications(null, trim);
        return activeNotifications != null ? activeNotifications : new StatusBarNotification[0];
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
        StatusBarNotification[] activeNotifications = getActiveNotifications(keys, TRIM_FULL);
        return activeNotifications != null ? activeNotifications : new StatusBarNotification[0];
    }

    /**
     * Request one or more notifications by key. Useful if you have been keeping track of
     * notifications but didn't want to retain the bits, and now need to go back and extract
     * more data out of those notifications.
     *
     * @hide
     * @removed
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
            return cleanUpNotificationList(parceledList);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
        }
        return null;
    }

    private StatusBarNotification[] cleanUpNotificationList(
            ParceledListSlice<StatusBarNotification> parceledList) {
        if (parceledList == null || parceledList.getList() == null) {
            return new StatusBarNotification[0];
        }
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
                // populate people for older clients.
                maybePopulatePeople(notification);
            } catch (IllegalArgumentException e) {
                if (corruptNotifications == null) {
                    corruptNotifications = new ArrayList<>(N);
                }
                corruptNotifications.add(sbn);
                Log.w(TAG, "get(Active/Snoozed)Notifications: can't rebuild notification from " +
                        sbn.getPackageName());
            }
        }
        if (corruptNotifications != null) {
            list.removeAll(corruptNotifications);
        }
        return list.toArray(new StatusBarNotification[list.size()]);
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
     * Clears listener hints set via {@link #getCurrentListenerHints()}.
     *
     * <p>The service should wait for the {@link #onListenerConnected()} event
     * before performing this operation.
     */
    public final void clearRequestedListenerHints() {
        if (!isBound()) return;
        try {
            getNotificationInterface().clearRequestedListenerHints(mWrapper);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
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
    @UnsupportedAppUsage
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
     * @removed
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
     * @removed
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
     * <p>Once this is called, you will no longer receive updates and no method calls are
     * guaranteed to be successful, until you next receive the {@link #onListenerConnected()} event.
     * The service will likely be killed by the system after this call.
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

    /**
     * Convert new-style Icons to legacy representations for pre-M clients.
     * @hide
     */
    public final void createLegacyIconExtras(Notification n) {
        if (getContext().getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.M) {
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

    /**
     * Populates remote views for pre-P targeting apps.
     */
    private void maybePopulatePeople(Notification notification) {
        if (getContext().getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.P) {
            ArrayList<Person> people = notification.extras.getParcelableArrayList(
                    Notification.EXTRA_PEOPLE_LIST);
            if (people != null && people.isEmpty()) {
                int size = people.size();
                String[] peopleArray = new String[size];
                for (int i = 0; i < size; i++) {
                    Person person = people.get(i);
                    peopleArray[i] = person.resolveToLegacyUri();
                }
                notification.extras.putStringArray(Notification.EXTRA_PEOPLE, peopleArray);
            }
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
            if (sbn == null) {
                Log.w(TAG, "onNotificationPosted: Error receiving StatusBarNotification");
                return;
            }

            try {
                // convert icon metadata to legacy format for older clients
                createLegacyIconExtras(sbn.getNotification());
                maybePopulateRemoteViews(sbn.getNotification());
                maybePopulatePeople(sbn.getNotification());
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
                NotificationRankingUpdate update, NotificationStats stats, int reason) {
            StatusBarNotification sbn;
            try {
                sbn = sbnHolder.get();
            } catch (RemoteException e) {
                Log.w(TAG, "onNotificationRemoved: Error receiving StatusBarNotification", e);
                return;
            }
            if (sbn == null) {
                Log.w(TAG, "onNotificationRemoved: Error receiving StatusBarNotification");
                return;
            }
            // protect subclass from concurrent modifications of (@link mNotificationKeys}.
            synchronized (mLock) {
                applyUpdateLocked(update);
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = sbn;
                args.arg2 = mRankingMap;
                args.arg3 = reason;
                args.arg4 = stats;
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
        public void onNotificationEnqueuedWithChannel(
                IStatusBarNotificationHolder notificationHolder, NotificationChannel channel,
                NotificationRankingUpdate update)
                throws RemoteException {
            // no-op in the listener
        }

        @Override
        public void onNotificationsSeen(List<String> keys)
                throws RemoteException {
            // no-op in the listener
        }

        @Override
        public void onPanelRevealed(int items) throws RemoteException {
            // no-op in the listener
        }

        @Override
        public void onPanelHidden() throws RemoteException {
            // no-op in the listener
        }

        @Override
        public void onNotificationVisibilityChanged(
                String key, boolean isVisible) {
            // no-op in the listener
        }

        @Override
        public void onNotificationSnoozedUntilContext(
                IStatusBarNotificationHolder notificationHolder, String snoozeCriterionId)
                throws RemoteException {
            // no-op in the listener
        }

        @Override
        public void onNotificationExpansionChanged(
                String key, boolean isUserAction, boolean isExpanded) {
            // no-op in the listener
        }

        @Override
        public void onNotificationDirectReply(String key) {
            // no-op in the listener
        }

        @Override
        public void onSuggestedReplySent(String key, CharSequence reply, int source) {
            // no-op in the listener
        }

        @Override
        public void onActionClicked(String key, Notification.Action action, int source) {
            // no-op in the listener
        }

        @Override
        public void onNotificationClicked(String key) {
            // no-op in the listener
        }

        @Override
        public void onAllowedAdjustmentsChanged() {
            // no-op in the listener
        }

        @Override
        public void onNotificationChannelModification(String pkgName, UserHandle user,
                NotificationChannel channel,
                @ChannelOrGroupModificationTypes int modificationType) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = pkgName;
            args.arg2 = user;
            args.arg3 = channel;
            args.arg4 = modificationType;
            mHandler.obtainMessage(
                    MyHandler.MSG_ON_NOTIFICATION_CHANNEL_MODIFIED, args).sendToTarget();
        }

        @Override
        public void onNotificationChannelGroupModification(String pkgName, UserHandle user,
                NotificationChannelGroup group,
                @ChannelOrGroupModificationTypes int modificationType) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = pkgName;
            args.arg2 = user;
            args.arg3 = group;
            args.arg4 = modificationType;
            mHandler.obtainMessage(
                    MyHandler.MSG_ON_NOTIFICATION_CHANNEL_GROUP_MODIFIED, args).sendToTarget();
        }

        @Override
        public void onStatusBarIconsBehaviorChanged(boolean hideSilentStatusIcons) {
            mHandler.obtainMessage(MyHandler.MSG_ON_STATUS_BAR_ICON_BEHAVIOR_CHANGED,
                    hideSilentStatusIcons).sendToTarget();
        }

        @Override
        public void onNotificationFeedbackReceived(String key, NotificationRankingUpdate update,
                Bundle feedback) {
            // no-op in the listener
        }


    }

    /**
     * @hide
     */
    @GuardedBy("mLock")
    public final void applyUpdateLocked(NotificationRankingUpdate update) {
        mRankingMap = update.getRankingMap();
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

        /**
         * Value signifying that the user and device policy manager have not expressed a lockscreen
         * visibility override for a notification.
         */
        public static final int VISIBILITY_NO_OVERRIDE = NotificationManager.VISIBILITY_NO_OVERRIDE;

        /**
         * The user is likely to have a negative reaction to this notification.
         */
        public static final int USER_SENTIMENT_NEGATIVE = -1;
        /**
         * It is not known how the user will react to this notification.
         */
        public static final int USER_SENTIMENT_NEUTRAL = 0;
        /**
         * The user is likely to have a positive reaction to this notification.
         */
        public static final int USER_SENTIMENT_POSITIVE = 1;

       /** @hide */
        @IntDef(prefix = { "USER_SENTIMENT_" }, value = {
                USER_SENTIMENT_NEGATIVE, USER_SENTIMENT_NEUTRAL, USER_SENTIMENT_POSITIVE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface UserSentiment {}

        /**
         * Notification was demoted in shade
         * @hide
         */
        public static final int RANKING_DEMOTED = -1;
        /**
         * Notification was unchanged
         * @hide
         */
        public static final int RANKING_UNCHANGED = 0;
        /**
         * Notification was promoted in shade
         * @hide
         */
        public static final int RANKING_PROMOTED = 1;

        /** @hide */
        @IntDef(prefix = { "RANKING_" }, value = {
                RANKING_PROMOTED, RANKING_DEMOTED, RANKING_UNCHANGED
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface RankingAdjustment {}

        private @NonNull String mKey;
        private int mRank = -1;
        private boolean mIsAmbient;
        private boolean mMatchesInterruptionFilter;
        private int mVisibilityOverride;
        private int mSuppressedVisualEffects;
        private @NotificationManager.Importance int mImportance;
        private CharSequence mImportanceExplanation;
        private float mRankingScore;
        // System specified group key.
        private String mOverrideGroupKey;
        // Notification assistant channel override.
        private NotificationChannel mChannel;
        // Notification assistant people override.
        private ArrayList<String> mOverridePeople;
        // Notification assistant snooze criteria.
        private ArrayList<SnoozeCriterion> mSnoozeCriteria;
        private boolean mShowBadge;
        private @UserSentiment int mUserSentiment = USER_SENTIMENT_NEUTRAL;
        private boolean mHidden;
        private long mLastAudiblyAlertedMs;
        private boolean mNoisy;
        private ArrayList<Notification.Action> mSmartActions;
        private ArrayList<CharSequence> mSmartReplies;
        private boolean mCanBubble;
        private boolean mVisuallyInterruptive;
        private boolean mIsConversation;
        private ShortcutInfo mShortcutInfo;
        private @RankingAdjustment int mRankingAdjustment;
        private boolean mIsBubble;

        private static final int PARCEL_VERSION = 2;

        public Ranking() { }

        // You can parcel it, but it's not Parcelable
        /** @hide */
        @VisibleForTesting
        public void writeToParcel(Parcel out, int flags) {
            final long start = out.dataPosition();
            out.writeInt(PARCEL_VERSION);
            out.writeString(mKey);
            out.writeInt(mRank);
            out.writeBoolean(mIsAmbient);
            out.writeBoolean(mMatchesInterruptionFilter);
            out.writeInt(mVisibilityOverride);
            out.writeInt(mSuppressedVisualEffects);
            out.writeInt(mImportance);
            out.writeCharSequence(mImportanceExplanation);
            out.writeFloat(mRankingScore);
            out.writeString(mOverrideGroupKey);
            out.writeParcelable(mChannel, flags);
            out.writeStringList(mOverridePeople);
            out.writeTypedList(mSnoozeCriteria, flags);
            out.writeBoolean(mShowBadge);
            out.writeInt(mUserSentiment);
            out.writeBoolean(mHidden);
            out.writeLong(mLastAudiblyAlertedMs);
            out.writeBoolean(mNoisy);
            out.writeTypedList(mSmartActions, flags);
            out.writeCharSequenceList(mSmartReplies);
            out.writeBoolean(mCanBubble);
            out.writeBoolean(mVisuallyInterruptive);
            out.writeBoolean(mIsConversation);
            out.writeParcelable(mShortcutInfo, flags);
            out.writeInt(mRankingAdjustment);
            out.writeBoolean(mIsBubble);
        }

        /** @hide */
        @VisibleForTesting
        public Ranking(Parcel in) {
            final ClassLoader cl = getClass().getClassLoader();

            final int version = in.readInt();
            if (version != PARCEL_VERSION) {
                throw new IllegalArgumentException("malformed Ranking parcel: " + in + " version "
                        + version + ", expected " + PARCEL_VERSION);
            }
            mKey = in.readString();
            mRank = in.readInt();
            mIsAmbient = in.readBoolean();
            mMatchesInterruptionFilter = in.readBoolean();
            mVisibilityOverride = in.readInt();
            mSuppressedVisualEffects = in.readInt();
            mImportance = in.readInt();
            mImportanceExplanation = in.readCharSequence(); // may be null
            mRankingScore = in.readFloat();
            mOverrideGroupKey = in.readString(); // may be null
            mChannel = in.readParcelable(cl); // may be null
            mOverridePeople = in.createStringArrayList();
            mSnoozeCriteria = in.createTypedArrayList(SnoozeCriterion.CREATOR);
            mShowBadge = in.readBoolean();
            mUserSentiment = in.readInt();
            mHidden = in.readBoolean();
            mLastAudiblyAlertedMs = in.readLong();
            mNoisy = in.readBoolean();
            mSmartActions = in.createTypedArrayList(Notification.Action.CREATOR);
            mSmartReplies = in.readCharSequenceList();
            mCanBubble = in.readBoolean();
            mVisuallyInterruptive = in.readBoolean();
            mIsConversation = in.readBoolean();
            mShortcutInfo = in.readParcelable(cl);
            mRankingAdjustment = in.readInt();
            mIsBubble = in.readBoolean();
        }


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
         * Returns the user or device policy manager specified visibility (see
         * {@link Notification#VISIBILITY_PRIVATE}, {@link Notification#VISIBILITY_PUBLIC},
         * {@link Notification#VISIBILITY_SECRET}) for this notification, or
         * {@link NotificationListenerService.Ranking#VISIBILITY_NO_OVERRIDE} if
         * no such preference has been expressed.
         */
        public @Notification.NotificationVisibilityOverride
        int getLockscreenVisibilityOverride() {
            return mVisibilityOverride;
        }

        /**
         * Returns the type(s) of visual effects that should be suppressed for this notification.
         * See {@link NotificationManager.Policy}, e.g.
         * {@link NotificationManager.Policy#SUPPRESSED_EFFECT_LIGHTS}.
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
         * @return the importance of the notification
         */
        public @NotificationManager.Importance int getImportance() {
            return mImportance;
        }

        /**
         * If the importance has been overridden by user preference, then this will be non-null,
         * and should be displayed to the user.
         *
         * @return the explanation for the importance, or null if it is the natural importance
         */
        public CharSequence getImportanceExplanation() {
            return mImportanceExplanation;
        }

        /**
         * Returns the ranking score provided by the {@link NotificationAssistantService} to
         * sort the notifications in the shade
         *
         * @return the ranking score of the notification, range from -1 to 1
         * @hide
         */
        public float getRankingScore() {
            return mRankingScore;
        }

        /**
         * If the system has overridden the group key, then this will be non-null, and this
         * key should be used to bundle notifications.
         */
        public String getOverrideGroupKey() {
            return mOverrideGroupKey;
        }

        /**
         * Returns the notification channel this notification was posted to, which dictates
         * notification behavior and presentation.
         */
        public NotificationChannel getChannel() {
            return mChannel;
        }

        /**
         * Returns how the system thinks the user feels about notifications from the
         * channel provided by {@link #getChannel()}. You can use this information to expose
         * controls to help the user block this channel's notifications, if the sentiment is
         * {@link #USER_SENTIMENT_NEGATIVE}, or emphasize this notification if the sentiment is
         * {@link #USER_SENTIMENT_POSITIVE}.
         */
        public int getUserSentiment() {
            return mUserSentiment;
        }

        /**
         * If the {@link NotificationAssistantService} has added people to this notification, then
         * this will be non-null.
         * @hide
         * @removed
         */
        @SystemApi
        public List<String> getAdditionalPeople() {
            return mOverridePeople;
        }

        /**
         * Returns snooze criteria provided by the {@link NotificationAssistantService}. If your
         * user interface displays options for snoozing notifications these criteria should be
         * displayed as well.
         * @hide
         * @removed
         */
        @SystemApi
        public List<SnoozeCriterion> getSnoozeCriteria() {
            return mSnoozeCriteria;
        }

        /**
         * Returns a list of smart {@link Notification.Action} that can be added by the
         * {@link NotificationAssistantService}
         */
        public @NonNull List<Notification.Action> getSmartActions() {
            return mSmartActions == null ? Collections.emptyList() : mSmartActions;
        }

        /**
         * Returns a list of smart replies that can be added by the
         * {@link NotificationAssistantService}
         */
        public @NonNull List<CharSequence> getSmartReplies() {
            return mSmartReplies == null ? Collections.emptyList() : mSmartReplies;
        }

        /**
         * Returns whether this notification can be displayed as a badge.
         *
         * @return true if the notification can be displayed as a badge, false otherwise.
         */
        public boolean canShowBadge() {
            return mShowBadge;
        }

        /**
         * Returns whether the app that posted this notification is suspended, so this notification
         * should be hidden.
         *
         * @return true if the notification should be hidden, false otherwise.
         */
        public boolean isSuspended() {
            return mHidden;
        }

        /**
         * Returns the last time this notification alerted the user via sound or vibration.
         *
         * @return the time of the last alerting behavior, in milliseconds.
         */
        @CurrentTimeMillisLong
        public long getLastAudiblyAlertedMillis() {
            return mLastAudiblyAlertedMs;
        }

        /**
         * Returns whether the user has allowed bubbles globally, at the app level, and at the
         * channel level for this notification.
         *
         * <p>This does not take into account the current importance of the notification, the
         * current DND state, or whether the posting app is foreground.</p>
         */
        public boolean canBubble() {
            return mCanBubble;
        }

        /** @hide */
        public boolean visuallyInterruptive() {
            return mVisuallyInterruptive;
        }

        /** @hide */
        public boolean isNoisy() {
            return mNoisy;
        }

        /**
         * Returns whether this notification is a conversation notification, and would appear
         * in the conversation section of the notification shade, on devices that separate that
         * type of notification.
         */
        public boolean isConversation() {
            return mIsConversation;
        }

        /**
         * Returns whether this notification is actively a bubble.
         * @hide
         */
        public boolean isBubble() {
            return mIsBubble;
        }

        /**
         * Returns the shortcut information associated with this notification, if it is a
         * {@link #isConversation() conversation notification}.
         * <p>This might be null even if the notification is a conversation notification, if
         * the posting app hasn't opted into the full conversation feature set yet.</p>
         */
        public @Nullable ShortcutInfo getConversationShortcutInfo() {
            return mShortcutInfo;
        }

        /**
         * Returns the intended transition to ranking passed by {@link NotificationAssistantService}
         * @hide
         */
        public @RankingAdjustment int getRankingAdjustment() {
            return mRankingAdjustment;
        }

        /**
         * @hide
         */
        @VisibleForTesting
        public void populate(String key, int rank, boolean matchesInterruptionFilter,
                int visibilityOverride, int suppressedVisualEffects, int importance,
                CharSequence explanation, String overrideGroupKey,
                NotificationChannel channel, ArrayList<String> overridePeople,
                ArrayList<SnoozeCriterion> snoozeCriteria, boolean showBadge,
                int userSentiment, boolean hidden, long lastAudiblyAlertedMs,
                boolean noisy, ArrayList<Notification.Action> smartActions,
                ArrayList<CharSequence> smartReplies, boolean canBubble,
                boolean visuallyInterruptive, boolean isConversation, ShortcutInfo shortcutInfo,
                int rankingAdjustment, boolean isBubble) {
            mKey = key;
            mRank = rank;
            mIsAmbient = importance < NotificationManager.IMPORTANCE_LOW;
            mMatchesInterruptionFilter = matchesInterruptionFilter;
            mVisibilityOverride = visibilityOverride;
            mSuppressedVisualEffects = suppressedVisualEffects;
            mImportance = importance;
            mImportanceExplanation = explanation;
            mOverrideGroupKey = overrideGroupKey;
            mChannel = channel;
            mOverridePeople = overridePeople;
            mSnoozeCriteria = snoozeCriteria;
            mShowBadge = showBadge;
            mUserSentiment = userSentiment;
            mHidden = hidden;
            mLastAudiblyAlertedMs = lastAudiblyAlertedMs;
            mNoisy = noisy;
            mSmartActions = smartActions;
            mSmartReplies = smartReplies;
            mCanBubble = canBubble;
            mVisuallyInterruptive = visuallyInterruptive;
            mIsConversation = isConversation;
            mShortcutInfo = shortcutInfo;
            mRankingAdjustment = rankingAdjustment;
            mIsBubble = isBubble;
        }

        /**
         * @hide
         */
        public @NonNull Ranking withAudiblyAlertedInfo(@Nullable Ranking previous) {
            if (previous != null && previous.mLastAudiblyAlertedMs > 0
                    && this.mLastAudiblyAlertedMs <= 0) {
                this.mLastAudiblyAlertedMs = previous.mLastAudiblyAlertedMs;
            }
            return this;
        }

        /**
         * @hide
         */
        public void populate(Ranking other) {
            populate(other.mKey,
                    other.mRank,
                    other.mMatchesInterruptionFilter,
                    other.mVisibilityOverride,
                    other.mSuppressedVisualEffects,
                    other.mImportance,
                    other.mImportanceExplanation,
                    other.mOverrideGroupKey,
                    other.mChannel,
                    other.mOverridePeople,
                    other.mSnoozeCriteria,
                    other.mShowBadge,
                    other.mUserSentiment,
                    other.mHidden,
                    other.mLastAudiblyAlertedMs,
                    other.mNoisy,
                    other.mSmartActions,
                    other.mSmartReplies,
                    other.mCanBubble,
                    other.mVisuallyInterruptive,
                    other.mIsConversation,
                    other.mShortcutInfo,
                    other.mRankingAdjustment,
                    other.mIsBubble);
        }

        /**
         * {@hide}
         */
        public static String importanceToString(int importance) {
            switch (importance) {
                case NotificationManager.IMPORTANCE_UNSPECIFIED:
                    return "UNSPECIFIED";
                case NotificationManager.IMPORTANCE_NONE:
                    return "NONE";
                case NotificationManager.IMPORTANCE_MIN:
                    return "MIN";
                case NotificationManager.IMPORTANCE_LOW:
                    return "LOW";
                case NotificationManager.IMPORTANCE_DEFAULT:
                    return "DEFAULT";
                case NotificationManager.IMPORTANCE_HIGH:
                case NotificationManager.IMPORTANCE_MAX:
                    return "HIGH";
                default:
                    return "UNKNOWN(" + String.valueOf(importance) + ")";
            }
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Ranking other = (Ranking) o;
            return Objects.equals(mKey, other.mKey)
                    && Objects.equals(mRank, other.mRank)
                    && Objects.equals(mMatchesInterruptionFilter, other.mMatchesInterruptionFilter)
                    && Objects.equals(mVisibilityOverride, other.mVisibilityOverride)
                    && Objects.equals(mSuppressedVisualEffects, other.mSuppressedVisualEffects)
                    && Objects.equals(mImportance, other.mImportance)
                    && Objects.equals(mImportanceExplanation, other.mImportanceExplanation)
                    && Objects.equals(mOverrideGroupKey, other.mOverrideGroupKey)
                    && Objects.equals(mChannel, other.mChannel)
                    && Objects.equals(mOverridePeople, other.mOverridePeople)
                    && Objects.equals(mSnoozeCriteria, other.mSnoozeCriteria)
                    && Objects.equals(mShowBadge, other.mShowBadge)
                    && Objects.equals(mUserSentiment, other.mUserSentiment)
                    && Objects.equals(mHidden, other.mHidden)
                    && Objects.equals(mLastAudiblyAlertedMs, other.mLastAudiblyAlertedMs)
                    && Objects.equals(mNoisy, other.mNoisy)
                    // Action.equals() doesn't exist so let's just compare list lengths
                    && ((mSmartActions == null ? 0 : mSmartActions.size())
                        == (other.mSmartActions == null ? 0 : other.mSmartActions.size()))
                    && Objects.equals(mSmartReplies, other.mSmartReplies)
                    && Objects.equals(mCanBubble, other.mCanBubble)
                    && Objects.equals(mVisuallyInterruptive, other.mVisuallyInterruptive)
                    && Objects.equals(mIsConversation, other.mIsConversation)
                    // Shortcutinfo doesn't have equals either; use id
                    &&  Objects.equals((mShortcutInfo == null ? 0 : mShortcutInfo.getId()),
                    (other.mShortcutInfo == null ? 0 : other.mShortcutInfo.getId()))
                    && Objects.equals(mRankingAdjustment, other.mRankingAdjustment)
                    && Objects.equals(mIsBubble, other.mIsBubble);
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
        private ArrayList<String> mOrderedKeys = new ArrayList<>();
        // Note: all String keys should be intern'd as pointers into mOrderedKeys
        private ArrayMap<String, Ranking> mRankings = new ArrayMap<>();

        /**
         * @hide
         */
        public RankingMap(Ranking[] rankings) {
            for (int i = 0; i < rankings.length; i++) {
                final String key = rankings[i].getKey();
                mOrderedKeys.add(key);
                mRankings.put(key, rankings[i]);
            }
        }

        // -- parcelable interface --

        private RankingMap(Parcel in) {
            final ClassLoader cl = getClass().getClassLoader();
            final int count = in.readInt();
            mOrderedKeys.ensureCapacity(count);
            mRankings.ensureCapacity(count);
            for (int i = 0; i < count; i++) {
                final Ranking r = new Ranking(in);
                final String key = r.getKey();
                mOrderedKeys.add(key);
                mRankings.put(key, r);
            }
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RankingMap other = (RankingMap) o;

            return mOrderedKeys.equals(other.mOrderedKeys)
                    && mRankings.equals(other.mRankings);

        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            final int count = mOrderedKeys.size();
            out.writeInt(count);
            for (int i = 0; i < count; i++) {
                mRankings.get(mOrderedKeys.get(i)).writeToParcel(out, flags);
            }
        }

        public static final @android.annotation.NonNull Creator<RankingMap> CREATOR = new Creator<RankingMap>() {
            @Override
            public RankingMap createFromParcel(Parcel source) {
                return new RankingMap(source);
            }

            @Override
            public RankingMap[] newArray(int size) {
                return new RankingMap[size];
            }
        };

        /**
         * Request the list of notification keys in their current ranking
         * order.
         *
         * @return An array of active notification keys, in their ranking order.
         */
        public String[] getOrderedKeys() {
            return mOrderedKeys.toArray(new String[0]);
        }

        /**
         * Populates outRanking with ranking information for the notification
         * with the given key.
         *
         * @return true if a valid key has been passed and outRanking has
         * been populated; false otherwise
         */
        public boolean getRanking(String key, Ranking outRanking) {
            if (mRankings.containsKey(key)) {
                outRanking.populate(mRankings.get(key));
                return true;
            }
            return false;
        }

        /**
         * Get a reference to the actual Ranking object corresponding to the key.
         * Used only by unit tests.
         *
         * @hide
         */
        @VisibleForTesting
        public Ranking getRawRankingObject(String key) {
            return mRankings.get(key);
        }
    }

    private final class MyHandler extends Handler {
        public static final int MSG_ON_NOTIFICATION_POSTED = 1;
        public static final int MSG_ON_NOTIFICATION_REMOVED = 2;
        public static final int MSG_ON_LISTENER_CONNECTED = 3;
        public static final int MSG_ON_NOTIFICATION_RANKING_UPDATE = 4;
        public static final int MSG_ON_LISTENER_HINTS_CHANGED = 5;
        public static final int MSG_ON_INTERRUPTION_FILTER_CHANGED = 6;
        public static final int MSG_ON_NOTIFICATION_CHANNEL_MODIFIED = 7;
        public static final int MSG_ON_NOTIFICATION_CHANNEL_GROUP_MODIFIED = 8;
        public static final int MSG_ON_STATUS_BAR_ICON_BEHAVIOR_CHANGED = 9;

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
                    int reason = (int) args.arg3;
                    NotificationStats stats = (NotificationStats) args.arg4;
                    args.recycle();
                    onNotificationRemoved(sbn, rankingMap, stats, reason);
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

                case MSG_ON_NOTIFICATION_CHANNEL_MODIFIED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    String pkgName = (String) args.arg1;
                    UserHandle user= (UserHandle) args.arg2;
                    NotificationChannel channel = (NotificationChannel) args.arg3;
                    int modificationType = (int) args.arg4;
                    onNotificationChannelModified(pkgName, user, channel, modificationType);
                } break;

                case MSG_ON_NOTIFICATION_CHANNEL_GROUP_MODIFIED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    String pkgName = (String) args.arg1;
                    UserHandle user = (UserHandle) args.arg2;
                    NotificationChannelGroup group = (NotificationChannelGroup) args.arg3;
                    int modificationType = (int) args.arg4;
                    onNotificationChannelGroupModified(pkgName, user, group, modificationType);
                } break;

                case MSG_ON_STATUS_BAR_ICON_BEHAVIOR_CHANGED: {
                    onSilentStatusBarIconsVisibilityChanged((Boolean) msg.obj);
                } break;
            }
        }
    }
}
