/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.app;

import android.hardware.soundtrigger.SoundTrigger;
import android.media.permission.Identity;
import android.media.soundtrigger_middleware.ISoundTriggerInjection;
import com.android.internal.app.ISoundTriggerSession;

/**
 * Service interface for a generic sound recognition model.
 *
 * This interface serves as an entry point to establish a session, associated with a client
 * identity, which exposes the actual functionality.
 *
 * @hide
 */
interface ISoundTriggerService {
    /**
     * Creates a new session.
     *
     * This version is intended to be used when the caller itself is the originator of the
     * operations, for authorization purposes.
     *
     * The pid/uid fields are ignored and will be replaced by those provided by binder.
     *
     * It is good practice to clear the binder calling identity prior to calling this, in case the
     * caller is ever in the same process as the callee.
     *
     * The binder object being passed is used by the server to keep track of client death, in order
     * to clean-up whenever that happens.
     */
    ISoundTriggerSession attachAsOriginator(in Identity originatorIdentity,
                                            in SoundTrigger.ModuleProperties moduleProperties,
                                            IBinder client);

    /**
     * Creates a new session.
     *
     * This version is intended to be used when the caller is acting on behalf of a separate entity
     * (the originator) and the sessions operations are to be accounted against that originator for
     * authorization purposes.
     *
     * The caller must hold the SOUNDTRIGGER_DELEGATE_IDENTITY permission in order to be trusted to
     * provide a reliable originator identity. It should follow the best practices for reliably and
     * securely verifying the identity of the originator.
     *
     * It is good practice to clear the binder calling identity prior to calling this, in case the
     * caller is ever in the same process as the callee.
     *
     * The binder object being passed is used by the server to keep track of client death, in order
     * to clean-up whenever that happens.
     */
    ISoundTriggerSession attachAsMiddleman(in Identity middlemanIdentity,
                                           in Identity originatorIdentity,
                                           in SoundTrigger.ModuleProperties moduleProperties,
                                           IBinder client);

    /**
     * Get available underlying SoundTrigger modules to attach to.
     */
    List<SoundTrigger.ModuleProperties> listModuleProperties(in Identity originatorIdentity);

    /**
     * Attach an HAL injection interface.
     */
     void attachInjection(ISoundTriggerInjection injection);

    /**
     * Test API to override the phone call state.
     */
     void setInPhoneCallState(boolean isInPhoneCall);

}
