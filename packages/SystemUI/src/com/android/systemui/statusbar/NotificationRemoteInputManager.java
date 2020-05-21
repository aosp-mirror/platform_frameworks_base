/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.RemoteInputHistoryItem;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.dagger.StatusBarDependenciesModule;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry.EditedSuggestionInfo;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.RemoteInputUriController;
import com.android.systemui.statusbar.policy.RemoteInputView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

import dagger.Lazy;

/**
 * Class for handling remote input state over a set of notifications. This class handles things
 * like keeping notifications temporarily that were cancelled as a response to a remote input
 * interaction, keeping track of notifications to remove when NotificationPresenter is collapsed,
 * and handling clicks on remote views.
 */
public class NotificationRemoteInputManager implements Dumpable {
    public static final boolean ENABLE_REMOTE_INPUT =
            SystemProperties.getBoolean("debug.enable_remote_input", true);
    public static boolean FORCE_REMOTE_INPUT_HISTORY =
            SystemProperties.getBoolean("debug.force_remoteinput_history", true);
    private static final boolean DEBUG = false;
    private static final String TAG = "NotifRemoteInputManager";

    /**
     * How long to wait before auto-dismissing a notification that was kept for remote input, and
     * has now sent a remote input. We auto-dismiss, because the app may not see a reason to cancel
     * these given that they technically don't exist anymore. We wait a bit in case the app issues
     * an update.
     */
    private static final int REMOTE_INPUT_KEPT_ENTRY_AUTO_CANCEL_DELAY = 200;

    /**
     * Notifications that are already removed but are kept around because we want to show the
     * remote input history. See {@link RemoteInputHistoryExtender} and
     * {@link SmartReplyHistoryExtender}.
     */
    protected final ArraySet<String> mKeysKeptForRemoteInputHistory = new ArraySet<>();

    /**
     * Notifications that are already removed but are kept around because the remote input is
     * actively being used (i.e. user is typing in it).  See {@link RemoteInputActiveExtender}.
     */
    protected final ArraySet<NotificationEntry> mEntriesKeptForRemoteInputActive =
            new ArraySet<>();

    // Dependencies:
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final SmartReplyController mSmartReplyController;
    private final NotificationEntryManager mEntryManager;
    private final Handler mMainHandler;
    private final ActionClickLogger mLogger;

    private final Lazy<StatusBar> mStatusBarLazy;

    protected final Context mContext;
    private final UserManager mUserManager;
    private final KeyguardManager mKeyguardManager;
    private final StatusBarStateController mStatusBarStateController;
    private final RemoteInputUriController mRemoteInputUriController;
    private final NotificationClickNotifier mClickNotifier;

    protected RemoteInputController mRemoteInputController;
    protected NotificationLifetimeExtender.NotificationSafeToRemoveCallback
            mNotificationLifetimeFinishedCallback;
    protected IStatusBarService mBarService;
    protected Callback mCallback;
    protected final ArrayList<NotificationLifetimeExtender> mLifetimeExtenders = new ArrayList<>();

