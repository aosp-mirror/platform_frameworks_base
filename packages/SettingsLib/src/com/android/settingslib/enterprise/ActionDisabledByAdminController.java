/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.enterprise;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog.Builder;

import com.android.settingslib.RestrictedLockUtils;

/**
 * A controller used to customize the action disabled by admin dialog.
 */
public interface ActionDisabledByAdminController {

    /**
     * Handles the adding and setting up of the learn more button. If button is not needed, then
     * this method can be left empty.
     */
    void setupLearnMoreButton(Activity activity, Builder builder);

    /**
     * Returns the admin support dialog's title resource id.
     */
    String getAdminSupportTitle(@Nullable String restriction);

    /**
     * Returns the admin support dialog's content string.
     */
    CharSequence getAdminSupportContentString(
            Context context, @Nullable CharSequence supportMessage);

    /**
     * Updates the enforced admin
     */
    void updateEnforcedAdmin(RestrictedLockUtils.EnforcedAdmin admin, int adminUserId);
}
