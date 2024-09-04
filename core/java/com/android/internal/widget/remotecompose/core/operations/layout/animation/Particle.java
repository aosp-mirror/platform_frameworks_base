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
package com.android.internal.widget.remotecompose.core.operations.layout.animation;

public class Particle {
    public final float x;
    public final float y;
    public float radius;
    public float r;
    public float g;
    public float b;

    public Particle(float x, float y, float radius, float r, float g, float b) {
        this.x = x;
        this.y = y;
        this.radius = radius;
        this.r = r;
        this.g = g;
        this.b = b;
    }
}