    private final RemoteViews.OnClickHandler mOnClickHandler = new RemoteViews.OnClickHandler() {

        @Override
        public boolean onClickHandler(
                View view, PendingIntent pendingIntent, RemoteViews.RemoteResponse response) {
            mStatusBarLazy.get().wakeUpIfDozing(SystemClock.uptimeMillis(), view,
                    "NOTIFICATION_CLICK");

            final NotificationEntry entry = getNotificationForParent(view.getParent());
            mLogger.logInitialClick(entry, pendingIntent);

            if (handleRemoteInput(view, pendingIntent)) {
                mLogger.logRemoteInputWasHandled(entry);
                return true;
            }

            if (DEBUG) {
                Log.v(TAG, "Notification click handler invoked for intent: " + pendingIntent);
            }
            logActionClick(view, entry, pendingIntent);
            // The intent we are sending is for the application, which
            // won't have permission to immediately start an activity after
            // the user switches to home.  We know it is safe to do at this
            // point, so make sure new activity switches are now allowed.
            try {
                ActivityManager.getService().resumeAppSwitches();
            } catch (RemoteException e) {
            }
            return mCallback.handleRemoteViewClick(view, pendingIntent, () -> {
                Pair<Intent, ActivityOptions> options = response.getLaunchOptions(view);
                options.second.setLaunchWindowingMode(
                        WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY);
                mLogger.logStartingIntentWithDefaultHandler(entry, pendingIntent);
                return RemoteViews.startPendingIntent(view, pendingIntent, options);
            });
        }

        private void logActionClick(
                View view,
                NotificationEntry entry,
                PendingIntent actionIntent) {
            Integer actionIndex = (Integer)
                    view.getTag(com.android.internal.R.id.notification_action_index_tag);
            if (actionIndex == null) {
                // Custom action button, not logging.
                return;
            }
            ViewParent parent = view.getParent();
            StatusBarNotification statusBarNotification = entry.getSbn();
            if (statusBarNotification == null) {
                Log.w(TAG, "Couldn't determine notification for click.");
                return;
            }
            String key = statusBarNotification.getKey();
            int buttonIndex = -1;
            // If this is a default template, determine the index of the button.
            if (view.getId() == com.android.internal.R.id.action0 &&
                    parent != null && parent instanceof ViewGroup) {
                ViewGroup actionGroup = (ViewGroup) parent;
                buttonIndex = actionGroup.indexOfChild(view);
            }
            final int count = mEntryManager.getActiveNotificationsCount();
            final int rank = mEntryManager
                    .getActiveNotificationUnfiltered(key).getRanking().getRank();

            // Notification may be updated before this function is executed, and thus play safe
            // here and verify that the action object is still the one that where the click happens.
            Notification.Action[] actions = statusBarNotification.getNotification().actions;
            if (actions == null || actionIndex >= actions.length) {
                Log.w(TAG, "statusBarNotification.getNotification().actions is null or invalid");
                return;
            }
            final Notification.Action action =
                    statusBarNotification.getNotification().actions[actionIndex];
            if (!Objects.equals(action.actionIntent, actionIntent)) {
                Log.w(TAG, "actionIntent does not match");
                return;
            }
            NotificationVisibility.NotificationLocation location =
                    NotificationLogger.getNotificationLocation(
                            mEntryManager.getActiveNotificationUnfiltered(key));
            final NotificationVisibility nv =
                    NotificationVisibility.obtain(key, rank, count, true, location);
            mClickNotifier.onNotificationActionClick(key, buttonIndex, action, nv, false);
        }

        private NotificationEntry getNotificationForParent(ViewParent parent) {
            while (parent != null) {
                if (parent instanceof ExpandableNotificationRow) {
                    return ((ExpandableNotificationRow) parent).getEntry();
                }
                parent = parent.getParent();
            }
            return null;
        }

        private boolean handleRemoteInput(View view, PendingIntent pendingIntent) {
            if (mCallback.shouldHandleRemoteInput(view, pendingIntent)) {
                return true;
            }

            Object tag = view.getTag(com.android.internal.R.id.remote_input_tag);
            RemoteInput[] inputs = null;
            if (tag instanceof RemoteInput[]) {
                inputs = (RemoteInput[]) tag;
            }

            if (inputs == null) {
                return false;
            }

            RemoteInput input = null;

            for (RemoteInput i : inputs) {
                if (i.getAllowFreeFormInput()) {
                    input = i;
                }
            }

            if (input == null) {
                return false;
            }

            return activateRemoteInput(view, inputs, input, pendingIntent,
                    null /* editedSuggestionInfo */);
        }
    };

