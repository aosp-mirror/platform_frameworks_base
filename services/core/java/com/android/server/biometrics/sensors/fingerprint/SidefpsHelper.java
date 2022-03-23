/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint;

import android.annotation.Nullable;
import android.hardware.fingerprint.ISidefpsController;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Contains helper methods for side-fps fingerprint controller.
 */
public class SidefpsHelper {
    private static final String TAG = "SidefpsHelper";

    /**
     * Shows the side-fps affordance
     * @param sidefpsController controller that shows and hides the side-fps affordance
     */
    public static void showOverlay(@Nullable ISidefpsController sidefpsController) {
        if (sidefpsController == null) {
            return;
        }

        try {
            sidefpsController.show();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when showing the side-fps overlay", e);
        }
    }

    /**
     * Hides the side-fps affordance
     * @param sidefpsController controller that shows and hides the side-fps affordance
     */
    public static void hideOverlay(@Nullable ISidefpsController sidefpsController) {
        if (sidefpsController == null) {
            return;
        }
        try {
            sidefpsController.hide();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when hiding the side-fps overlay", e);
        }
    }
}
