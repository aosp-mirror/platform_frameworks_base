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

import android.app.Presentation;
import android.content.Context;
import android.view.Display;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.window.extensions.core.util.function.Consumer;

/**
 * {@link Presentation} object that is used to present extra content
 * on the rear facing display when in a rear display presentation feature.
 */
class RearDisplayPresentation extends Presentation implements ExtensionWindowAreaPresentation {

    @NonNull
    private final Consumer<@WindowAreaComponent.WindowAreaSessionState Integer> mStateConsumer;

    RearDisplayPresentation(@NonNull Context outerContext, @NonNull Display display,
            @NonNull Consumer<@WindowAreaComponent.WindowAreaSessionState Integer> stateConsumer) {
        super(outerContext, display);
        mStateConsumer = stateConsumer;
    }

    /**
     * {@code mStateConsumer} is notified that their content is now visible when the
     * {@link Presentation} object is started. There is no comparable callback for
     * {@link WindowAreaComponent#SESSION_STATE_CONTENT_INVISIBLE} in {@link #onStop()} due to the
     * timing of when a {@link android.hardware.devicestate.DeviceStateRequest} is cancelled
     * ending rear display presentation mode happening before the {@link Presentation} is stopped.
     */
    @Override
    protected void onStart() {
        super.onStart();
        mStateConsumer.accept(WindowAreaComponent.SESSION_STATE_CONTENT_VISIBLE);
    }

    @NonNull
    @Override
    public Context getPresentationContext() {
        return getContext();
    }

    @Override
    public void setPresentationView(View view) {
        setContentView(view);
        if (!isShowing()) {
            show();
        }
    }
}
