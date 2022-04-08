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
package android.media.soundtrigger_middleware;

import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;

/**
 * Main entry point into this module.
 *
 * Allows the client to enumerate the available soundtrigger devices and their capabilities, then
 * attach to either one of them in order to use it.
 *
 * {@hide}
 */
interface ISoundTriggerMiddlewareService {
    /**
     * Query the available modules and their capabilities.
     */
    SoundTriggerModuleDescriptor[] listModules();

    /**
     * Attach to one of the available modules.
     * listModules() must be called prior to calling this method and the provided handle must be
     * one of the handles from the returned list.
     */
    ISoundTriggerModule attach(int handle, ISoundTriggerCallback callback);
}