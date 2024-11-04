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

package com.android.server.wm;

import android.annotation.Nullable;
import android.os.IBinder;
import android.view.WindowInsets;

/**
 * A common parent for {@link InputTarget} and {@link InsetsControlTarget}: Some types (like the
 * {@link EmbeddedWindowController.EmbeddedWindow}) should not be a control target for insets in
 * general, but should be able to request the IME. To archive this, the InsetsTarget contains the
 * minimal information that those interfaces share (and what is needed to show the IME.
 */
public interface InsetsTarget {

    /**
     * @return Client IWindow token for the target.
     */
    @Nullable
    IBinder getWindowToken();

    /**
     * @param types The {@link WindowInsets.Type}s which requestedVisibility status is returned.
     * @return {@code true} if any of the {@link WindowInsets.Type.InsetsType} is requested
     * visible by this target.
     */
    boolean isRequestedVisible(@WindowInsets.Type.InsetsType int types);

    /**
     * @return {@link WindowInsets.Type.InsetsType}s which are requested visible by this target.
     */
    @WindowInsets.Type.InsetsType int getRequestedVisibleTypes();
}
