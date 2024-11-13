/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.InputMethodSubtypeHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class HardwareKeyboardShortcutController {
    @GuardedBy("ImfLock.class")
    private final ArrayList<InputMethodSubtypeHandle> mSubtypeHandles = new ArrayList<>();

    HardwareKeyboardShortcutController() {
    }

    @GuardedBy("ImfLock.class")
    void update(@NonNull InputMethodSettings settings) {
        mSubtypeHandles.clear();
        final List<InputMethodInfo> inputMethods = settings.getEnabledInputMethodList();
        for (int i = 0; i < inputMethods.size(); ++i) {
            final InputMethodInfo imi = inputMethods.get(i);
            if (!imi.shouldShowInInputMethodPicker()) {
                continue;
            }
            final List<InputMethodSubtype> subtypes =
                    settings.getEnabledInputMethodSubtypeList(imi, true);
            if (subtypes.isEmpty()) {
                mSubtypeHandles.add(InputMethodSubtypeHandle.of(imi, null));
            } else {
                for (final InputMethodSubtype subtype : subtypes) {
                    if (subtype.isSuitableForPhysicalKeyboardLayoutMapping()) {
                        mSubtypeHandles.add(InputMethodSubtypeHandle.of(imi, subtype));
                    }
                }
            }
        }
    }

    @AnyThread
    @Nullable
    static <T> T getNeighborItem(@NonNull List<T> list, @NonNull T value, boolean next) {
        final int size = list.size();
        for (int i = 0; i < size; ++i) {
            if (Objects.equals(value, list.get(i))) {
                final int nextIndex = (i + (next ? 1 : -1) + size) % size;
                return list.get(nextIndex);
            }
        }
        return null;
    }

    @GuardedBy("ImfLock.class")
    @Nullable
    InputMethodSubtypeHandle onSubtypeSwitch(
            @NonNull InputMethodSubtypeHandle currentImeAndSubtype, boolean forward) {
        return getNeighborItem(mSubtypeHandles, currentImeAndSubtype, forward);
    }
}
