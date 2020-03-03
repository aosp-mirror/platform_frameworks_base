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

package android.view.inline;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;

/**
 * This class represents a view that can hold an opaque content that may be from a different source.
 *
 * @hide
 */
public class InlineContentView extends SurfaceView {
    public InlineContentView(@NonNull Context context,
            @NonNull SurfaceControlViewHost.SurfacePackage surfacePackage) {
        super(context);
        setZOrderOnTop(true);
        setChildSurfacePackage(surfacePackage);
        getHolder().setFormat(PixelFormat.TRANSPARENT);
    }
}
