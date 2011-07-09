/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.recent;

public class Constants {
    static final int MAX_ESCAPE_ANIMATION_DURATION = 500; // in ms
    static final int SNAP_BACK_DURATION = 250; // in ms
    static final int ESCAPE_VELOCITY = 100; // speed of item required to "curate" it in dp/s
    public static float ALPHA_FADE_START = 0.8f; // fraction of thumbnail width where fade starts
    static final float ALPHA_FADE_END = 0.5f; // fraction of thumbnail width beyond which alpha->0
}
