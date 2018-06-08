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

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserManager;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.policy.RemoteInputView;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Set;

/**
 * Class for handling remote input state over a set of notifications. This class handles things
 * like keeping notifications temporarily that were cancelled as a response to a remote input
 * interaction, keeping track of notifications to remove when NotificationPresenter is collapsed,
 * and handling clicks on remote views.
 */
public class NotificationRemoteInputManager implements Dumpable {
    public static final boolean ENABLE_REMOTE_INPUT =
            SystemProperties.getBoolean("debug.enable_remote_input", true);
    public static final boolean FORCE_REMOTE_INPUT_HISTORY =
            SystemProperties.getBoolean("debug.force_remoteinput_history", true);
    private static final boolean DEBUG = false;
    private static final String TAG = "NotificationRemoteInputManager";

    /**
     * How long to wait before auto-dismissing a notification that was kept for remote input, and
     * has now sent a remote input. We auto-dismiss, because the app may not see a reason to cancel
     * these given that they technically don't exist anymore. We wait a bit in case the app issues
     * an update.
     */
    private static final int REMOTE_INPUT_KEPT_ENTRY_AUTO_CANCEL_DELAY = 200;

    protected final ArraySet<NotificationData.Entry> mRemoteInputEntriesToRemoveOnCollapse =
            new ArraySet<>();

    // Dependencies:
    protected final NotificationLockscreenUserManager mLockscreenUserManager =
            Dependency.get(NotificationLockscreenUserManager.class);

    protected final Context mContext;
    private final UserManager mUserManager;

    protected RemoteInputController mRemoteInputController;
    protected NotificationPresenter mPresenter;
    protected NotificationEntryManager mEntryManager;
    protected IStatusBarService mBarService;
    protected Callback mCallback;

