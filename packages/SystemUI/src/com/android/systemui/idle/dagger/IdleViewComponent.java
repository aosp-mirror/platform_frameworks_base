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

package com.android.systemui.idle.dagger;

import com.android.systemui.idle.IdleHostView;
import com.android.systemui.idle.IdleHostViewController;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * Subcomponent for working with {@link IdleHostView}.
 */
@Subcomponent
public interface IdleViewComponent {
    /** Simple factory for {@link Factory}. */
    @Subcomponent.Factory
    interface Factory {
        IdleViewComponent build(@BindsInstance IdleHostView idleHostView);
    }

    /** Builds a {@link IdleHostViewController}. */
    IdleHostViewController getIdleHostViewController();
}
