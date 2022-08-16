/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import android.view.InsetsSource;

/**
 * Controller for a specific inset source on the server. It's called provider as it provides the
 * {@link InsetsSource} to the client that uses it in {@link android.view.InsetsSourceConsumer}.
 */
class WindowContainerInsetsSourceProvider extends InsetsSourceProvider {
    // TODO(b/218734524): Move the window container specific stuff from InsetsSourceProvider to
    //  this class.

    WindowContainerInsetsSourceProvider(InsetsSource source,
            InsetsStateController stateController, DisplayContent displayContent) {
        super(source, stateController, displayContent);
    }
}

