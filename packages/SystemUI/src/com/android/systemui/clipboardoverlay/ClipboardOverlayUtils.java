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

package com.android.systemui.clipboardoverlay;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;

import com.android.systemui.R;

import javax.inject.Inject;

class ClipboardOverlayUtils {

    @Inject
    ClipboardOverlayUtils() {
    }

    boolean isRemoteCopy(Context context, ClipData clipData, String clipSource) {
        if (clipData != null && clipData.getDescription().getExtras() != null
                && clipData.getDescription().getExtras().getBoolean(
                ClipDescription.EXTRA_IS_REMOTE_DEVICE)) {
            ComponentName remoteComponent = ComponentName.unflattenFromString(
                    context.getResources().getString(R.string.config_remoteCopyPackage));
            if (remoteComponent != null) {
                return remoteComponent.getPackageName().equals(clipSource);
            }
        }
        return false;
    }
}
