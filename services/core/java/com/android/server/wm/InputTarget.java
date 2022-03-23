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

package com.android.server.wm;

import android.view.IWindow;

/**
 * Common interface between focusable objects.
 *
 * Both WindowState and EmbeddedWindows can receive input. This consolidates some common properties
 * of both targets.
 */
interface InputTarget {
    /* Get the WindowState associated with the target. */
    WindowState getWindowState();

    /* Display id of the target. */
    int getDisplayId();

    /* Client IWindow for the target. */
    IWindow getIWindow();

    /* Owning pid of the target. */
    int getPid();
}

