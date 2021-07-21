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

import android.media.permission.Identity;
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
     *
     * This variant is intended for use by the originator of the operations for permission
     * enforcement purposes. The provided identity's uid/pid fields will be ignored and overridden
     * by the ones provided by Binder.getCallingUid() / Binder.getCallingPid().
     */
    SoundTriggerModuleDescriptor[] listModulesAsOriginator(in Identity identity);

    /**
     * Query the available modules and their capabilities.
     *
     * This variant is intended for use by a trusted "middleman", acting on behalf of some identity
     * other than itself. The caller must provide:
     * - Its own identity, which will be used to establish trust via the
     *   SOUNDTRIGGER_DELEGATE_IDENTITY permission. This identity's uid/pid fields will be ignored
     *   and overridden by the ones provided by Binder.getCallingUid() / Binder.getCallingPid().
     *   This implies that the caller must clear its caller identity to protect from the case where
     *   it resides in the same process as the callee.
     * - The identity of the entity on behalf of which module operations are to be performed.
     */
    SoundTriggerModuleDescriptor[] listModulesAsMiddleman(in Identity middlemanIdentity,
                                                          in Identity originatorIdentity);

    /**
     * Attach to one of the available modules.
     *
     * This variant is intended for use by the originator of the operations for permission
     * enforcement purposes. The provided identity's uid/pid fields will be ignored and overridden
     * by the ones provided by Binder.getCallingUid() / Binder.getCallingPid().
     *
     * listModules() must be called prior to calling this method and the provided handle must be
     * one of the handles from the returned list.
     */
    ISoundTriggerModule attachAsOriginator(int handle,
                                           in Identity identity,
                                           ISoundTriggerCallback callback);

    /**
     * Attach to one of the available modules.
     *
     * This variant is intended for use by a trusted "middleman", acting on behalf of some identity
     * other than itself. The caller must provide:
     * - Its own identity, which will be used to establish trust via the
     *   SOUNDTRIGGER_DELEGATE_IDENTITY permission. This identity's uid/pid fields will be ignored
     *   and overridden by the ones provided by Binder.getCallingUid() / Binder.getCallingPid().
     *   This implies that the caller must clear its caller identity to protect from the case where
     *   it resides in the same process as the callee.
     * - The identity of the entity on behalf of which module operations are to be performed.
     *
     * listModules() must be called prior to calling this method and the provided handle must be
     * one of the handles from the returned list.
     */
    ISoundTriggerModule attachAsMiddleman(int handle,
                                          in Identity middlemanIdentity,
                                          in Identity originatorIdentity,
                                          ISoundTriggerCallback callback);
}