    /**
     * Injected constructor. See {@link StatusBarDependenciesModule}.
     */
    public NotificationRemoteInputManager(
            Context context,
            NotificationLockscreenUserManager lockscreenUserManager,
            SmartReplyController smartReplyController,
            NotificationEntryManager notificationEntryManager,
            Lazy<StatusBar> statusBarLazy,
            StatusBarStateController statusBarStateController,
            @Main Handler mainHandler,
            RemoteInputUriController remoteInputUriController,
            NotificationClickNotifier clickNotifier,
            ActionClickLogger logger) {
        mContext = context;
        mLockscreenUserManager = lockscreenUserManager;
        mSmartReplyController = smartReplyController;
        mEntryManager = notificationEntryManager;
        mStatusBarLazy = statusBarLazy;
        mMainHandler = mainHandler;
        mLogger = logger;
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        addLifetimeExtenders();
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mStatusBarStateController = statusBarStateController;
        mRemoteInputUriController = remoteInputUriController;
        mClickNotifier = clickNotifier;

        notificationEntryManager.addNotificationEntryListener(new NotificationEntryListener() {
            @Override
            public void onPreEntryUpdated(NotificationEntry entry) {
                // Mark smart replies as sent whenever a notification is updated - otherwise the
                // smart replies are never marked as sent.
                mSmartReplyController.stopSending(entry);
            }

            @Override
            public void onEntryRemoved(
                    @Nullable NotificationEntry entry,
                    NotificationVisibility visibility,
                    boolean removedByUser,
                    int reason) {
                // We're removing the notification, the smart controller can forget about it.
                mSmartReplyController.stopSending(entry);

                if (removedByUser && entry != null) {
                    onPerformRemoveNotification(entry, entry.getKey());
                }
            }
        });
    }

    /** Initializes this component with the provided dependencies. */
    public void setUpWithCallback(Callback callback, RemoteInputController.Delegate delegate) {
        mCallback = callback;
        mRemoteInputController = new RemoteInputController(delegate, mRemoteInputUriController);
        mRemoteInputController.addCallback(new RemoteInputController.Callback() {
            @Override
            public void onRemoteInputSent(NotificationEntry entry) {
                if (FORCE_REMOTE_INPUT_HISTORY
                        && isNotificationKeptForRemoteInputHistory(entry.getKey())) {
                    mNotificationLifetimeFinishedCallback.onSafeToRemove(entry.getKey());
                } else if (mEntriesKeptForRemoteInputActive.contains(entry)) {
                    // We're currently holding onto this notification, but from the apps point of
                    // view it is already canceled, so we'll need to cancel it on the apps behalf
                    // after sending - unless the app posts an update in the mean time, so wait a
                    // bit.
                    mMainHandler.postDelayed(() -> {
                        if (mEntriesKeptForRemoteInputActive.remove(entry)) {
                            mNotificationLifetimeFinishedCallback.onSafeToRemove(entry.getKey());
                        }
                    }, REMOTE_INPUT_KEPT_ENTRY_AUTO_CANCEL_DELAY);
                }
                try {
                    mBarService.onNotificationDirectReplied(entry.getSbn().getKey());
                    if (entry.editedSuggestionInfo != null) {
                        boolean modifiedBeforeSending =
                                !TextUtils.equals(entry.remoteInputText,
                                        entry.editedSuggestionInfo.originalText);
                        mBarService.onNotificationSmartReplySent(
                                entry.getSbn().getKey(),
                                entry.editedSuggestionInfo.index,
                                entry.editedSuggestionInfo.originalText,
                                NotificationLogger
                                        .getNotificationLocation(entry)
                                        .toMetricsEventEnum(),
                                modifiedBeforeSending);
                    }
                } catch (RemoteException e) {
                    // Nothing to do, system going down
                }
            }
        });
        mSmartReplyController.setCallback((entry, reply) -> {
            StatusBarNotification newSbn =
                    rebuildNotificationWithRemoteInput(entry, reply, true /* showSpinner */,
                            null /* mimeType */, null /* uri */);
            mEntryManager.updateNotification(newSbn, null /* ranking */);
        });
    }

