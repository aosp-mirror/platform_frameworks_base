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

package android.view;

import android.annotation.NonNull;

/**
 * A class that provides an {@link OnBackInvokedDispatcher} that allows you to register
 * an {@link OnBackInvokedCallback} for handling the system back invocation behavior.
 */
public interface OnBackInvokedDispatcherOwner {
    /**
     * Returns the {@link OnBackInvokedDispatcher} that should dispatch the back invocation
     * to its registered {@link OnBackInvokedCallback}s.
     * Returns null when the root view is not attached to a window or a view tree with a decor.
     */
    @NonNull
    OnBackInvokedDispatcher getOnBackInvokedDispatcher();
}
