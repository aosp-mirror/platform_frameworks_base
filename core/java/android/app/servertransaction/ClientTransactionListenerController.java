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

package android.app.servertransaction;

import static com.android.window.flags.Flags.bundleClientTransactionFlag;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.app.ActivityThread;
import android.hardware.display.DisplayManagerGlobal;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Singleton controller to manage listeners to individual {@link ClientTransaction}.
 *
 * @hide
 */
public class ClientTransactionListenerController {

    private static ClientTransactionListenerController sController;

    private final DisplayManagerGlobal mDisplayManager;

    /** Gets the singleton controller. */
    @NonNull
    public static ClientTransactionListenerController getInstance() {
        synchronized (ClientTransactionListenerController.class) {
            if (sController == null) {
                sController = new ClientTransactionListenerController(
                        DisplayManagerGlobal.getInstance());
            }
            return sController;
        }
    }

    /** Creates a new instance for test only. */
    @VisibleForTesting
    @NonNull
    public static ClientTransactionListenerController createInstanceForTesting(
            @NonNull DisplayManagerGlobal displayManager) {
        return new ClientTransactionListenerController(displayManager);
    }

    private ClientTransactionListenerController(@NonNull DisplayManagerGlobal displayManager) {
        mDisplayManager = requireNonNull(displayManager);
    }

    /**
     * Called when receives a {@link ClientTransaction} that is updating display-related
     * window configuration.
     */
    public void onDisplayChanged(int displayId) {
        if (!bundleClientTransactionFlag()) {
            return;
        }
        if (ActivityThread.isSystem()) {
            // Not enable for system server.
            return;
        }
        mDisplayManager.handleDisplayChangeFromWindowManager(displayId);
    }
}