    /**
     * Activates a given {@link RemoteInput}
     *
     * @param view The view of the action button or suggestion chip that was tapped.
     * @param inputs The remote inputs that need to be sent to the app.
     * @param input The remote input that needs to be activated.
     * @param pendingIntent The pending intent to be sent to the app.
     * @param editedSuggestionInfo The smart reply that should be inserted in the remote input, or
     *         {@code null} if the user is not editing a smart reply.
     * @return Whether the {@link RemoteInput} was activated.
     */
    public boolean activateRemoteInput(View view, RemoteInput[] inputs, RemoteInput input,
            PendingIntent pendingIntent, @Nullable EditedSuggestionInfo editedSuggestionInfo) {

        ViewParent p = view.getParent();
        RemoteInputView riv = null;
        ExpandableNotificationRow row = null;
        while (p != null) {
            if (p instanceof View) {
                View pv = (View) p;
                if (pv.isRootNamespace()) {
                    riv = findRemoteInputView(pv);
                    row = (ExpandableNotificationRow) pv.getTag(R.id.row_tag_for_content_view);
                    break;
                }
            }
            p = p.getParent();
        }

        if (row == null) {
            return false;
        }

        row.setUserExpanded(true);

        if (!mLockscreenUserManager.shouldAllowLockscreenRemoteInput()) {
            final int userId = pendingIntent.getCreatorUserHandle().getIdentifier();

            final boolean isLockedManagedProfile =
                    mUserManager.getUserInfo(userId).isManagedProfile()
                    && mKeyguardManager.isDeviceLocked(userId);

            final boolean isParentUserLocked;
            if (isLockedManagedProfile) {
                final UserInfo profileParent = mUserManager.getProfileParent(userId);
                isParentUserLocked = (profileParent != null)
                        && mKeyguardManager.isDeviceLocked(profileParent.id);
            } else {
                isParentUserLocked = false;
            }

            if (mLockscreenUserManager.isLockscreenPublicMode(userId)
                    || mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                // If the parent user is no longer locked, and the user to which the remote input
                // is destined is a locked, managed profile, then onLockedWorkRemoteInput should be
                // called to unlock it.
                if (isLockedManagedProfile && !isParentUserLocked) {
                    mCallback.onLockedWorkRemoteInput(userId, row, view);
                } else {
                    // Even if we don't have security we should go through this flow, otherwise
                    // we won't go to the shade.
                    mCallback.onLockedRemoteInput(row, view);
                }
                return true;
            }
            if (isLockedManagedProfile) {
                mCallback.onLockedWorkRemoteInput(userId, row, view);
                return true;
            }
        }

        if (riv != null && !riv.isAttachedToWindow()) {
            // the remoteInput isn't attached to the window anymore :/ Let's focus on the expanded
            // one instead if it's available
            riv = null;
        }
        if (riv == null) {
            riv = findRemoteInputView(row.getPrivateLayout().getExpandedChild());
            if (riv == null) {
                return false;
            }
        }
        if (riv == row.getPrivateLayout().getExpandedRemoteInput()
                && !row.getPrivateLayout().getExpandedChild().isShown()) {
            // The expanded layout is selected, but it's not shown yet, let's wait on it to
            // show before we do the animation.
            mCallback.onMakeExpandedVisibleForRemoteInput(row, view);
            return true;
        }

        if (!riv.isAttachedToWindow()) {
            // if we still didn't find a view that is attached, let's abort.
            return false;
        }
        int width = view.getWidth();
        if (view instanceof TextView) {
            // Center the reveal on the text which might be off-center from the TextView
            TextView tv = (TextView) view;
            if (tv.getLayout() != null) {
                int innerWidth = (int) tv.getLayout().getLineWidth(0);
                innerWidth += tv.getCompoundPaddingLeft() + tv.getCompoundPaddingRight();
                width = Math.min(width, innerWidth);
            }
        }
        int cx = view.getLeft() + width / 2;
        int cy = view.getTop() + view.getHeight() / 2;
        int w = riv.getWidth();
        int h = riv.getHeight();
        int r = Math.max(
                Math.max(cx + cy, cx + (h - cy)),
                Math.max((w - cx) + cy, (w - cx) + (h - cy)));

        riv.setRevealParameters(cx, cy, r);
        riv.setPendingIntent(pendingIntent);
        riv.setRemoteInput(inputs, input, editedSuggestionInfo);
        riv.focusAnimated();

        return true;
    }

