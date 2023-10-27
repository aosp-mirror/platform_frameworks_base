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


import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.RemoteViews;
import android.widget.RemoteViews.InteractionHandler;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.CoreStartable;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.res.R;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.dagger.CentralSurfacesDependenciesModule;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.RemoteInputControllerLogger;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntry.EditedSuggestionInfo;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.RemoteInputUriController;
import com.android.systemui.statusbar.policy.RemoteInputView;
import com.android.systemui.util.DumpUtilsKt;
import com.android.systemui.util.ListenerSet;
import com.android.systemui.util.kotlin.JavaAdapter;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Class for handling remote input state over a set of notifications. This class handles things
 * like keeping notifications temporarily that were cancelled as a response to a remote input
 * interaction, keeping track of notifications to remove when NotificationPresenter is collapsed,
 * and handling clicks on remote views.
 */
@SysUISingleton
public class NotificationRemoteInputManager implements CoreStartable {
    public static final boolean ENABLE_REMOTE_INPUT =
            SystemProperties.getBoolean("debug.enable_remote_input", true);
    public static boolean FORCE_REMOTE_INPUT_HISTORY =
            SystemProperties.getBoolean("debug.force_remoteinput_history", true);
    private static final boolean DEBUG = false;
    private static final String TAG = "NotifRemoteInputManager";

    private RemoteInputListener mRemoteInputListener;

    // Dependencies:
    private final NotificationLockscreenUserManager mLockscreenUserManager;
    private final SmartReplyController mSmartReplyController;
    private final NotificationVisibilityProvider mVisibilityProvider;
    private final PowerInteractor mPowerInteractor;
    private final ActionClickLogger mLogger;
    private final JavaAdapter mJavaAdapter;
    private final ShadeInteractor mShadeInteractor;
    protected final Context mContext;
    protected final NotifPipelineFlags mNotifPipelineFlags;
    private final UserManager mUserManager;
    private final KeyguardManager mKeyguardManager;
    private final StatusBarStateController mStatusBarStateController;
    private final RemoteInputUriController mRemoteInputUriController;

    private final RemoteInputControllerLogger mRemoteInputControllerLogger;
    private final NotificationClickNotifier mClickNotifier;

    protected RemoteInputController mRemoteInputController;
    protected IStatusBarService mBarService;
    protected Callback mCallback;

    private final List<RemoteInputController.Callback> mControllerCallbacks = new ArrayList<>();
    private final ListenerSet<Consumer<NotificationEntry>> mActionPressListeners =
            new ListenerSet<>();

