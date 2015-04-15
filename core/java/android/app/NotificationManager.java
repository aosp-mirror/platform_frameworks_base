/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.app.Notification.Builder;
import android.app.NotificationManager.Policy.Token;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.service.notification.IConditionListener;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenModeConfig;
import android.util.Log;

import java.util.Objects;
import java.util.List;

/**
 * Class to notify the user of events that happen.  This is how you tell
 * the user that something has happened in the background. {@more}
 *
 * Notifications can take different forms:
 * <ul>
 *      <li>A persistent icon that goes in the status bar and is accessible
 *          through the launcher, (when the user selects it, a designated Intent
 *          can be launched),</li>
 *      <li>Turning on or flashing LEDs on the device, or</li>
 *      <li>Alerting the user by flashing the backlight, playing a sound,
 *          or vibrating.</li>
 * </ul>
 *
 * <p>
 * Each of the notify methods takes an int id parameter and optionally a
 * {@link String} tag parameter, which may be {@code null}.  These parameters
 * are used to form a pair (tag, id), or ({@code null}, id) if tag is
 * unspecified.  This pair identifies this notification from your app to the
 * system, so that pair should be unique within your app.  If you call one
 * of the notify methods with a (tag, id) pair that is currently active and
 * a new set of notification parameters, it will be updated.  For example,
 * if you pass a new status bar icon, the old icon in the status bar will
 * be replaced with the new one.  This is also the same tag and id you pass
 * to the {@link #cancel(int)} or {@link #cancel(String, int)} method to clear
 * this notification.
 *
 * <p>
 * You do not instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService}.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For a guide to creating notifications, read the
 * <a href="{@docRoot}guide/topics/ui/notifiers/notifications.html">Status Bar Notifications</a>
 * developer guide.</p>
 * </div>
 *
 * @see android.app.Notification
 * @see android.content.Context#getSystemService
 */
public class NotificationManager
{
    private static String TAG = "NotificationManager";
    private static boolean localLOGV = false;

    /**
     * Intent that is broadcast when the state of {@link #getEffectsSuppressor()} changes.
     * This broadcast is only sent to registered receivers.
     *
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_EFFECTS_SUPPRESSOR_CHANGED
            = "android.os.action.ACTION_EFFECTS_SUPPRESSOR_CHANGED";

    /**
     * Intent that is broadcast when the state of getNotificationPolicy() changes.
     * This broadcast is only sent to registered receivers.
     */
    @SdkConstant(SdkConstant.SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_POLICY_CHANGED
            = "android.app.action.NOTIFICATION_POLICY_CHANGED";

    private static INotificationManager sService;

    /** @hide */
    static public INotificationManager getService()
    {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService("notification");
        sService = INotificationManager.Stub.asInterface(b);
        return sService;
    }

    /*package*/ NotificationManager(Context context, Handler handler)
    {
        mContext = context;
    }

    /** {@hide} */
    public static NotificationManager from(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Post a notification to be shown in the status bar. If a notification with
     * the same id has already been posted by your application and has not yet been canceled, it
     * will be replaced by the updated information.
     *
     * @param id An identifier for this notification unique within your
     *        application.
     * @param notification A {@link Notification} object describing what to show the user. Must not
     *        be null.
     */
    public void notify(int id, Notification notification)
    {
        notify(null, id, notification);
    }

    /**
     * Post a notification to be shown in the status bar. If a notification with
     * the same tag and id has already been posted by your application and has not yet been
     * canceled, it will be replaced by the updated information.
     *
     * @param tag A string identifier for this notification.  May be {@code null}.
     * @param id An identifier for this notification.  The pair (tag, id) must be unique
     *        within your application.
     * @param notification A {@link Notification} object describing what to
     *        show the user. Must not be null.
     */
    public void notify(String tag, int id, Notification notification)
    {
        int[] idOut = new int[1];
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (notification.sound != null) {
            notification.sound = notification.sound.getCanonicalUri();
            if (StrictMode.vmFileUriExposureEnabled()) {
                notification.sound.checkFileUriExposed("Notification.sound");
            }
        }
        if (localLOGV) Log.v(TAG, pkg + ": notify(" + id + ", " + notification + ")");
        Notification stripped = notification.clone();
        Builder.stripForDelivery(stripped);
        try {
            service.enqueueNotificationWithTag(pkg, mContext.getOpPackageName(), tag, id,
                    stripped, idOut, UserHandle.myUserId());
            if (id != idOut[0]) {
                Log.w(TAG, "notify: id corrupted: sent " + id + ", got back " + idOut[0]);
            }
        } catch (RemoteException e) {
        }
    }

    /**
     * @hide
     */
    public void notifyAsUser(String tag, int id, Notification notification, UserHandle user)
    {
        int[] idOut = new int[1];
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (notification.sound != null) {
            notification.sound = notification.sound.getCanonicalUri();
            if (StrictMode.vmFileUriExposureEnabled()) {
                notification.sound.checkFileUriExposed("Notification.sound");
            }
        }
        if (localLOGV) Log.v(TAG, pkg + ": notify(" + id + ", " + notification + ")");
        Notification stripped = notification.clone();
        Builder.stripForDelivery(stripped);
        try {
            service.enqueueNotificationWithTag(pkg, mContext.getOpPackageName(), tag, id,
                    stripped, idOut, user.getIdentifier());
            if (id != idOut[0]) {
                Log.w(TAG, "notify: id corrupted: sent " + id + ", got back " + idOut[0]);
            }
        } catch (RemoteException e) {
        }
    }

    /**
     * Cancel a previously shown notification.  If it's transient, the view
     * will be hidden.  If it's persistent, it will be removed from the status
     * bar.
     */
    public void cancel(int id)
    {
        cancel(null, id);
    }

    /**
     * Cancel a previously shown notification.  If it's transient, the view
     * will be hidden.  If it's persistent, it will be removed from the status
     * bar.
     */
    public void cancel(String tag, int id)
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (localLOGV) Log.v(TAG, pkg + ": cancel(" + id + ")");
        try {
            service.cancelNotificationWithTag(pkg, tag, id, UserHandle.myUserId());
        } catch (RemoteException e) {
        }
    }

    /**
     * @hide
     */
    public void cancelAsUser(String tag, int id, UserHandle user)
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (localLOGV) Log.v(TAG, pkg + ": cancel(" + id + ")");
        try {
            service.cancelNotificationWithTag(pkg, tag, id, user.getIdentifier());
        } catch (RemoteException e) {
        }
    }

