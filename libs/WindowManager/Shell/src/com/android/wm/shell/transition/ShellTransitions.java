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

package com.android.wm.shell.transition;

import android.annotation.NonNull;
import android.window.IRemoteTransition;
import android.window.TransitionFilter;

import com.android.wm.shell.common.annotations.ExternalThread;

/**
 * Interface to manage remote transitions.
 */
@ExternalThread
public interface ShellTransitions {

    /**
     * Returns a binder that can be passed to an external process to manipulate remote transitions.
     */
    default IShellTransitions createExternalInterface() {
        return null;
    }

    /**
     * Registers a remote transition.
     */
    void registerRemote(@NonNull TransitionFilter filter,
            @NonNull IRemoteTransition remoteTransition);

    /**
     * Unregisters a remote transition.
     */
    void unregisterRemote(@NonNull IRemoteTransition remoteTransition);
}
