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

package com.android.systemui.shared.communal;

import com.android.systemui.shared.communal.ICommunalSurfaceCallback;

/**
 * An interface, implemented by clients of CommunalHost, to provide communal surfaces for SystemUI.
 * The associated binder proxy will be retained by SystemUI and called on-demand when a communal
 * surface is needed (either new instantiation or update).
 */
oneway interface ICommunalSource {
    /**
     * Called by the CommunalHost when a new communal surface is needed. The provided arguments
     * match the arguments necessary to construct a SurfaceControlViewHost for producing a
     * SurfacePackage to return.
     */
    void getCommunalSurface(in IBinder hostToken, in int width, in int height, in int displayId,
        in ICommunalSurfaceCallback callback) = 1;
}