    private RemoteInputView findRemoteInputView(View v) {
        if (v == null) {
            return null;
        }
        return (RemoteInputView) v.findViewWithTag(RemoteInputView.VIEW_TAG);
    }

    /**
     * Adds all the notification lifetime extenders. Each extender represents a reason for the
     * NotificationRemoteInputManager to keep a notification lifetime extended.
     */
    protected void addLifetimeExtenders() {
        mLifetimeExtenders.add(new RemoteInputHistoryExtender());
        mLifetimeExtenders.add(new SmartReplyHistoryExtender());
        mLifetimeExtenders.add(new RemoteInputActiveExtender());
    }

    public ArrayList<NotificationLifetimeExtender> getLifetimeExtenders() {
        return mLifetimeExtenders;
    }

    public RemoteInputController getController() {
        return mRemoteInputController;
    }

    @VisibleForTesting
    void onPerformRemoveNotification(NotificationEntry entry, final String key) {
        if (mKeysKeptForRemoteInputHistory.contains(key)) {
            mKeysKeptForRemoteInputHistory.remove(key);
        }
        if (mRemoteInputController.isRemoteInputActive(entry)) {
            mRemoteInputController.removeRemoteInput(entry, null);
        }
    }

    public void onPanelCollapsed() {
        for (int i = 0; i < mEntriesKeptForRemoteInputActive.size(); i++) {
            NotificationEntry entry = mEntriesKeptForRemoteInputActive.valueAt(i);
            mRemoteInputController.removeRemoteInput(entry, null);
            if (mNotificationLifetimeFinishedCallback != null) {
                mNotificationLifetimeFinishedCallback.onSafeToRemove(entry.getKey());
            }
        }
        mEntriesKeptForRemoteInputActive.clear();
    }

    public boolean isNotificationKeptForRemoteInputHistory(String key) {
        return mKeysKeptForRemoteInputHistory.contains(key);
    }

    public boolean shouldKeepForRemoteInputHistory(NotificationEntry entry) {
        if (!FORCE_REMOTE_INPUT_HISTORY) {
            return false;
        }
        return (mRemoteInputController.isSpinning(entry.getKey())
                || entry.hasJustSentRemoteInput());
    }

    public boolean shouldKeepForSmartReplyHistory(NotificationEntry entry) {
        if (!FORCE_REMOTE_INPUT_HISTORY) {
            return false;
        }
        return mSmartReplyController.isSendingSmartReply(entry.getKey());
    }

