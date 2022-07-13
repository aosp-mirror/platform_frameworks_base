/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.sysui;

import android.content.res.Configuration;

/**
 * General interface for notifying the Shell of common SysUI events like configuration or keyguard
 * changes.
 *
 * TODO: Move ShellInit and ShellCommandHandler into this interface
 */
public interface ShellInterface {

    /**
     * Notifies the Shell that the configuration has changed.
     */
    default void onConfigurationChanged(Configuration newConfiguration) {}
}