    private final RemoteViews.OnClickHandler mOnClickHandler = new RemoteViews.OnClickHandler() {

        @Override
        public boolean onClickHandler(
                final View view, final PendingIntent pendingIntent, final Intent fillInIntent) {
            mPresenter.wakeUpIfDozing(SystemClock.uptimeMillis(), view);

            if (handleRemoteInput(view, pendingIntent)) {
                return true;
            }

            if (DEBUG) {
                Log.v(TAG, "Notification click handler invoked for intent: " + pendingIntent);
            }
            logActionClick(view);
            // The intent we are sending is for the application, which
            // won't have permission to immediately start an activity after
            // the user switches to home.  We know it is safe to do at this
            // point, so make sure new activity switches are now allowed.
            try {
                ActivityManager.getService().resumeAppSwitches();
            } catch (RemoteException e) {
            }
            return mCallback.handleRemoteViewClick(view, pendingIntent, fillInIntent,
                    () -> superOnClickHandler(view, pendingIntent, fillInIntent));
        }

        private void logActionClick(View view) {
            ViewParent parent = view.getParent();
            String key = getNotificationKeyForParent(parent);
            if (key == null) {
                Log.w(TAG, "Couldn't determine notification for click.");
                return;
            }
            int index = -1;
            // If this is a default template, determine the index of the button.
            if (view.getId() == com.android.internal.R.id.action0 &&
                    parent != null && parent instanceof ViewGroup) {
                ViewGroup actionGroup = (ViewGroup) parent;
                index = actionGroup.indexOfChild(view);
            }
            final int count = mEntryManager.getNotificationData().getActiveNotifications().size();
            final int rank = mEntryManager.getNotificationData().getRank(key);
            final NotificationVisibility nv = NotificationVisibility.obtain(key, rank, count, true);
            try {
                mBarService.onNotificationActionClick(key, index, nv);
            } catch (RemoteException e) {
                // Ignore
            }
        }

        private String getNotificationKeyForParent(ViewParent parent) {
            while (parent != null) {
                if (parent instanceof ExpandableNotificationRow) {
                    return ((ExpandableNotificationRow) parent)
                            .getStatusBarNotification().getKey();
                }
                parent = parent.getParent();
            }
            return null;
        }

        private boolean superOnClickHandler(View view, PendingIntent pendingIntent,
                Intent fillInIntent) {
            return super.onClickHandler(view, pendingIntent, fillInIntent,
                    WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY);
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

            ViewParent p = view.getParent();
            RemoteInputView riv = null;
            while (p != null) {
                if (p instanceof View) {
                    View pv = (View) p;
                    if (pv.isRootNamespace()) {
                        riv = findRemoteInputView(pv);
                        break;
                    }
                }
                p = p.getParent();
            }
            ExpandableNotificationRow row = null;
            while (p != null) {
                if (p instanceof ExpandableNotificationRow) {
                    row = (ExpandableNotificationRow) p;
                    break;
                }
                p = p.getParent();
            }

            if (row == null) {
                return false;
            }

            row.setUserExpanded(true);

            if (!mLockscreenUserManager.shouldAllowLockscreenRemoteInput()) {
                final int userId = pendingIntent.getCreatorUserHandle().getIdentifier();
                if (mLockscreenUserManager.isLockscreenPublicMode(userId)) {
                    mCallback.onLockedRemoteInput(row, view);
                    return true;
                }
                if (mUserManager.getUserInfo(userId).isManagedProfile()
                        && mPresenter.isDeviceLocked(userId)) {
                    mCallback.onLockedWorkRemoteInput(userId, row, view);
                    return true;
                }
            }

            if (riv == null) {
                riv = findRemoteInputView(row.getPrivateLayout().getExpandedChild());
                if (riv == null) {
                    return false;
                }
                if (!row.getPrivateLayout().getExpandedChild().isShown()) {
                    mCallback.onMakeExpandedVisibleForRemoteInput(row, view);
                    return true;
                }
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
            riv.setRemoteInput(inputs, input);
            riv.focusAnimated();

            return true;
        }

        private RemoteInputView findRemoteInputView(View v) {
            if (v == null) {
                return null;
            }
            return (RemoteInputView) v.findViewWithTag(RemoteInputView.VIEW_TAG);
        }
    };

    public NotificationRemoteInputManager(Context context) {
        mContext = context;
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
    }

    public void setUpWithPresenter(NotificationPresenter presenter,
            NotificationEntryManager entryManager,
            Callback callback,
            RemoteInputController.Delegate delegate) {
        mPresenter = presenter;
        mEntryManager = entryManager;
        mCallback = callback;
        mRemoteInputController = new RemoteInputController(delegate);
        mRemoteInputController.addCallback(new RemoteInputController.Callback() {
            @Override
            public void onRemoteInputSent(NotificationData.Entry entry) {
                if (FORCE_REMOTE_INPUT_HISTORY
                        && mEntryManager.isNotificationKeptForRemoteInput(entry.key)) {
                    mEntryManager.removeNotification(entry.key, null);
                } else if (mRemoteInputEntriesToRemoveOnCollapse.contains(entry)) {
                    // We're currently holding onto this notification, but from the apps point of
                    // view it is already canceled, so we'll need to cancel it on the apps behalf
                    // after sending - unless the app posts an update in the mean time, so wait a
                    // bit.
                    mPresenter.getHandler().postDelayed(() -> {
                        if (mRemoteInputEntriesToRemoveOnCollapse.remove(entry)) {
                            mEntryManager.removeNotification(entry.key, null);
                        }
                    }, REMOTE_INPUT_KEPT_ENTRY_AUTO_CANCEL_DELAY);
                }
                try {
                    mBarService.onNotificationDirectReplied(entry.notification.getKey());
                } catch (RemoteException e) {
                    // Nothing to do, system going down
                }
            }
        });

    }

    public RemoteInputController getController() {
        return mRemoteInputController;
    }

    public void onUpdateNotification(NotificationData.Entry entry) {
        mRemoteInputEntriesToRemoveOnCollapse.remove(entry);
    }

    /**
     * Returns true if NotificationRemoteInputManager wants to keep this notification around.
     *
     * @param entry notification being removed
     */
    public boolean onRemoveNotification(NotificationData.Entry entry) {
        if (entry != null && mRemoteInputController.isRemoteInputActive(entry)
                && (entry.row != null && !entry.row.isDismissed())) {
            mRemoteInputEntriesToRemoveOnCollapse.add(entry);
            return true;
        }
        return false;
    }

    public void onPerformRemoveNotification(StatusBarNotification n,
            NotificationData.Entry entry) {
        if (mRemoteInputController.isRemoteInputActive(entry)) {
            mRemoteInputController.removeRemoteInput(entry, null);
        }
    }

    public void removeRemoteInputEntriesKeptUntilCollapsed() {
        for (int i = 0; i < mRemoteInputEntriesToRemoveOnCollapse.size(); i++) {
            NotificationData.Entry entry = mRemoteInputEntriesToRemoveOnCollapse.valueAt(i);
            mRemoteInputController.removeRemoteInput(entry, null);
            mEntryManager.removeNotification(entry.key, mEntryManager.getLatestRankingMap());
        }
        mRemoteInputEntriesToRemoveOnCollapse.clear();
    }

    public void checkRemoteInputOutside(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_OUTSIDE // touch outside the source bar
                && event.getX() == 0 && event.getY() == 0  // a touch outside both bars
                && mRemoteInputController.isRemoteInputActive()) {
            mRemoteInputController.closeRemoteInputs();
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NotificationRemoteInputManager state:");
        pw.print("  mRemoteInputEntriesToRemoveOnCollapse: ");
        pw.println(mRemoteInputEntriesToRemoveOnCollapse);
    }

    public void bindRow(ExpandableNotificationRow row) {
        row.setRemoteInputController(mRemoteInputController);
        row.setRemoteViewClickHandler(mOnClickHandler);
    }

    @VisibleForTesting
    public Set<NotificationData.Entry> getRemoteInputEntriesToRemoveOnCollapse() {
        return mRemoteInputEntriesToRemoveOnCollapse;
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
         * @param fillInIntent
         * @param defaultHandler
         * @return  true iff the click was handled
         */
        boolean handleRemoteViewClick(View view, PendingIntent pendingIntent, Intent fillInIntent,
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
