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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import com.android.internal.os.SomeArgs;
import java.lang.annotation.Retention;
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
 * @hide
 */
@SystemApi
public abstract class NotificationAssistantService extends NotificationListenerService {
    private static final String TAG = "NotificationAssistants";

    /** @hide */
    @Retention(SOURCE)
    @IntDef({SOURCE_FROM_APP, SOURCE_FROM_ASSISTANT})
    public @interface Source {}

    /**
     * To indicate an adjustment is from an app.
     */
    public static final int SOURCE_FROM_APP = 0;
    /**
     * To indicate an adjustment is from a {@link NotificationAssistantService}.
     */
    public static final int SOURCE_FROM_ASSISTANT = 1;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE
            = "android.service.notification.NotificationAssistantService";

    /**
     * Activity Action: Show notification assistant detail setting page in NAS app.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_NOTIFICATION_ASSISTANT_DETAIL_SETTINGS =
            "android.service.notification.action.NOTIFICATION_ASSISTANT_DETAIL_SETTINGS";


    /**
     * Data type: int, the feedback rating score provided by user. The score can be any integer
     *            value depends on the experimental and feedback UX design.
     */
    public static final String FEEDBACK_RATING = "feedback.rating";

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
    public final @NonNull IBinder onBind(@Nullable Intent intent) {
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
    abstract public void onNotificationSnoozedUntilContext(@NonNull StatusBarNotification sbn,
            @NonNull String snoozeCriterionId);

    /**
     * A notification was posted by an app. Called before post.
     *
     * <p>Note: this method is only called if you don't override
     * {@link #onNotificationEnqueued(StatusBarNotification, NotificationChannel)} or
     * {@link #onNotificationEnqueued(StatusBarNotification, NotificationChannel, RankingMap)}.</p>
     *
     * @param sbn the new notification
     * @return an adjustment or null to take no action, within 200ms.
     */
    abstract public @Nullable Adjustment onNotificationEnqueued(@NonNull StatusBarNotification sbn);

    /**
     * A notification was posted by an app. Called before post.
     *
     * <p>Note: this method is only called if you don't override
     * {@link #onNotificationEnqueued(StatusBarNotification, NotificationChannel, RankingMap)}.</p>
     *
     * @param sbn the new notification
     * @param channel the channel the notification was posted to
     * @return an adjustment or null to take no action, within 200ms.
     */
    public @Nullable Adjustment onNotificationEnqueued(@NonNull StatusBarNotification sbn,
            @NonNull NotificationChannel channel) {
        return onNotificationEnqueued(sbn);
    }

    /**
     * A notification was posted by an app. Called before post.
     *
     * @param sbn the new notification
     * @param channel the channel the notification was posted to
     * @param rankingMap The current ranking map that can be used to retrieve ranking information
     *                   for active notifications.
     * @return an adjustment or null to take no action, within 200ms.
     */
    public @Nullable Adjustment onNotificationEnqueued(@NonNull StatusBarNotification sbn,
            @NonNull NotificationChannel channel, @NonNull RankingMap rankingMap) {
        return onNotificationEnqueued(sbn, channel);
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
    public void onNotificationRemoved(@NonNull StatusBarNotification sbn,
            @NonNull RankingMap rankingMap,
            @NonNull NotificationStats stats, int reason) {
        onNotificationRemoved(sbn, rankingMap, reason);
    }

    /**
     * Implement this to know when a user has seen notifications, as triggered by
     * {@link #setNotificationsShown(String[])}.
     */
    public void onNotificationsSeen(@NonNull List<String> keys) {

    }

    /**
     * Implement this to know when the notification panel is revealed
     *
     * @param items Number of notifications on the panel at time of opening
     */
    public void onPanelRevealed(int items) {

    }

    /**
     * Implement this to know when the notification panel is hidden
     */
    public void onPanelHidden() {

    }

    /**
     * Implement this to know when a notification becomes visible or hidden from the user.
     *
     * @param key the notification key
     * @param isVisible whether the notification is visible.
     */
    public void onNotificationVisibilityChanged(@NonNull String key, boolean isVisible) {

    }

    /**
     * Implement this to know when a notification change (expanded / collapsed) is visible to user.
     *
     * @param key the notification key
     * @param isUserAction whether the expanded change is caused by user action.
     * @param isExpanded whether the notification is expanded.
     */
    public void onNotificationExpansionChanged(
            @NonNull String key, boolean isUserAction, boolean isExpanded) {}

    /**
     * Implement this to know when a direct reply is sent from a notification.
     * @param key the notification key
     */
    public void onNotificationDirectReplied(@NonNull String key) {}

    /**
     * Implement this to know when a suggested reply is sent.
     * @param key the notification key
     * @param reply the reply that is just sent
     * @param source the source that provided the reply, e.g. SOURCE_FROM_APP
     */
    public void onSuggestedReplySent(@NonNull String key, @NonNull CharSequence reply,
            @Source int source) {
    }

    /**
     * Implement this to know when an action is clicked.
     * @param key the notification key
     * @param action the action that is just clicked
     * @param source the source that provided the action, e.g. SOURCE_FROM_APP
     */
    public void onActionInvoked(@NonNull String key, @NonNull Notification.Action action,
            @Source int source) {
    }

    /**
     * Implement this to know when a notification is clicked by user.
     * @param key the notification key
     */
    public void onNotificationClicked(@NonNull String key) {
    }

    /**
     * Implement this to know when a user has changed which features of
     * their notifications the assistant can modify.
     * <p> Query {@link NotificationManager#getAllowedAssistantAdjustments()} to see what
     * {@link Adjustment adjustments} you are currently allowed to make.</p>
     *
     * @deprecated changing allowed adjustments is no longer supported.
     */
    @Deprecated
    public void onAllowedAdjustmentsChanged() {
    }

    /**
     * Implement this to know when user provides a feedback.
     * @param key the notification key
     * @param rankingMap The current ranking map that can be used to retrieve ranking information
     *                   for active notifications.
     * @param feedback the received feedback, such as {@link #FEEDBACK_RATING rating score}
     */
    public void onNotificationFeedbackReceived(@NonNull String key, @NonNull RankingMap rankingMap,
            @NonNull Bundle feedback) {
    }

    /**
     * Updates a notification.  N.B. this won’t cause
     * an existing notification to alert, but might allow a future update to
     * this notification to alert.
     *
     * @param adjustment the adjustment with an explanation
     */
    public final void adjustNotification(@NonNull Adjustment adjustment) {
        if (!isBound()) return;
        try {
            setAdjustmentIssuer(adjustment);
            getNotificationInterface().applyEnqueuedAdjustmentFromAssistant(mWrapper, adjustment);
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
    public final void adjustNotifications(@NonNull List<Adjustment> adjustments) {
        if (!isBound()) return;
        try {
            for (Adjustment adjustment : adjustments) {
                setAdjustmentIssuer(adjustment);
            }
            getNotificationInterface().applyAdjustmentsFromAssistant(mWrapper, adjustments);
        } catch (android.os.RemoteException ex) {
            Log.v(TAG, "Unable to contact notification manager", ex);
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Inform the notification manager about un-snoozing a specific notification.
     * <p>
     * This should only be used for notifications snoozed because of a contextual snooze suggestion
     * you provided via {@link Adjustment#KEY_SNOOZE_CRITERIA}. Once un-snoozed, you will get a
     * {@link #onNotificationPosted(StatusBarNotification, RankingMap)} callback for the
     * notification.
     * @param key The key of the notification to snooze
     */
    public final void unsnoozeNotification(@NonNull String key) {
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
                NotificationChannel channel, NotificationRankingUpdate update) {
            StatusBarNotification sbn;
            try {
                sbn = sbnHolder.get();
            } catch (RemoteException e) {
                Log.w(TAG, "onNotificationEnqueued: Error receiving StatusBarNotification", e);
                return;
            }
            if (sbn == null) {
                Log.w(TAG, "onNotificationEnqueuedWithChannel: "
                        + "Error receiving StatusBarNotification");
                return;
            }

            applyUpdateLocked(update);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = sbn;
            args.arg2 = channel;
            args.arg3 = getCurrentRanking();
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
            if (sbn == null) {
                Log.w(TAG, "onNotificationSnoozed: Error receiving StatusBarNotification");
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

        @Override
        public void onPanelRevealed(int items) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = items;
            mHandler.obtainMessage(MyHandler.MSG_ON_PANEL_REVEALED,
                    args).sendToTarget();
        }

        @Override
        public void onPanelHidden() {
            SomeArgs args = SomeArgs.obtain();
            mHandler.obtainMessage(MyHandler.MSG_ON_PANEL_HIDDEN,
                    args).sendToTarget();
        }

        @Override
        public void onNotificationVisibilityChanged(String key, boolean isVisible) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = key;
            args.argi1 = isVisible ? 1 : 0;
            mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_VISIBILITY_CHANGED,
                    args).sendToTarget();
        }

        @Override
        public void onNotificationExpansionChanged(String key, boolean isUserAction,
                boolean isExpanded) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = key;
            args.argi1 = isUserAction ? 1 : 0;
            args.argi2 = isExpanded ? 1 : 0;
            mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_EXPANSION_CHANGED, args)
                    .sendToTarget();
        }

        @Override
        public void onNotificationDirectReply(String key) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = key;
            mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_DIRECT_REPLY_SENT, args)
                    .sendToTarget();
        }

        @Override
        public void onSuggestedReplySent(String key, CharSequence reply, int source) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = key;
            args.arg2 = reply;
            args.argi2 = source;
            mHandler.obtainMessage(MyHandler.MSG_ON_SUGGESTED_REPLY_SENT, args).sendToTarget();
        }

        @Override
        public void onActionClicked(String key, Notification.Action action, int source) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = key;
            args.arg2 = action;
            args.argi2 = source;
            mHandler.obtainMessage(MyHandler.MSG_ON_ACTION_INVOKED, args).sendToTarget();
        }

        @Override
        public void onNotificationClicked(String key) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = key;
            mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_CLICKED, args).sendToTarget();
        }

        @Override
        public void onAllowedAdjustmentsChanged() {
            mHandler.obtainMessage(MyHandler.MSG_ON_ALLOWED_ADJUSTMENTS_CHANGED).sendToTarget();
        }

        @Override
        public void onNotificationFeedbackReceived(String key, NotificationRankingUpdate update,
                Bundle feedback) {
            applyUpdateLocked(update);
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = key;
            args.arg2 = getCurrentRanking();
            args.arg3 = feedback;
            mHandler.obtainMessage(MyHandler.MSG_ON_NOTIFICATION_FEEDBACK_RECEIVED,
                    args).sendToTarget();
        }
    }

    private void setAdjustmentIssuer(@Nullable Adjustment adjustment) {
        if (adjustment != null) {
            adjustment.setIssuer(getOpPackageName() + "/" + getClass().getName());
        }
    }

    private final class MyHandler extends Handler {
        public static final int MSG_ON_NOTIFICATION_ENQUEUED = 1;
        public static final int MSG_ON_NOTIFICATION_SNOOZED = 2;
        public static final int MSG_ON_NOTIFICATIONS_SEEN = 3;
        public static final int MSG_ON_NOTIFICATION_EXPANSION_CHANGED = 4;
        public static final int MSG_ON_NOTIFICATION_DIRECT_REPLY_SENT = 5;
        public static final int MSG_ON_SUGGESTED_REPLY_SENT = 6;
        public static final int MSG_ON_ACTION_INVOKED = 7;
        public static final int MSG_ON_ALLOWED_ADJUSTMENTS_CHANGED = 8;
        public static final int MSG_ON_PANEL_REVEALED = 9;
        public static final int MSG_ON_PANEL_HIDDEN = 10;
        public static final int MSG_ON_NOTIFICATION_VISIBILITY_CHANGED = 11;
        public static final int MSG_ON_NOTIFICATION_CLICKED = 12;
        public static final int MSG_ON_NOTIFICATION_FEEDBACK_RECEIVED = 13;

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
                    RankingMap ranking = (RankingMap) args.arg3;
                    args.recycle();
                    Adjustment adjustment = onNotificationEnqueued(sbn, channel, ranking);
                    setAdjustmentIssuer(adjustment);
                    if (adjustment != null) {
                        if (!isBound()) {
                            Log.w(TAG, "MSG_ON_NOTIFICATION_ENQUEUED: service not bound, skip.");
                            return;
                        }
                        try {
                            getNotificationInterface().applyEnqueuedAdjustmentFromAssistant(
                                    mWrapper, adjustment);
                        } catch (android.os.RemoteException ex) {
                            Log.v(TAG, "Unable to contact notification manager", ex);
                            throw ex.rethrowFromSystemServer();
                        } catch (SecurityException e) {
                            // app cannot catch and recover from this, so do on their behalf
                            Log.w(TAG, "Enqueue adjustment failed; no longer connected", e);
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
                case MSG_ON_NOTIFICATION_EXPANSION_CHANGED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    String key = (String) args.arg1;
                    boolean isUserAction = args.argi1 == 1;
                    boolean isExpanded = args.argi2 == 1;
                    args.recycle();
                    onNotificationExpansionChanged(key, isUserAction, isExpanded);
                    break;
                }
                case MSG_ON_NOTIFICATION_DIRECT_REPLY_SENT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    String key = (String) args.arg1;
                    args.recycle();
                    onNotificationDirectReplied(key);
                    break;
                }
                case MSG_ON_SUGGESTED_REPLY_SENT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    String key = (String) args.arg1;
                    CharSequence reply = (CharSequence) args.arg2;
                    int source = args.argi2;
                    args.recycle();
                    onSuggestedReplySent(key, reply, source);
                    break;
                }
                case MSG_ON_ACTION_INVOKED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    String key = (String) args.arg1;
                    Notification.Action action = (Notification.Action) args.arg2;
                    int source = args.argi2;
                    args.recycle();
                    onActionInvoked(key, action, source);
                    break;
                }
                case MSG_ON_ALLOWED_ADJUSTMENTS_CHANGED: {
                    onAllowedAdjustmentsChanged();
                    break;
                }
                case MSG_ON_PANEL_REVEALED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    int items = args.argi1;
                    args.recycle();
                    onPanelRevealed(items);
                    break;
                }
                case MSG_ON_PANEL_HIDDEN: {
                    onPanelHidden();
                    break;
                }
                case MSG_ON_NOTIFICATION_VISIBILITY_CHANGED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    String key = (String) args.arg1;
                    boolean isVisible = args.argi1 == 1;
                    args.recycle();
                    onNotificationVisibilityChanged(key, isVisible);
                    break;
                }
                case MSG_ON_NOTIFICATION_CLICKED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    String key = (String) args.arg1;
                    args.recycle();
                    onNotificationClicked(key);
                    break;
                }
                case MSG_ON_NOTIFICATION_FEEDBACK_RECEIVED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    String key = (String) args.arg1;
                    RankingMap ranking = (RankingMap) args.arg2;
                    Bundle feedback = (Bundle) args.arg3;
                    args.recycle();
                    onNotificationFeedbackReceived(key, ranking, feedback);
                    break;
                }
            }
        }
    }
}
