/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.dagger.pip;

import android.annotation.NonNull;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.pip.PipBoundsAlgorithm;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.dagger.WMShellBaseModule;
import com.android.wm.shell.dagger.WMSingleton;
import com.android.wm.shell.pip2.PipTransition;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import dagger.Module;
import dagger.Provides;

/**
 * Provides dependencies from {@link com.android.wm.shell.pip2}, this implementation is meant to be
 * the successor of its sibling {@link Pip1Module}.
 */
@Module(includes = WMShellBaseModule.class)
public abstract class Pip2Module {
    @WMSingleton
    @Provides
    static PipTransition providePipTransition(@NonNull ShellInit shellInit,
            @NonNull ShellTaskOrganizer shellTaskOrganizer,
            @NonNull Transitions transitions,
            PipBoundsState pipBoundsState,
            PipBoundsAlgorithm pipBoundsAlgorithm) {
        return new PipTransition(shellInit, shellTaskOrganizer, transitions, pipBoundsState, null,
                pipBoundsAlgorithm);
    }
}
