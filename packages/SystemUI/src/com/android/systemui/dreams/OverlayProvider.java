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

package com.android.systemui.dreams;

import android.content.Context;

/**
 * {@link OverlayProvider} is an interface for defining entities that can supply overlays to show
 * over a dream. Presentation components such as the {@link DreamOverlayService} supply
 * implementations with the necessary context for constructing such overlays.
 */
public interface OverlayProvider {
    /**
     * Called when the {@link OverlayHost} requests the associated overlay be produced.
     *
     * @param context The {@link Context} used to construct the view.
     * @param creationCallback The callback to inform when the overlay has been created.
     * @param interactionCallback The callback to inform when the overlay has been interacted with.
     */
    void onCreateOverlay(Context context, OverlayHost.CreationCallback creationCallback,
            OverlayHost.InteractionCallback interactionCallback);
}
