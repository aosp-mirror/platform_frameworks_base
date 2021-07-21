/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator;

import androidx.annotation.NonNull;

import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable;

/**
 * Interface for registering callbacks to the {@link NotifPipeline}.
 */
public interface Coordinator {
    /**
     * Called after the NewNotifPipeline is initialized.
     * Coordinators should register their listeners and {@link Pluggable}s to the pipeline.
     */
    void attach(@NonNull NotifPipeline pipeline);
}
