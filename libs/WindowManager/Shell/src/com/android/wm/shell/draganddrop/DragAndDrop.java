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

package com.android.wm.shell.draganddrop;

import android.content.res.Configuration;

import com.android.wm.shell.common.annotations.ExternalThread;

/**
 * Interface for telling DragAndDrop stuff.
 */
@ExternalThread
public interface DragAndDrop {

    /** Called when the theme changes. */
    void onThemeChanged();

    /** Called when the configuration changes. */
    void onConfigChanged(Configuration newConfig);
}
