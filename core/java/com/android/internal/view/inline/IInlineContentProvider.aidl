/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.view.inline;

import com.android.internal.view.inline.IInlineContentCallback;

/**
 * Binder interface for a process to request the inline content from the other process.
 * {@hide}
 */
oneway interface IInlineContentProvider {
    void provideContent(int width, int height, in IInlineContentCallback callback);
    void requestSurfacePackage();
    void onSurfacePackageReleased();
}
