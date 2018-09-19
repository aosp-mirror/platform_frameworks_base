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
 * limitations under the License
 */

package com.android.systemui.biometrics;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.android.systemui.R;

/**
 * This class loads the view for the system-provided dialog. The view consists of:
 * Application Icon, Title, Subtitle, Description, Fingerprint Icon, Error/Help message area,
 * and positive/negative buttons.
 */
public class FaceDialogView extends BiometricDialogView {
    public FaceDialogView(Context context,
            DialogViewCallback callback) {
        super(context, callback);
    }

    @Override
    protected int getHintStringResourceId() {
        return R.string.face_dialog_looking_for_face;
    }

    @Override
    protected int getAuthenticatedAccessibilityResourceId() {
        if (mRequireConfirmation) {
            return com.android.internal.R.string.face_authenticated_confirmation_required;
        } else {
            return com.android.internal.R.string.face_authenticated_no_confirmation_required;
        }
    }

    @Override
    protected int getIconDescriptionResourceId() {
        return R.string.accessibility_face_dialog_face_icon;
    }

    @Override
    protected void updateIcon(int lastState, int newState) {
        Drawable icon = mContext.getDrawable(R.drawable.face_dialog_icon);

        final ImageView faceIcon = getLayout().findViewById(R.id.biometric_icon);
        faceIcon.setImageDrawable(icon);
    }
}