    public void checkRemoteInputOutside(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_OUTSIDE // touch outside the source bar
                && event.getX() == 0 && event.getY() == 0  // a touch outside both bars
                && mRemoteInputController.isRemoteInputActive()) {
            mRemoteInputController.closeRemoteInputs();
        }
    }

    @VisibleForTesting
    StatusBarNotification rebuildNotificationForCanceledSmartReplies(
            NotificationEntry entry) {
        return rebuildNotificationWithRemoteInput(entry, null /* remoteInputTest */,
                false /* showSpinner */, null /* mimeType */, null /* uri */);
    }

    @VisibleForTesting
    StatusBarNotification rebuildNotificationWithRemoteInput(NotificationEntry entry,
            CharSequence remoteInputText, boolean showSpinner, String mimeType, Uri uri) {
        StatusBarNotification sbn = entry.getSbn();

        Notification.Builder b = Notification.Builder
                .recoverBuilder(mContext, sbn.getNotification().clone());
        if (remoteInputText != null || uri != null) {
            RemoteInputHistoryItem[] oldHistoryItems = (RemoteInputHistoryItem[])
                    sbn.getNotification().extras.getParcelableArray(
                            Notification.EXTRA_REMOTE_INPUT_HISTORY_ITEMS);
            RemoteInputHistoryItem[] newHistoryItems;

            if (oldHistoryItems == null) {
                newHistoryItems = new RemoteInputHistoryItem[1];
            } else {
                newHistoryItems = new RemoteInputHistoryItem[oldHistoryItems.length + 1];
                System.arraycopy(oldHistoryItems, 0, newHistoryItems, 1, oldHistoryItems.length);
            }
            RemoteInputHistoryItem newItem;
            if (uri != null) {
                newItem = new RemoteInputHistoryItem(mimeType, uri, remoteInputText);
            } else {
                newItem = new RemoteInputHistoryItem(remoteInputText);
            }
            newHistoryItems[0] = newItem;
            b.setRemoteInputHistory(newHistoryItems);
        }
        b.setShowRemoteInputSpinner(showSpinner);
        b.setHideSmartReplies(true);

        Notification newNotification = b.build();

        // Undo any compatibility view inflation
        newNotification.contentView = sbn.getNotification().contentView;
        newNotification.bigContentView = sbn.getNotification().bigContentView;
        newNotification.headsUpContentView = sbn.getNotification().headsUpContentView;

        return new StatusBarNotification(
                sbn.getPackageName(),
                sbn.getOpPkg(),
                sbn.getId(),
                sbn.getTag(),
                sbn.getUid(),
                sbn.getInitialPid(),
                newNotification,
                sbn.getUser(),
                sbn.getOverrideGroupKey(),
                sbn.getPostTime());
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NotificationRemoteInputManager state:");
        pw.print("  mKeysKeptForRemoteInputHistory: ");
        pw.println(mKeysKeptForRemoteInputHistory);
        pw.print("  mEntriesKeptForRemoteInputActive: ");
        pw.println(mEntriesKeptForRemoteInputActive);
    }

    public void bindRow(ExpandableNotificationRow row) {
        row.setRemoteInputController(mRemoteInputController);
    }

    /**
     * Return on-click handler for notification remote views
     *
     * @return on-click handler
     */
    public RemoteViews.OnClickHandler getRemoteViewsOnClickHandler() {
        return mOnClickHandler;
    }

    @VisibleForTesting
    public Set<NotificationEntry> getEntriesKeptForRemoteInputActive() {
        return mEntriesKeptForRemoteInputActive;
    }

    /**
     * NotificationRemoteInputManager has multiple reasons to keep notification lifetime extended
     * so we implement multiple NotificationLifetimeExtenders
     */
    protected abstract class RemoteInputExtender implements NotificationLifetimeExtender {
        @Override
        public void setCallback(NotificationSafeToRemoveCallback callback) {
            if (mNotificationLifetimeFinishedCallback == null) {
                mNotificationLifetimeFinishedCallback = callback;
            }
        }
    }

    /**
     * Notification is kept alive as it was cancelled in response to a remote input interaction.
     * This allows us to show what you replied and allows you to continue typing into it.
     */
    protected class RemoteInputHistoryExtender extends RemoteInputExtender {
        @Override
        public boolean shouldExtendLifetime(@NonNull NotificationEntry entry) {
            return shouldKeepForRemoteInputHistory(entry);
        }

        @Override
        public void setShouldManageLifetime(NotificationEntry entry,
                boolean shouldExtend) {
            if (shouldExtend) {
                CharSequence remoteInputText = entry.remoteInputText;
                if (TextUtils.isEmpty(remoteInputText)) {
                    remoteInputText = entry.remoteInputTextWhenReset;
                }
                String remoteInputMimeType = entry.remoteInputMimeType;
                Uri remoteInputUri = entry.remoteInputUri;
                StatusBarNotification newSbn = rebuildNotificationWithRemoteInput(entry,
                        remoteInputText, false /* showSpinner */, remoteInputMimeType,
                        remoteInputUri);
                entry.onRemoteInputInserted();

                if (newSbn == null) {
                    return;
                }

                mEntryManager.updateNotification(newSbn, null);

                // Ensure the entry hasn't already been removed. This can happen if there is an
                // inflation exception while updating the remote history
                if (entry.isRemoved()) {
                    return;
                }

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Keeping notification around after sending remote input "
                            + entry.getKey());
                }

                mKeysKeptForRemoteInputHistory.add(entry.getKey());
            } else {
                mKeysKeptForRemoteInputHistory.remove(entry.getKey());
            }
        }
    }

    /**
     * Notification is kept alive for smart reply history.  Similar to REMOTE_INPUT_HISTORY but with
     * {@link SmartReplyController} specific logic
     */
    protected class SmartReplyHistoryExtender extends RemoteInputExtender {
        @Override
        public boolean shouldExtendLifetime(@NonNull NotificationEntry entry) {
            return shouldKeepForSmartReplyHistory(entry);
        }

        @Override
        public void setShouldManageLifetime(NotificationEntry entry,
                boolean shouldExtend) {
            if (shouldExtend) {
                StatusBarNotification newSbn = rebuildNotificationForCanceledSmartReplies(entry);

                if (newSbn == null) {
                    return;
                }

                mEntryManager.updateNotification(newSbn, null);

                if (entry.isRemoved()) {
                    return;
                }

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Keeping notification around after sending smart reply "
                            + entry.getKey());
                }

                mKeysKeptForRemoteInputHistory.add(entry.getKey());
            } else {
                mKeysKeptForRemoteInputHistory.remove(entry.getKey());
                mSmartReplyController.stopSending(entry);
            }
        }
    }

    /**
     * Notification is kept alive because the user is still using the remote input
     */
    protected class RemoteInputActiveExtender extends RemoteInputExtender {
        @Override
        public boolean shouldExtendLifetime(@NonNull NotificationEntry entry) {
            return mRemoteInputController.isRemoteInputActive(entry);
        }

        @Override
        public void setShouldManageLifetime(NotificationEntry entry,
                boolean shouldExtend) {
            if (shouldExtend) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Keeping notification around while remote input active "
                            + entry.getKey());
                }
                mEntriesKeptForRemoteInputActive.add(entry);
            } else {
                mEntriesKeptForRemoteInputActive.remove(entry);
            }
        }
    }

    /**
     * Callback for various remote input related events, or for providing information that
     * NotificationRemoteInputManager needs to know to decide what to do.
     */
    public interface Callback {

        /**
         * Called when remote input was activated but the device is locked.
         *
         * @param row
         * @param clicked
         */
        void onLockedRemoteInput(ExpandableNotificationRow row, View clicked);

        /**
         * Called when remote input was activated but the device is locked and in a managed profile.
         *
         * @param userId
         * @param row
         * @param clicked
         */
        void onLockedWorkRemoteInput(int userId, ExpandableNotificationRow row, View clicked);

        /**
         * Called when a row should be made expanded for the purposes of remote input.
         *
         * @param row
         * @param clickedView
         */
        void onMakeExpandedVisibleForRemoteInput(ExpandableNotificationRow row, View clickedView);

        /**
         * Return whether or not remote input should be handled for this view.
         *
         * @param view
         * @param pendingIntent
         * @return true iff the remote input should be handled
         */
        boolean shouldHandleRemoteInput(View view, PendingIntent pendingIntent);

        /**
         * Performs any special handling for a remote view click. The default behaviour can be
         * called through the defaultHandler parameter.
         *
         * @param view
         * @param pendingIntent
         * @param defaultHandler
         * @return  true iff the click was handled
         */
        boolean handleRemoteViewClick(View view, PendingIntent pendingIntent,
                ClickHandler defaultHandler);
    }

    /**
     * Helper interface meant for passing the default on click behaviour to NotificationPresenter,
     * so it may do its own handling before invoking the default behaviour.
     */
    public interface ClickHandler {
        /**
         * Tries to handle a click on a remote view.
         *
         * @return true iff the click was handled
         */
        boolean handleClick();
    }
}
