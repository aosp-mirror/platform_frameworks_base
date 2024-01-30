/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.UserInfo;
import android.os.UserHandle;

import androidx.annotation.NonNull;

import com.android.systemui.res.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.GuestResetOrExitSessionReceiver.ResetSessionDialogFactory;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.qs.QSUserSwitcherEvent;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.util.settings.SecureSettings;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Manages notification when a guest session is resumed.
 */
@SysUISingleton
public class GuestResumeSessionReceiver {

    @VisibleForTesting
    public static final String SETTING_GUEST_HAS_LOGGED_IN = "systemui.guest_has_logged_in";

    @VisibleForTesting
    public AlertDialog mNewSessionDialog;
    private final Executor mMainExecutor;
    private final UserTracker mUserTracker;
    private final SecureSettings mSecureSettings;
    private final ResetSessionDialogFactory mResetSessionDialogFactory;
    private final GuestSessionNotification mGuestSessionNotification;

    @VisibleForTesting
    public final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    cancelDialog();

                    UserInfo currentUser = mUserTracker.getUserInfo();
                    if (!currentUser.isGuest()) {
                        return;
                    }

                    int guestLoginState = mSecureSettings.getIntForUser(
                            SETTING_GUEST_HAS_LOGGED_IN, 0, newUser);

                    if (guestLoginState == 0) {
                        // set 1 to indicate, 1st login
                        guestLoginState = 1;
                        mSecureSettings.putIntForUser(SETTING_GUEST_HAS_LOGGED_IN, guestLoginState,
                                newUser);
                    } else if (guestLoginState == 1) {
                        // set 2 to indicate, 2nd or later login
                        guestLoginState = 2;
                        mSecureSettings.putIntForUser(SETTING_GUEST_HAS_LOGGED_IN, guestLoginState,
                                newUser);
                    }

                    mGuestSessionNotification.createPersistentNotification(currentUser,
                            (guestLoginState <= 1));

                    if (guestLoginState > 1) {
                        mNewSessionDialog = mResetSessionDialogFactory.create(newUser);
                        mNewSessionDialog.show();
                    }
                }
            };

    @Inject
    public GuestResumeSessionReceiver(
            @Main Executor mainExecutor,
            UserTracker userTracker,
            SecureSettings secureSettings,
            GuestSessionNotification guestSessionNotification,
            ResetSessionDialogFactory resetSessionDialogFactory) {
        mMainExecutor = mainExecutor;
        mUserTracker = userTracker;
        mSecureSettings = secureSettings;
        mGuestSessionNotification = guestSessionNotification;
        mResetSessionDialogFactory = resetSessionDialogFactory;
    }

    /**
     * Register this receiver with the {@link BroadcastDispatcher}
     */
    public void register() {
        mUserTracker.addCallback(mUserChangedCallback, mMainExecutor);
    }

    private void cancelDialog() {
        if (mNewSessionDialog != null && mNewSessionDialog.isShowing()) {
            mNewSessionDialog.cancel();
            mNewSessionDialog = null;
        }
    }

    /**
     * Dialog shown when user when asking for confirmation before deleting guest user.
     */
    @VisibleForTesting
    public static class ResetSessionDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        @VisibleForTesting
        public static final int BUTTON_WIPE = BUTTON_NEGATIVE;
        @VisibleForTesting
        public static final int BUTTON_DONTWIPE = BUTTON_POSITIVE;

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
        public ResetSessionDialog(Context context,
                UserSwitcherController userSwitcherController,
                UiEventLogger uiEventLogger,
                @Assisted int userId) {
            super(context, DEFAULT_THEME, false /* dismissOnDeviceLock */);

            setTitle(context.getString(R.string.guest_wipe_session_title));
            setMessage(context.getString(R.string.guest_wipe_session_message));
            setCanceledOnTouchOutside(false);

            setButton(BUTTON_WIPE,
                    context.getString(R.string.guest_wipe_session_wipe), this);
            setButton(BUTTON_DONTWIPE,
                    context.getString(R.string.guest_wipe_session_dontwipe), this);

            mUserSwitcherController = userSwitcherController;
            mUiEventLogger = uiEventLogger;
            mUserId = userId;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == BUTTON_WIPE) {
                mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_GUEST_WIPE);
                mUserSwitcherController.removeGuestUser(mUserId, UserHandle.USER_NULL);
                dismiss();
            } else if (which == BUTTON_DONTWIPE) {
                mUiEventLogger.log(QSUserSwitcherEvent.QS_USER_GUEST_CONTINUE);
                cancel();
            }
        }
    }
}
