/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.am;

import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.internal.R;
import com.android.server.pm.UserManagerService;
import java.io.FileDescriptor;

/**
 * Dialog to show when a user switch it about to happen for the car. The intent is to snapshot the
 * screen immediately after the dialog shows so that the user is informed that something is
 * happening in the background rather than just freeze the screen and not know if the user-switch
 * affordance was being handled.
 *
 */
final class CarUserSwitchingDialog extends UserSwitchingDialog {
    private static final String TAG = "ActivityManagerCarUserSwitchingDialog";

    public CarUserSwitchingDialog(ActivityManagerService service, Context context, UserInfo oldUser,
        UserInfo newUser, boolean aboveSystem, String switchingFromSystemUserMessage,
        String switchingToSystemUserMessage) {
        super(service, context, oldUser, newUser, aboveSystem, switchingFromSystemUserMessage,
            switchingToSystemUserMessage);

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    @Override
    void inflateContent() {
        // Set up the dialog contents
        setCancelable(false);
        Resources res = getContext().getResources();
        // Custom view due to alignment and font size requirements
        View view = LayoutInflater.from(getContext()).inflate(R.layout.car_user_switching_dialog,
            null);

        FileDescriptor fileDescriptor = UserManagerService.getInstance()
                .getUserIcon(mNewUser.id).getFileDescriptor();
        Bitmap bitmap = BitmapFactory.decodeFileDescriptor(fileDescriptor);
        ((ImageView) view.findViewById(R.id.user_loading_avatar))
            .setImageBitmap(bitmap);
        ((TextView) view.findViewById(R.id.user_loading))
            .setText(res.getString(R.string.car_loading_profile));
        setView(view);
    }
}
