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
package com.android.internal.widget.remotecompose.core;

import android.annotation.NonNull;

/**
 * PaintOperation interface, used for operations aimed at painting (while any operation _can_ paint,
 * this make it a little more explicit)
 */
public abstract class PaintOperation extends Operation {

    @Override
    public void apply(@NonNull RemoteContext context) {
        if (context.getMode() == RemoteContext.ContextMode.PAINT) {
            PaintContext paintContext = context.getPaintContext();
            if (paintContext != null) {
                paint(paintContext);
            }
        }
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    public abstract void paint(@NonNull PaintContext context);

    /**
     * Will return true if the operation is similar enough to the current one, in the context of an
     * animated transition.
     */
    public boolean suitableForTransition(@NonNull Operation op) {
        // by default expects the op to not be suitable
        return false;
    }
}
