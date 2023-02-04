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

package androidx.window.extensions.area;

import static androidx.window.extensions.area.WindowAreaComponent.SESSION_STATE_ACTIVE;
import static androidx.window.extensions.area.WindowAreaComponent.SESSION_STATE_INACTIVE;

import android.content.Context;
import android.hardware.devicestate.DeviceStateRequest;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.window.extensions.core.util.function.Consumer;

import java.util.Objects;

/**
 * Controller class that keeps track of the status of the device state request
 * to enable the rear display presentation feature. This controller notifies the session callback
 * when the state request is active, and notifies the callback when the request is canceled.
 *
 * Clients are notified via {@link Consumer} provided with
 * {@link androidx.window.extensions.area.WindowAreaComponent.WindowAreaStatus} values to signify
 * when the request becomes active and cancelled.
 */
class RearDisplayPresentationController implements DeviceStateRequest.Callback {

    private static final String TAG = "RearDisplayPresentationController";

    // Original context that requested to enable rear display presentation mode
    @NonNull
    private final Context mContext;
    @NonNull
    private final Consumer<@WindowAreaComponent.WindowAreaSessionState Integer> mStateConsumer;
    @Nullable
    private ExtensionWindowAreaPresentation mExtensionWindowAreaPresentation;
    @NonNull
    private final DisplayManager mDisplayManager;

    /**
     * Creates the RearDisplayPresentationController
     * @param context Originating {@link android.content.Context} that is initiating the rear
     *                display presentation session.
     * @param stateConsumer {@link Consumer} that will be notified that the session is active when
     *        the device state request is active and the session has been created. If the device
     *        state request is cancelled, the callback will be notified that the session has been
     *        ended. This could occur through a call to cancel the feature or if the device is
     *        manipulated in a way that cancels any device state override.
     */
    RearDisplayPresentationController(@NonNull Context context,
            @NonNull Consumer<@WindowAreaComponent.WindowAreaSessionState Integer> stateConsumer) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(stateConsumer);

        mContext = context;
        mStateConsumer = stateConsumer;
        mDisplayManager = context.getSystemService(DisplayManager.class);
    }

    @Override
    public void onRequestActivated(@NonNull DeviceStateRequest request) {
        Display[] rearDisplays = mDisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_REAR);
        if (rearDisplays.length == 0) {
            mStateConsumer.accept(SESSION_STATE_INACTIVE);
            Log.e(TAG, "Rear display list should not be empty");
            return;
        }

        mExtensionWindowAreaPresentation =
                new RearDisplayPresentation(mContext, rearDisplays[0], mStateConsumer);
        mStateConsumer.accept(SESSION_STATE_ACTIVE);
    }

    @Override
    public void onRequestCanceled(@NonNull DeviceStateRequest request) {
        mStateConsumer.accept(SESSION_STATE_INACTIVE);
    }

    @Nullable
    public ExtensionWindowAreaPresentation getWindowAreaPresentation() {
        return mExtensionWindowAreaPresentation;
    }
}
