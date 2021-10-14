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

package android.media.permission;

import android.annotation.NonNull;

/**
 * A composite {@link SafeCloseable}. Will close its children in reverse order.
 *
 * @hide
 */
class CompositeSafeCloseable implements SafeCloseable {
    private final @NonNull SafeCloseable[] mChildren;

    CompositeSafeCloseable(@NonNull SafeCloseable... children) {
        mChildren = children;
    }

    @Override
    public void close() {
        // Close in reverse order.
        for (int i = mChildren.length - 1; i >= 0; --i) {
            mChildren[i].close();
        }
    }
}
