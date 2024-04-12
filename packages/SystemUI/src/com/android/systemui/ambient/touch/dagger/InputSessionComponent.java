/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.ambient.touch.dagger;

import static com.android.systemui.ambient.touch.dagger.AmbientTouchModule.INPUT_SESSION_NAME;
import static com.android.systemui.ambient.touch.dagger.AmbientTouchModule.PILFER_ON_GESTURE_CONSUME;

import android.view.GestureDetector;

import com.android.systemui.ambient.touch.InputSession;
import com.android.systemui.shared.system.InputChannelCompat;

import dagger.BindsInstance;
import dagger.Subcomponent;

import javax.inject.Named;

/**
 * {@link InputSessionComponent} generates {@link InputSession} with specific instances bound for
 * the session name and whether touches should be pilfered when consumed.
 */
@Subcomponent(
        modules = { InputSessionModule.class }
)
public interface InputSessionComponent {
    /**
     * Generates {@link InputSessionComponent}.
     */
    @Subcomponent.Factory
    interface Factory {
        /** */
        InputSessionComponent create(@Named(INPUT_SESSION_NAME) @BindsInstance String name,
                @BindsInstance InputChannelCompat.InputEventListener inputEventListener,
                @BindsInstance GestureDetector.OnGestureListener gestureListener,
                @Named(PILFER_ON_GESTURE_CONSUME) @BindsInstance boolean pilferOnGestureConsume);
    }

    /** */
    InputSession getInputSession();
}
