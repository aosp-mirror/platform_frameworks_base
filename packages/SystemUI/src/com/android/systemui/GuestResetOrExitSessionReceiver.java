/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.UserHandle;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.qs.QSUserSwitcherEvent;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.UserSwitcherController;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import javax.inject.Inject;

/**
 * Manages handling of guest session persistent notification
 * and actions to reset guest or exit guest session
 */
public final class GuestResetOrExitSessionReceiver extends BroadcastReceiver {

    private static final String TAG = GuestResetOrExitSessionReceiver.class.getSimpleName();

    /**
     * Broadcast sent to the system when guest user needs to be reset.
     * This is only sent to registered receivers, not manifest receivers.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_GUEST_RESET = "android.intent.action.GUEST_RESET";

    /**
     * Broadcast sent to the system when guest user needs to exit.
     * This is only sent to registered receivers, not manifest receivers.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_GUEST_EXIT = "android.intent.action.GUEST_EXIT";

    public AlertDialog mExitSessionDialog;
    public AlertDialog mResetSessionDialog;
    private final UserTracker mUserTracker;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final ResetSessionDialogFactory mResetSessionDialogFactory;
    private final ExitSessionDialogFactory mExitSessionDialogFactory;

    @Inject
    public GuestResetOrExitSessionReceiver(UserTracker userTracker,
            BroadcastDispatcher broadcastDispatcher,
            ResetSessionDialogFactory resetSessionDialogFactory,
            ExitSessionDialogFactory exitSessionDialogFactory) {
        mUserTracker = userTracker;
        mBroadcastDispatcher = broadcastDispatcher;
        mResetSessionDialogFactory = resetSessionDialogFactory;
        mExitSessionDialogFactory = exitSessionDialogFactory;
    }

    /**
     * Register this receiver with the {@link BroadcastDispatcher}
     */
    public void register() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_GUEST_RESET);
        intentFilter.addAction(ACTION_GUEST_EXIT);
        mBroadcastDispatcher.registerReceiver(this, intentFilter, null /* handler */,
                                             UserHandle.SYSTEM);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        cancelResetDialog();
        cancelExitDialog();

        UserInfo currentUser = mUserTracker.getUserInfo();
        if (!currentUser.isGuest()) {
            return;
        }

        if (ACTION_GUEST_RESET.equals(action)) {
            mResetSessionDialog = mResetSessionDialogFactory.create(currentUser.id);
            mResetSessionDialog.show();
        } else if (ACTION_GUEST_EXIT.equals(action)) {
            mExitSessionDialog = mExitSessionDialogFactory.create(
                    currentUser.isEphemeral(), currentUser.id);
            mExitSessionDialog.show();
        }
    }

    private void cancelResetDialog() {
        if (mResetSessionDialog != null && mResetSessionDialog.isShowing()) {
            mResetSessionDialog.cancel();
            mResetSessionDialog = null;
        }
    }

    private void cancelExitDialog() {
        if (mExitSessionDialog != null && mExitSessionDialog.isShowing()) {
            mExitSessionDialog.cancel();
            mExitSessionDialog = null;
        }
    }

    /**
     * Factory class to create guest reset dialog instance
     *
     * Dialog shown when asking for confirmation before
     * reset and restart of guest user.
     */
    public static final class ResetSessionDialogFactory {
        private final SystemUIDialog.Factory mDialogFactory;
        private final Resources mResources;
        private final ResetSessionDialogClickListener.Factory mClickListenerFactory;

        @Inject
        public ResetSessionDialogFactory(
                SystemUIDialog.Factory dialogFactory,
                @Main Resources resources,
                ResetSessionDialogClickListener.Factory clickListenerFactory) {
            mDialogFactory = dialogFactory;
            mResources = resources;
            mClickListenerFactory = clickListenerFactory;
        }

        /** Create a guest reset dialog instance */
        public AlertDialog create(int userId) {
            SystemUIDialog dialog = mDialogFactory.create();
            ResetSessionDialogClickListener listener = mClickListenerFactory.create(
                    userId, dialog);
            dialog.setTitle(com.android.settingslib.R.string.guest_reset_and_restart_dialog_title);
            dialog.setMessage(mResources.getString(
                    com.android.settingslib.R.string.guest_reset_and_restart_dialog_message));
            dialog.setButton(
                    DialogInterface.BUTTON_NEUTRAL,
                    mResources.getString(android.R.string.cancel),
                    listener);
            dialog.setButton(DialogInterface.BUTTON_POSITIVE,
                    mResources.getString(
                            com.android.settingslib.R.string.guest_reset_guest_confirm_button),
                    listener);
            dialog.setCanceledOnTouchOutside(false);
            return dialog;
        }
    }

    public static class ResetSessionDialogClickListener implements DialogInterface.OnClickListener {
        private final UserSwitcherController mUserSwitcherController;
        private final UiEventLogger mUiEventLogger;
        private final int mUserId;
        private final DialogInterface mDialog;

        @AssistedFactory
        public interface Factory {
            ResetSessionDialogClickListener create(int userId, DialogInterface dialog);
        }

        @AssistedInject
        public ResetSessionDialogClickListener(
                UserSwitcherController userSwitcherController,
                UiEventLogger uiEventLogger,
                @Assisted int userId,
                @Assisted DialogInterface dialog
        ) {
            mUserSwitcherController = userSwitcherController;
            mUiEventLogger = uiEventLogger;
            mUserId = userId;
            mDialog = dialog;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == DialogInterface.BUTTON_POSITIVE) {
                mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_GUEST_REMOVE);
                mUserSwitcherController.removeGuestUser(mUserId, UserHandle.USER_NULL);
            } else if (which == DialogInterface.BUTTON_NEUTRAL) {
                mDialog.cancel();
            }
        }
    }

    /**
     * Dialog shown when asking for confirmation before
     * exit of guest user.
     */
    public static final class ExitSessionDialogFactory {
        private final SystemUIDialog.Factory mDialogFactory;
        private final ExitSessionDialogClickListener.Factory mClickListenerFactory;
        private final Resources mResources;

        @Inject
        public ExitSessionDialogFactory(
                SystemUIDialog.Factory dialogFactory,
                ExitSessionDialogClickListener.Factory clickListenerFactory,
                @Main Resources resources) {
            mDialogFactory = dialogFactory;
            mClickListenerFactory = clickListenerFactory;
            mResources = resources;
        }

        public AlertDialog create(boolean isEphemeral, int userId) {
            SystemUIDialog dialog = mDialogFactory.create();
            ExitSessionDialogClickListener clickListener = mClickListenerFactory.create(
                    isEphemeral, userId, dialog);
            if (isEphemeral) {
                dialog.setTitle(mResources.getString(
                        com.android.settingslib.R.string.guest_exit_dialog_title));
                dialog.setMessage(mResources.getString(
                        com.android.settingslib.R.string.guest_exit_dialog_message));
                dialog.setButton(
                        DialogInterface.BUTTON_NEUTRAL,
                        mResources.getString(android.R.string.cancel),
                        clickListener);
                dialog.setButton(
                        DialogInterface.BUTTON_POSITIVE,
                        mResources.getString(
                                com.android.settingslib.R.string.guest_exit_dialog_button),
                        clickListener);
            } else {
                dialog.setTitle(mResources.getString(
                        com.android.settingslib
                                .R.string.guest_exit_dialog_title_non_ephemeral));
                dialog.setMessage(mResources.getString(
                        com.android.settingslib
                                .R.string.guest_exit_dialog_message_non_ephemeral));
                dialog.setButton(
                        DialogInterface.BUTTON_NEUTRAL,
                        mResources.getString(android.R.string.cancel),
                        clickListener);
                dialog.setButton(
                        DialogInterface.BUTTON_NEGATIVE,
                        mResources.getString(
                                com.android.settingslib.R.string.guest_exit_clear_data_button),
                        clickListener);
                dialog.setButton(
                        DialogInterface.BUTTON_POSITIVE,
                        mResources.getString(
                                com.android.settingslib.R.string.guest_exit_save_data_button),
                        clickListener);
            }
            dialog.setCanceledOnTouchOutside(false);

            return dialog;
        }

    }

    public static class ExitSessionDialogClickListener implements DialogInterface.OnClickListener {
        private final UserSwitcherController mUserSwitcherController;
        private final boolean mIsEphemeral;
        private final int mUserId;
        private final DialogInterface mDialog;

        @AssistedFactory
        public interface Factory {
            ExitSessionDialogClickListener create(
                    boolean isEphemeral,
                    int userId,
                    DialogInterface dialog);
        }

        @AssistedInject
        public ExitSessionDialogClickListener(
                UserSwitcherController userSwitcherController,
                @Assisted boolean isEphemeral,
                @Assisted int userId,
                @Assisted DialogInterface dialog
        ) {
            mUserSwitcherController = userSwitcherController;
            mIsEphemeral = isEphemeral;
            mUserId = userId;
            mDialog = dialog;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (mIsEphemeral) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    // Ephemeral guest: exit guest, guest is removed by the system
                    // on exit, since its marked ephemeral
                    mUserSwitcherController.exitGuestUser(mUserId, UserHandle.USER_NULL, false);
                } else if (which == DialogInterface.BUTTON_NEUTRAL) {
                    // Cancel clicked, do nothing
                    mDialog.cancel();
                }
            } else {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    // Non-ephemeral guest: exit guest, guest is not removed by the system
                    // on exit, since its marked non-ephemeral
                    mUserSwitcherController.exitGuestUser(mUserId, UserHandle.USER_NULL, false);
                } else if (which == DialogInterface.BUTTON_NEGATIVE) {
                    // Non-ephemeral guest: remove guest and then exit
                    mUserSwitcherController.exitGuestUser(mUserId, UserHandle.USER_NULL, true);
                } else if (which == DialogInterface.BUTTON_NEUTRAL) {
                    // Cancel clicked, do nothing
                    mDialog.cancel();
                }
            }
        }
    }
}
