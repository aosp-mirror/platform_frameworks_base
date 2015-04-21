/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.internal.transition;

import android.animation.TimeInterpolator;
import android.view.animation.PathInterpolator;

class TransitionConstants {
    static final TimeInterpolator LINEAR_OUT_SLOW_IN = new PathInterpolator(0, 0, 0.2f, 1);
    static final TimeInterpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0, 0.2f, 1);
}
