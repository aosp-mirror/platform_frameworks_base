/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.autofill.ui;

import static com.android.server.autofill.Helper.sDebug;

import android.annotation.UserIdInt;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.UserManager;
import android.view.Display;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.utils.Slogf;

/**
 * Helper for display-related needs.
 */
final class DisplayHelper {

    private static final String TAG = "AutofillDisplayHelper";

    private static final UserManagerInternal sUmi = LocalServices
            .getService(UserManagerInternal.class);

    /**
     * Gets a context with the proper display id set for the given user.
     *
     * <p>For most cases it will return the provided context, but on devices that
     * {@link UserManager#isVisibleBackgroundUsersEnabled() support visible background users}, it
     * will return a context with the display the user started visible on.
     */
    static Context getDisplayContext(Context context, @UserIdInt int userId) {
        if (!UserManager.isVisibleBackgroundUsersEnabled()) {
            return context;
        }
        int displayId = sUmi.getMainDisplayAssignedToUser(userId);
        if (sDebug) {
            Slogf.d(TAG, "Creating context for display %d for user %d", displayId, userId);
        }
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null) {
            Slogf.wtf(TAG, "Could not get display with id %d (which is associated with user %d; "
                    + "FillUi operations will probably fail", displayId, userId);
            return context;
        }

        return context.createDisplayContext(display);
    }

    private DisplayHelper() {
        throw new UnsupportedOperationException("Contains only static methods");
    }
}
