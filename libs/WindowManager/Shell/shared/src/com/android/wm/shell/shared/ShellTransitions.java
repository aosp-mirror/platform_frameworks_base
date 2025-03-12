/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.shared;

import android.annotation.NonNull;
import android.window.RemoteTransition;
import android.window.TransitionFilter;

import com.android.wm.shell.shared.annotations.ExternalThread;

import java.util.concurrent.Executor;

/**
 * Interface to manage remote transitions.
 */
@ExternalThread
public interface ShellTransitions {
    /**
     * Registers a remote transition for all operations excluding takeovers (see
     * {@link ShellTransitions#registerRemoteForTakeover(TransitionFilter, RemoteTransition)}).
     */
    default void registerRemote(@NonNull TransitionFilter filter,
            @NonNull RemoteTransition remoteTransition) {}

    /**
     * Registers a remote transition for takeover operations only.
     */
    default void registerRemoteForTakeover(@NonNull TransitionFilter filter,
            @NonNull RemoteTransition remoteTransition) {}

    /**
     * Unregisters a remote transition for all operations.
     */
    default void unregisterRemote(@NonNull RemoteTransition remoteTransition) {}

    /**
     * Sets listener that will receive callbacks about transitions involving focus switch.
     */
    default void setFocusTransitionListener(@NonNull FocusTransitionListener listener,
            Executor executor) {}

    /**
     * Unsets listener that will receive callbacks about transitions involving focus switch.
     */
    default void unsetFocusTransitionListener(@NonNull FocusTransitionListener listener) {}
}
