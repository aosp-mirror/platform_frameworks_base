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

package com.android.systemui.shared.system;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.view.SurfaceView;

/** Utility class that is shared between SysUI and Launcher for Universal Smartspace features. */
public final class UniversalSmartspaceUtils {
    public static final String ACTION_REQUEST_SMARTSPACE_VIEW =
            "com.android.systemui.REQUEST_SMARTSPACE_VIEW";

    private static final String SYSUI_PACKAGE = "com.android.systemui";
    private static final String INTENT_KEY_INPUT_BUNDLE = "input_bundle";
    private static final String BUNDLE_KEY_INPUT_TOKEN = "input_token";
    private static final String INTENT_KEY_SURFACE_CONTROL = "surface_control";

    /** Creates an intent to request that sysui draws the Smartspace to the SurfaceView. */
    public static Intent createRequestSmartspaceIntent(SurfaceView surfaceView) {
        Intent intent = new Intent(ACTION_REQUEST_SMARTSPACE_VIEW);

        Bundle inputBundle = new Bundle();
        inputBundle.putBinder(BUNDLE_KEY_INPUT_TOKEN, surfaceView.getInputToken());
        return intent
                .putExtra(INTENT_KEY_SURFACE_CONTROL, surfaceView.getSurfaceControl())
                .putExtra(INTENT_KEY_INPUT_BUNDLE, inputBundle)
                .setPackage(SYSUI_PACKAGE)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);
    }

    /**
     * Retrieves the SurfaceControl from an Intent created by
     * {@link #createRequestSmartspaceIntent(SurfaceView)}.
     **/
    public static SurfaceControl getSurfaceControl(Intent intent) {
        return intent.getParcelableExtra(INTENT_KEY_SURFACE_CONTROL);
    }

    /**
     * Retrieves the input token from an Intent created by
     * {@link #createRequestSmartspaceIntent(SurfaceView)}.
     **/
    public static IBinder getInputToken(Intent intent) {
        Bundle inputBundle = intent.getBundleExtra(INTENT_KEY_INPUT_BUNDLE);
        return inputBundle == null ? null : inputBundle.getBinder(BUNDLE_KEY_INPUT_TOKEN);
    }

    private UniversalSmartspaceUtils() {}
}
