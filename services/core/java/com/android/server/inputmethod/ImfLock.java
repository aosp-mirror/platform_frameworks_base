/*
 * Copyright (C) 2021 The Android Open Source Project
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

/**
 * The implicit lock of this class serves as the global lock for
 * the {@link InputMethodManagerService} and its controllers,
 * which contain the main logic of the input method framework (IMF).
 *
 * <p>
 * This lock can be used as follows in code:
 * <pre>
 * synchronized (ImfLock.class) {
 *   ...
 * }
 * </pre>
 *
 * <p>
 * For annotations, you can use a similar syntax:
 * <pre>
 * &#64;GuardedBy("ImfLock.class")
 * myMethodDeclaration() {
 *   ...
 * }
 * </pre>
 */
final class ImfLock {
    private ImfLock() {
        // no instances
    }
}