    private final InteractionHandler mInteractionHandler = new InteractionHandler() {

        @Override
        public boolean onInteraction(
                View view, PendingIntent pendingIntent, RemoteViews.RemoteResponse response) {
            mPowerInteractor.wakeUpIfDozing(
                    "NOTIFICATION_CLICK", PowerManager.WAKE_REASON_GESTURE);

            Integer actionIndex = (Integer)
                    view.getTag(com.android.internal.R.id.notification_action_index_tag);

            final NotificationEntry entry = getNotificationForParent(view.getParent());
            mLogger.logInitialClick(entry, actionIndex, pendingIntent);

            if (handleRemoteInput(view, pendingIntent)) {
                mLogger.logRemoteInputWasHandled(entry, actionIndex);
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
            Notification.Action action = getActionFromView(view, entry, pendingIntent);
            return mCallback.handleRemoteViewClick(view, pendingIntent,
                    action == null ? false : action.isAuthenticationRequired(), actionIndex, () -> {
                    Pair<Intent, ActivityOptions> options = response.getLaunchOptions(view);
                    mLogger.logStartingIntentWithDefaultHandler(entry, pendingIntent, actionIndex);
                    boolean started = RemoteViews.startPendingIntent(view, pendingIntent, options);
                    if (started) releaseNotificationIfKeptForRemoteInputHistory(entry);
                    return started;
            });
        }

        private @Nullable Notification.Action getActionFromView(View view,
                NotificationEntry entry, PendingIntent actionIntent) {
            Integer actionIndex = (Integer)
                    view.getTag(com.android.internal.R.id.notification_action_index_tag);
            if (actionIndex == null) {
                return null;
            }
            if (entry == null) {
                Log.w(TAG, "Couldn't determine notification for click.");
                return null;
            }

            // Notification may be updated before this function is executed, and thus play safe
            // here and verify that the action object is still the one that where the click happens.
            StatusBarNotification statusBarNotification = entry.getSbn();
            Notification.Action[] actions = statusBarNotification.getNotification().actions;
            if (actions == null || actionIndex >= actions.length) {
                Log.w(TAG, "statusBarNotification.getNotification().actions is null or invalid");
                return null ;
            }
            final Notification.Action action =
                    statusBarNotification.getNotification().actions[actionIndex];
            if (!Objects.equals(action.actionIntent, actionIntent)) {
                Log.w(TAG, "actionIntent does not match");
                return null;
            }
            return action;
        }

        private void logActionClick(
                View view,
                NotificationEntry entry,
                PendingIntent actionIntent) {
            Notification.Action action = getActionFromView(view, entry, actionIntent);
            if (action == null) {
                return;
            }
            ViewParent parent = view.getParent();
            String key = entry.getSbn().getKey();
            int buttonIndex = -1;
            // If this is a default template, determine the index of the button.
            if (view.getId() == com.android.internal.R.id.action0 &&
                    parent != null && parent instanceof ViewGroup) {
                ViewGroup actionGroup = (ViewGroup) parent;
                buttonIndex = actionGroup.indexOfChild(view);
            }
            final NotificationVisibility nv = mVisibilityProvider.obtain(entry, true);
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
     * Injected constructor. See {@link CentralSurfacesDependenciesModule}.
     */
    @Inject
    public NotificationRemoteInputManager(
            Context context,
            NotifPipelineFlags notifPipelineFlags,
            NotificationLockscreenUserManager lockscreenUserManager,
            SmartReplyController smartReplyController,
            NotificationVisibilityProvider visibilityProvider,
            PowerInteractor powerInteractor,
            StatusBarStateController statusBarStateController,
            RemoteInputUriController remoteInputUriController,
            RemoteInputControllerLogger remoteInputControllerLogger,
            NotificationClickNotifier clickNotifier,
            ActionClickLogger logger,
            JavaAdapter javaAdapter,
            ShadeInteractor shadeInteractor) {
        mContext = context;
        mNotifPipelineFlags = notifPipelineFlags;
        mLockscreenUserManager = lockscreenUserManager;
        mSmartReplyController = smartReplyController;
        mVisibilityProvider = visibilityProvider;
        mPowerInteractor = powerInteractor;
        mLogger = logger;
        mJavaAdapter = javaAdapter;
        mShadeInteractor = shadeInteractor;
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mStatusBarStateController = statusBarStateController;
        mRemoteInputUriController = remoteInputUriController;
        mRemoteInputControllerLogger = remoteInputControllerLogger;
        mClickNotifier = clickNotifier;
    }

    @Override
    public void start() {
        mJavaAdapter.alwaysCollectFlow(mShadeInteractor.isAnyExpanded(),
                this::onShadeOrQsExpanded);
    }

    private void onShadeOrQsExpanded(boolean expanded) {
        if (expanded && mStatusBarStateController.getState() != StatusBarState.KEYGUARD) {
            try {
                mBarService.clearNotificationEffects();
            } catch (RemoteException e) {
                // Won't fail unless the world has ended.
            }
        }
        if (!expanded) {
            onPanelCollapsed();
        }
    }

    /** Add a listener for various remote input events.  Works with NEW pipeline only. */
    public void setRemoteInputListener(@NonNull RemoteInputListener remoteInputListener) {
        if (mRemoteInputListener != null) {
            throw new IllegalStateException("mRemoteInputListener is already set");
        }
        mRemoteInputListener = remoteInputListener;
        if (mRemoteInputController != null) {
            mRemoteInputListener.setRemoteInputController(mRemoteInputController);
        }
    }

    /** Initializes this component with the provided dependencies. */
    public void setUpWithCallback(Callback callback, RemoteInputController.Delegate delegate) {
        mCallback = callback;
        mRemoteInputController = new RemoteInputController(delegate,
                mRemoteInputUriController, mRemoteInputControllerLogger);
        if (mRemoteInputListener != null) {
            mRemoteInputListener.setRemoteInputController(mRemoteInputController);
        }
        // Register all stored callbacks from before the Controller was initialized.
        for (RemoteInputController.Callback cb : mControllerCallbacks) {
            mRemoteInputController.addCallback(cb);
        }
        mControllerCallbacks.clear();
        mRemoteInputController.addCallback(new RemoteInputController.Callback() {
            @Override
            public void onRemoteInputSent(NotificationEntry entry) {
                if (mRemoteInputListener != null) {
                    mRemoteInputListener.onRemoteInputSent(entry);
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
    }

    public void addControllerCallback(RemoteInputController.Callback callback) {
        if (mRemoteInputController != null) {
            mRemoteInputController.addCallback(callback);
        } else {
            mControllerCallbacks.add(callback);
        }
    }

    public void removeControllerCallback(RemoteInputController.Callback callback) {
        if (mRemoteInputController != null) {
            mRemoteInputController.removeCallback(callback);
        } else {
            mControllerCallbacks.remove(callback);
        }
    }

    public void addActionPressListener(Consumer<NotificationEntry> listener) {
        mActionPressListeners.addIfAbsent(listener);
    }

    public void removeActionPressListener(Consumer<NotificationEntry> listener) {
        mActionPressListeners.remove(listener);
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
        return activateRemoteInput(view, inputs, input, pendingIntent, editedSuggestionInfo,
                null /* userMessageContent */, null /* authBypassCheck */);
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
     * @param userMessageContent User-entered text with which to initialize the remote input view.
     * @param authBypassCheck Optional auth bypass check associated with this remote input
     *         activation. If {@code null}, we never bypass.
     * @return Whether the {@link RemoteInput} was activated.
     */
    public boolean activateRemoteInput(View view, RemoteInput[] inputs, RemoteInput input,
            PendingIntent pendingIntent, @Nullable EditedSuggestionInfo editedSuggestionInfo,
            @Nullable String userMessageContent,
            @Nullable AuthBypassPredicate authBypassCheck) {
        ViewParent p = view.getParent();
        RemoteInputView riv = null;
        ExpandableNotificationRow row = null;
        while (p != null) {
            if (p instanceof View) {
                View pv = (View) p;
                if (pv.getId() == com.android.internal.R.id.status_bar_latest_event_content) {
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

        final boolean deferBouncer = authBypassCheck != null;
        if (!deferBouncer && showBouncerForRemoteInput(view, pendingIntent, row)) {
            return true;
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
            mCallback.onMakeExpandedVisibleForRemoteInput(row, view, deferBouncer, () -> {
                activateRemoteInput(view, inputs, input, pendingIntent, editedSuggestionInfo,
                        userMessageContent, authBypassCheck);
            });
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

        riv.getController().setRevealParams(new RemoteInputView.RevealParams(cx, cy, r));
        riv.getController().setPendingIntent(pendingIntent);
        riv.getController().setRemoteInput(input);
        riv.getController().setRemoteInputs(inputs);
        riv.getController().setEditedSuggestionInfo(editedSuggestionInfo);
        riv.focusAnimated();
        if (userMessageContent != null) {
            riv.setEditTextContent(userMessageContent);
        }
        if (deferBouncer) {
            final ExpandableNotificationRow finalRow = row;
            riv.getController().setBouncerChecker(() ->
                    !authBypassCheck.canSendRemoteInputWithoutBouncer()
                            && showBouncerForRemoteInput(view, pendingIntent, finalRow));
        }

        return true;
    }

    private boolean showBouncerForRemoteInput(View view, PendingIntent pendingIntent,
            ExpandableNotificationRow row) {

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

        if ((mLockscreenUserManager.isLockscreenPublicMode(userId)
                || mStatusBarStateController.getState() == StatusBarState.KEYGUARD)) {
            // If the parent user is no longer locked, and the user to which the remote
            // input
            // is destined is a locked, managed profile, then onLockedWorkRemoteInput
            // should be
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
        return false;
    }

    private RemoteInputView findRemoteInputView(View v) {
        if (v == null) {
            return null;
        }
        return v.findViewWithTag(RemoteInputView.VIEW_TAG);
    }

    /**
     * Disable remote input on the entry and remove the remote input view.
     * This should be called when a user dismisses a notification that won't be lifetime extended.
     */
    public void cleanUpRemoteInputForUserRemoval(NotificationEntry entry) {
        if (isRemoteInputActive(entry)) {
            entry.mRemoteEditImeVisible = false;
            mRemoteInputController.removeRemoteInput(entry, null,
                    /* reason= */"RemoteInputManager#cleanUpRemoteInputForUserRemoval");
        }
    }

    /** Informs the remote input system that the panel has collapsed */
    public void onPanelCollapsed() {
        if (mRemoteInputListener != null) {
            mRemoteInputListener.onPanelCollapsed();
        }
    }

    /** Returns whether the given notification is lifetime extended because of remote input */
    public boolean isNotificationKeptForRemoteInputHistory(String key) {
        return mRemoteInputListener != null
                && mRemoteInputListener.isNotificationKeptForRemoteInputHistory(key);
    }

    /** Returns whether the notification should be lifetime extended for remote input history */
    public boolean shouldKeepForRemoteInputHistory(NotificationEntry entry) {
        if (!FORCE_REMOTE_INPUT_HISTORY) {
            return false;
        }
        return isSpinning(entry.getKey()) || entry.hasJustSentRemoteInput();
    }

    /**
     * Checks if the notification is being kept due to the user sending an inline reply, and if
     * so, releases that hold.  This is called anytime an action on the notification is dispatched
     * (after unlock, if applicable), and will then wait a short time to allow the app to update the
     * notification in response to the action.
     */
    private void releaseNotificationIfKeptForRemoteInputHistory(NotificationEntry entry) {
        if (entry == null) {
            return;
        }
        if (mRemoteInputListener != null) {
            mRemoteInputListener.releaseNotificationIfKeptForRemoteInputHistory(entry);
        }
        for (Consumer<NotificationEntry> listener : mActionPressListeners) {
            listener.accept(entry);
        }
    }

    /** Returns whether the notification should be lifetime extended for smart reply history */
    public boolean shouldKeepForSmartReplyHistory(NotificationEntry entry) {
        if (!FORCE_REMOTE_INPUT_HISTORY) {
            return false;
        }
        return mSmartReplyController.isSendingSmartReply(entry.getKey());
    }

    public void checkRemoteInputOutside(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_OUTSIDE // touch outside the source bar
                && event.getX() == 0 && event.getY() == 0  // a touch outside both bars
                && isRemoteInputActive()) {
            closeRemoteInputs();
        }
    }

    @Override
    public void dump(PrintWriter pwOriginal, String[] args) {
        IndentingPrintWriter pw = DumpUtilsKt.asIndenting(pwOriginal);
        if (mRemoteInputController != null) {
            pw.println("mRemoteInputController: " + mRemoteInputController);
            pw.increaseIndent();
            mRemoteInputController.dump(pw);
            pw.decreaseIndent();
        }
        if (mRemoteInputListener instanceof Dumpable) {
            pw.println("mRemoteInputListener: " + mRemoteInputListener.getClass().getSimpleName());
            pw.increaseIndent();
            ((Dumpable) mRemoteInputListener).dump(pw, args);
            pw.decreaseIndent();
        }
    }

    public void bindRow(ExpandableNotificationRow row) {
        row.setRemoteInputController(mRemoteInputController);
    }

    /**
     * Return on-click handler for notification remote views
     *
     * @return on-click handler
     */
    public RemoteViews.InteractionHandler getRemoteViewsOnClickHandler() {
        return mInteractionHandler;
    }

    public boolean isRemoteInputActive() {
        return mRemoteInputController != null && mRemoteInputController.isRemoteInputActive();
    }

    public boolean isRemoteInputActive(NotificationEntry entry) {
        return mRemoteInputController != null && mRemoteInputController.isRemoteInputActive(entry);
    }

    public boolean isSpinning(String entryKey) {
        return mRemoteInputController != null && mRemoteInputController.isSpinning(entryKey);
    }

    public void closeRemoteInputs() {
        if (mRemoteInputController != null) {
            mRemoteInputController.closeRemoteInputs();
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
         * @param deferBouncer
         * @param runnable
         */
        void onMakeExpandedVisibleForRemoteInput(ExpandableNotificationRow row, View clickedView,
                boolean deferBouncer, Runnable runnable);

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
         * @param appRequestedAuth
         * @param actionIndex
         * @param defaultHandler
         * @return  true iff the click was handled
         */
        boolean handleRemoteViewClick(View view, PendingIntent pendingIntent,
                boolean appRequestedAuth, @Nullable Integer actionIndex,
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

    /**
     * Predicate that is associated with a specific {@link #activateRemoteInput(View, RemoteInput[],
     * RemoteInput, PendingIntent, EditedSuggestionInfo, String, AuthBypassPredicate)}
     * invocation that determines whether or not the bouncer can be bypassed when sending the
     * RemoteInput.
     */
    public interface AuthBypassPredicate {
        /**
         * Determines if the RemoteInput can be sent without the bouncer. Should be checked the
         * same frame that the RemoteInput is to be sent.
         */
        boolean canSendRemoteInputWithoutBouncer();
    }

    /** Shows the bouncer if necessary */
    public interface BouncerChecker {
        /**
         * Shows the bouncer if necessary in order to send a RemoteInput.
         *
         * @return {@code true} if the bouncer was shown, {@code false} otherwise
         */
        boolean showBouncerIfNecessary();
    }

    /** An interface for listening to remote input events that relate to notification lifetime */
    public interface RemoteInputListener {
        /** Called when remote input pending intent has been sent */
        void onRemoteInputSent(@NonNull NotificationEntry entry);

        /** Called when the notification shade becomes fully closed */
        void onPanelCollapsed();

        /** @return whether lifetime of a notification is being extended by the listener */
        boolean isNotificationKeptForRemoteInputHistory(@NonNull String key);

        /** Called on user interaction to end lifetime extension for history */
        void releaseNotificationIfKeptForRemoteInputHistory(@NonNull NotificationEntry entry);

        /** Called when the RemoteInputController is attached to the manager */
        void setRemoteInputController(@NonNull RemoteInputController remoteInputController);
    }
}