    /**
     * Cancel all previously shown notifications. See {@link #cancel} for the
     * detailed behavior.
     */
    public void cancelAll()
    {
        INotificationManager service = getService();
        String pkg = mContext.getPackageName();
        if (localLOGV) Log.v(TAG, pkg + ": cancelAll()");
        try {
            service.cancelAllNotifications(pkg, UserHandle.myUserId());
        } catch (RemoteException e) {
        }
    }

    /**
     * @hide
     */
    public ComponentName getEffectsSuppressor() {
        INotificationManager service = getService();
        try {
            return service.getEffectsSuppressor();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * @hide
     */
    public boolean matchesCallFilter(Bundle extras) {
        INotificationManager service = getService();
        try {
            return service.matchesCallFilter(extras);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * @hide
     */
    public boolean isSystemConditionProviderEnabled(String path) {
        INotificationManager service = getService();
        try {
            return service.isSystemConditionProviderEnabled(path);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * @hide
     */
    public void setZenMode(int mode, Uri conditionId, String reason) {
        INotificationManager service = getService();
        try {
            service.setZenMode(mode, conditionId, reason);
        } catch (RemoteException e) {
        }
    }

    /**
     * @hide
     */
    public boolean setZenModeConfig(ZenModeConfig config, String reason) {
        INotificationManager service = getService();
        try {
            return service.setZenModeConfig(config, reason);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * @hide
     */
    public void requestZenModeConditions(IConditionListener listener, int relevance) {
        INotificationManager service = getService();
        try {
            service.requestZenModeConditions(listener, relevance);
        } catch (RemoteException e) {
        }
    }

    /**
     * @hide
     */
    public int getZenMode() {
        INotificationManager service = getService();
        try {
            return service.getZenMode();
        } catch (RemoteException e) {
        }
        return Global.ZEN_MODE_OFF;
    }

    /**
     * @hide
     */
    public ZenModeConfig getZenModeConfig() {
        INotificationManager service = getService();
        try {
            return service.getZenModeConfig();
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Requests a notification policy token for the calling package.
     *
     * @param callback required, used to receive the granted token or the deny signal.
     * @param handler The handler used when receiving the result.
     *                If null, the current thread is used.
     */
    public void requestNotificationPolicyToken(@NonNull final Policy.Token.RequestCallback callback,
            @Nullable Handler handler) {
        checkRequired("callback", callback);
        final Handler h = handler != null ? handler : new Handler();
        INotificationManager service = getService();
        try {
            service.requestNotificationPolicyToken(mContext.getOpPackageName(),
                    new INotificationManagerCallback.Stub() {
                @Override
                public void onPolicyToken(final Token token) throws RemoteException {
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            if (token != null) {
                                callback.onTokenGranted(token);
                            } else {
                                callback.onTokenDenied();
                            }
                        }
                    });
                }
            });
        } catch (RemoteException e) {
        }
    }

    /**
     * Checks a given notification policy token.
     *
     * Returns true if the token is still valid for managing policy.
     */
    public boolean isNotificationPolicyTokenValid(@NonNull Policy.Token token) {
        if (token == null) return false;
        INotificationManager service = getService();
        try {
            return service.isNotificationPolicyTokenValid(mContext.getOpPackageName(), token);
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * Gets the current notification policy.
     *
     * @param token A valid notification policy token is required to access the current policy.
     */
    public Policy getNotificationPolicy(@NonNull Policy.Token token) {
        checkRequired("token", token);
        INotificationManager service = getService();
        try {
            return service.getNotificationPolicy(token);
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Sets the current notification policy.
     *
     * @param token  A valid notification policy token is required to modify the current policy.
     * @param policy The new desired policy.
     */
    public void setNotificationPolicy(@NonNull Policy.Token token, @NonNull Policy policy) {
        checkRequired("token", token);
        checkRequired("policy", policy);
        INotificationManager service = getService();
        try {
            service.setNotificationPolicy(token, policy);
        } catch (RemoteException e) {
        }
    }

    private Context mContext;

    private static void checkRequired(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    /**
     * Notification policy configuration.  Represents user-preferences for notification
     * filtering and prioritization.
     */
    public static class Policy implements android.os.Parcelable {
        /** Reminder notifications are prioritized. */
        public static final int PRIORITY_CATEGORY_REMINDERS = 1 << 0;
        /** Event notifications are prioritized. */
        public static final int PRIORITY_CATEGORY_EVENTS = 1 << 1;
        /** Message notifications are prioritized. */
        public static final int PRIORITY_CATEGORY_MESSAGES = 1 << 2;
        /** Calls are prioritized. */
        public static final int PRIORITY_CATEGORY_CALLS = 1 << 3;
        /** Calls from repeat callers are prioritized. */
        public static final int PRIORITY_CATEGORY_REPEAT_CALLERS = 1 << 4;

        private static final int[] ALL_PRIORITY_CATEGORIES = {
            PRIORITY_CATEGORY_REMINDERS,
            PRIORITY_CATEGORY_EVENTS,
            PRIORITY_CATEGORY_MESSAGES,
            PRIORITY_CATEGORY_CALLS,
            PRIORITY_CATEGORY_REPEAT_CALLERS,
        };

        /** Any sender is prioritized. */
        public static final int PRIORITY_SENDERS_ANY = 0;
        /** Saved contacts are prioritized. */
        public static final int PRIORITY_SENDERS_CONTACTS = 1;
        /** Only starred contacts are prioritized. */
        public static final int PRIORITY_SENDERS_STARRED = 2;

        /** Notification categories to prioritize. Bitmask of PRIORITY_CATEGORY_* constants. */
        public final int priorityCategories;

        /** Notification senders to prioritize. One of:
         * PRIORITY_SENDERS_ANY, PRIORITY_SENDERS_CONTACTS, PRIORITY_SENDERS_STARRED */
        public final int prioritySenders;

        public Policy(int priorityCategories, int prioritySenders) {
            this.priorityCategories = priorityCategories;
            this.prioritySenders = prioritySenders;
        }

        /** @hide */
        public Policy(Parcel source) {
            this(source.readInt(), source.readInt());
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(priorityCategories);
            dest.writeInt(prioritySenders);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(priorityCategories, prioritySenders);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Policy)) return false;
            if (o == this) return true;
            final Policy other = (Policy) o;
            return other.priorityCategories == priorityCategories
                    && other.prioritySenders == prioritySenders;
        }

        @Override
        public String toString() {
            return "NotificationManager.Policy["
                    + "priorityCategories=" + priorityCategoriesToString(priorityCategories)
                    + ",prioritySenders=" + prioritySendersToString(prioritySenders)
                    + "]";
        }

        public static String priorityCategoriesToString(int priorityCategories) {
            if (priorityCategories == 0) return "";
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ALL_PRIORITY_CATEGORIES.length; i++) {
                final int priorityCategory = ALL_PRIORITY_CATEGORIES[i];
                if ((priorityCategories & priorityCategory) != 0) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(priorityCategoryToString(priorityCategory));
                }
                priorityCategories &= ~priorityCategory;
            }
            if (priorityCategories != 0) {
                if (sb.length() > 0) sb.append(',');
                sb.append("PRIORITY_CATEGORY_UNKNOWN_").append(priorityCategories);
            }
            return sb.toString();
        }

        private static String priorityCategoryToString(int priorityCategory) {
            switch (priorityCategory) {
                case PRIORITY_CATEGORY_REMINDERS: return "PRIORITY_CATEGORY_REMINDERS";
                case PRIORITY_CATEGORY_EVENTS: return "PRIORITY_CATEGORY_EVENTS";
                case PRIORITY_CATEGORY_MESSAGES: return "PRIORITY_CATEGORY_MESSAGES";
                case PRIORITY_CATEGORY_CALLS: return "PRIORITY_CATEGORY_CALLS";
                case PRIORITY_CATEGORY_REPEAT_CALLERS: return "PRIORITY_CATEGORY_REPEAT_CALLERS";
                default: return "PRIORITY_CATEGORY_UNKNOWN_" + priorityCategory;
            }
        }

        public static String prioritySendersToString(int prioritySenders) {
            switch (prioritySenders) {
                case PRIORITY_SENDERS_ANY: return "PRIORITY_SENDERS_ANY";
                case PRIORITY_SENDERS_CONTACTS: return "PRIORITY_SENDERS_CONTACTS";
                case PRIORITY_SENDERS_STARRED: return "PRIORITY_SENDERS_STARRED";
                default: return "PRIORITY_SENDERS_UNKNOWN_" + prioritySenders;
            }
        }

        public static final Parcelable.Creator<Policy> CREATOR = new Parcelable.Creator<Policy>() {
            @Override
            public Policy createFromParcel(Parcel in) {
                return new Policy(in);
            }

            @Override
            public Policy[] newArray(int size) {
                return new Policy[size];
            }
        };

        /**
         * Represents a client-specific token required to manage notification policy.
         */
        public static class Token implements Parcelable {
            private final IBinder mBinder;

            /** @hide */
            public Token(IBinder binder) {
                if (binder == null) throw new IllegalArgumentException("Binder required for token");
                mBinder = binder;
            }

            @Override
            public int describeContents() {
                return 0;
            }

            @Override
            public int hashCode() {
                return Objects.hash(mBinder);
            }

            @Override
            public boolean equals(Object o) {
                if (!(o instanceof Token)) return false;
                if (o == this) return true;
                final Token other = (Token) o;
                return Objects.equals(other.mBinder, mBinder);
            }

            @Override
            public String toString() {
                return String.format("NotificationManager.Token[0x%08x]",
                        System.identityHashCode(mBinder));
            }

            @Override
            public void writeToParcel(Parcel dest, int flags) {
                dest.writeStrongBinder(mBinder);
            }

            public static final Parcelable.Creator<Token> CREATOR
                    = new Parcelable.Creator<Token>() {
                @Override
                public Token createFromParcel(Parcel in) {
                    return new Token(in.readStrongBinder());
                }

                @Override
                public Token[] newArray(int size) {
                    return new Token[size];
                }
            };

            /** Callback for receiving the result of a token request. */
            public static abstract class RequestCallback {
                /**
                 * Received if the request was granted for this package.
                 *
                 * @param token can be used to manage notification policy.
                 */
                public abstract void onTokenGranted(Policy.Token token);

                /**
                 * Received if the request was denied for this package.
                 */
                public abstract void onTokenDenied();
            }
        }
    }

    /**
     * Recover a list of active notifications: ones that have been posted by the calling app that
     * have not yet been dismissed by the user or {@link #cancel(String, int)}ed by the app.
     *
     * Each notification is embedded in a {@link StatusBarNotification} object, including the
     * original <code>tag</code> and <code>id</code> supplied to
     * {@link #notify(String, int, Notification) notify()}
     * (via {@link StatusBarNotification#getTag() getTag()} and
     * {@link StatusBarNotification#getId() getId()}) as well as a copy of the original
     * {@link Notification} object (via {@link StatusBarNotification#getNotification()}).
     *
     * @return An array of {@link StatusBarNotification}.
     */
    public StatusBarNotification[] getActiveNotifications() {
        final INotificationManager service = getService();
        final String pkg = mContext.getPackageName();
        try {
            final ParceledListSlice<StatusBarNotification> parceledList
                    = service.getAppActiveNotifications(pkg, UserHandle.myUserId());
            final List<StatusBarNotification> list = parceledList.getList();
            return list.toArray(new StatusBarNotification[list.size()]);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to talk to notification manager. Woe!", e);
        }
        return new StatusBarNotification[0];
    }
}
