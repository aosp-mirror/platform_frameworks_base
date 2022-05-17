/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.DialogInterface;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.UserSwitcherController;

public class UserUtil {
    public static void deleteUserWithPrompt(Context context, int userId,
                                            UserSwitcherController userSwitcherController) {
        new RemoveUserDialog(context, userId, userSwitcherController).show();
    }

    private final static class RemoveUserDialog extends SystemUIDialog implements
            DialogInterface.OnClickListener {

        private final int mUserId;
        private final UserSwitcherController mUserSwitcherController;

        public RemoveUserDialog(Context context, int userId,
                                UserSwitcherController userSwitcherController) {
            super(context);
            setTitle(R.string.user_remove_user_title);
            setMessage(context.getString(R.string.user_remove_user_message));
            setButton(DialogInterface.BUTTON_NEUTRAL,
                    context.getString(android.R.string.cancel), this);
            setButton(DialogInterface.BUTTON_POSITIVE,
                    context.getString(R.string.user_remove_user_remove), this);
            setCanceledOnTouchOutside(false);
            mUserId = userId;
            mUserSwitcherController = userSwitcherController;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which == BUTTON_NEUTRAL) {
                cancel();
            } else {
                dismiss();
                mUserSwitcherController.removeUserId(mUserId);
            }
        }
    }
}
