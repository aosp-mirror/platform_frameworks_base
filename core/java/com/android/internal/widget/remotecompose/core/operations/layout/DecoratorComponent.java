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
package com.android.internal.widget.remotecompose.core.operations.layout;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.RemoteContext;

/**
 * Indicates a lightweight component (without children) that is only laid out and not able to be
 * measured. Eg borders, background, clips, etc.
 */
public interface DecoratorComponent {
    /**
     * Layout the decorator
     *
     * @param context
     * @param component the associated component
     * @param width horizontal dimension in pixels
     * @param height vertical dimension in pixels
     */
    void layout(@NonNull RemoteContext context, Component component, float width, float height);
}
