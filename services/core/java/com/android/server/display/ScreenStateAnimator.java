/*
 * Copyright (C) 2018 The Dirty Unicorns Project
 * Copyright (C) 2022 The Potato Open Sauce Project
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

package com.android.server.display;

import java.io.PrintWriter;

import android.content.Context;

/** @hide */
public interface ScreenStateAnimator {
    public static final int MODE_WARM_UP = 0;
    public static final int MODE_COOL_DOWN = 1;
    public static final int MODE_FADE = 2;
    public static final int MODE_SCALE_DOWN = 3;

    public boolean prepare(Context context, int mode);

    public default void dismissResources() {}

    public void dismiss();

    public boolean draw(float level);

    public void dump(PrintWriter pw);
}
