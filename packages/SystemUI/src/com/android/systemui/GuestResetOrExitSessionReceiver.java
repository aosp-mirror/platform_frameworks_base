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
import android.os.UserHandle;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.qs.QSUserSwitcherEvent;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.UserSwitcherController;

import javax.inject.Inject;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

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
    private final ResetSessionDialog.Factory mResetSessionDialogFactory;
    private final ExitSessionDialog.Factory mExitSessionDialogFactory;

    @Inject
    public GuestResetOrExitSessionReceiver(UserTracker userTracker,
            BroadcastDispatcher broadcastDispatcher,
            ResetSessionDialog.Factory resetSessionDialogFactory,
            ExitSessionDialog.Factory exitSessionDialogFactory) {
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
            mExitSessionDialog = mExitSessionDialogFactory.create(currentUser.id,
                        currentUser.isEphemeral());
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
     * Dialog shown when asking for confirmation before
     * reset and restart of guest user.
     */
    public static final class ResetSessionDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        private final UserSwitcherController mUserSwitcherController;
        private final UiEventLogger mUiEventLogger;
        private final int mUserId;

        /** Factory class to create guest reset dialog instance */
        @AssistedFactory
        public interface Factory {
            /** Create a guest reset dialog instance */
            ResetSessionDialog create(int userId);
        }

        @AssistedInject
        ResetSessionDialog(Context context,
                UserSwitcherController userSwitcherController,
                UiEventLogger uiEventLogger,
                @Assisted int userId) {
            super(context);

            setTitle(com.android.settingslib.R.string.guest_reset_and_restart_dialog_title);
            setMessage(context.getString(
                        com.android.settingslib.R.string.guest_reset_and_restart_dialog_message));
            setButton(DialogInterface.BUTTON_NEUTRAL,
                    context.getString(android.R.string.cancel), this);
            setButton(DialogInterface.BUTTON_POSITIVE,
                    context.getString(
                        com.android.settingslib.R.string.guest_reset_guest_confirm_button), this);
            setCanceledOnTouchOutside(false);

            mUserSwitcherController = userSwitcherController;
            mUiEventLogger = uiEventLogger;
            mUserId = userId;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == DialogInterface.BUTTON_POSITIVE) {
                mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_GUEST_REMOVE);
                mUserSwitcherController.removeGuestUser(mUserId, UserHandle.USER_NULL);
            } else if (which == DialogInterface.BUTTON_NEUTRAL) {
                cancel();
            }
        }
    }

    /**
     * Dialog shown when asking for confirmation before
     * exit of guest user.
     */
    public static final class ExitSessionDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        private final UserSwitcherController mUserSwitcherController;
        private final int mUserId;
        private boolean mIsEphemeral;

        /** Factory class to create guest exit dialog instance */
        @AssistedFactory
        public interface Factory {
            /** Create a guest exit dialog instance */
            ExitSessionDialog create(int userId, boolean isEphemeral);
        }

        @AssistedInject
        ExitSessionDialog(Context context,
                UserSwitcherController userSwitcherController,
                @Assisted int userId,
                @Assisted boolean isEphemeral) {
            super(context);

            if (isEphemeral) {
                setTitle(context.getString(
                            com.android.settingslib.R.string.guest_exit_dialog_title));
                setMessage(context.getString(
                            com.android.settingslib.R.string.guest_exit_dialog_message));
                setButton(DialogInterface.BUTTON_NEUTRAL,
                        context.getString(android.R.string.cancel), this);
                setButton(DialogInterface.BUTTON_POSITIVE,
                        context.getString(
                            com.android.settingslib.R.string.guest_exit_dialog_button), this);
            } else {
                setTitle(context.getString(
                            com.android.settingslib
                                .R.string.guest_exit_dialog_title_non_ephemeral));
                setMessage(context.getString(
                            com.android.settingslib
                                .R.string.guest_exit_dialog_message_non_ephemeral));
                setButton(DialogInterface.BUTTON_NEUTRAL,
                        context.getString(android.R.string.cancel), this);
                setButton(DialogInterface.BUTTON_NEGATIVE,
                        context.getString(
                            com.android.settingslib.R.string.guest_exit_clear_data_button), this);
                setButton(DialogInterface.BUTTON_POSITIVE,
                        context.getString(
                            com.android.settingslib.R.string.guest_exit_save_data_button), this);
            }
            setCanceledOnTouchOutside(false);

            mUserSwitcherController = userSwitcherController;
            mUserId = userId;
            mIsEphemeral = isEphemeral;
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
                    cancel();
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
                    cancel();
                }
            }
        }
    }
}
