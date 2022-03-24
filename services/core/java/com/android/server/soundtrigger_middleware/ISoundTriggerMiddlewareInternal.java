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

package com.android.server.soundtrigger_middleware;

import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;

/**
 * This interface closely follows ISoundTriggerMiddlewareService with some subtle changes for
 * convenience.
 *
 * The ISoundTriggerMiddlewareService have been modified to exclude identity information and the
 * RemoteException signature, both of which are only relevant at the service boundary layer.
 */
public interface ISoundTriggerMiddlewareInternal {
    /**
     * Query the available modules and their capabilities.
     */
    public SoundTriggerModuleDescriptor[] listModules();

    /**
     * Attach to one of the available modules.
     *
     * listModules() must be called prior to calling this method and the provided handle must be
     * one of the handles from the returned list.
     */
    public ISoundTriggerModule attach(int handle,
            ISoundTriggerCallback callback);
}
