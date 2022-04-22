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

package com.android.wm.shell.common;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.text.TextUtils;
import android.view.View;

import com.android.internal.jank.InteractionJankMonitor;

/** Utils class for simplfy InteractionJank trancing call */
public class InteractionJankMonitorUtils {

    /**
     * Begin a trace session.
     *
     * @param cujType the specific {@link InteractionJankMonitor.CujType}.
     * @param view the view to trace
     * @param tag the tag to distinguish different flow of same type CUJ.
     */
    public static void beginTracing(@InteractionJankMonitor.CujType int cujType,
            @NonNull View view, @Nullable String tag) {
        final InteractionJankMonitor.Configuration.Builder builder =
                InteractionJankMonitor.Configuration.Builder.withView(cujType, view);
        if (!TextUtils.isEmpty(tag)) {
            builder.setTag(tag);
        }
        InteractionJankMonitor.getInstance().begin(builder);
    }

    /**
     * End a trace session.
     *
     * @param cujType the specific {@link InteractionJankMonitor.CujType}.
     */
    public static void endTracing(@InteractionJankMonitor.CujType int cujType) {
        InteractionJankMonitor.getInstance().end(cujType);
    }

    /**
     * Cancel the trace session.
     *
     * @param cujType the specific {@link InteractionJankMonitor.CujType}.
     */
    public static void cancelTracing(@InteractionJankMonitor.CujType int cujType) {
        InteractionJankMonitor.getInstance().cancel(cujType);
    }
}
