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

package android.view.inputmethod;

import android.os.CancellationSignal;

import java.util.Set;

/**
 * A {@link HandwritingGesture} that can be
 * {@link InputConnection#previewHandwritingGesture(
 *  PreviewableHandwritingGesture, CancellationSignal) previewed}.
 *
 *  Note: An editor might only implement a subset of gesture previews and declares the supported
 *  ones via {@link EditorInfo#getSupportedHandwritingGesturePreviews}.
 *
 * @see EditorInfo#setSupportedHandwritingGesturePreviews(Set)
 * @see EditorInfo#getSupportedHandwritingGesturePreviews()
 */
public abstract class PreviewableHandwritingGesture extends HandwritingGesture {
    PreviewableHandwritingGesture() {
        // intentionally empty.
    }
}
