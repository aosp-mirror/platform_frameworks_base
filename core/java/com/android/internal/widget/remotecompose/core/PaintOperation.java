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

/**
 * PaintOperation interface, used for operations aimed at painting
 * (while any operation _can_ paint, this make it a little more explicit)
 */
public abstract class PaintOperation implements Operation {

    @Override
    public void apply(RemoteContext context) {
        if (context.getMode() == RemoteContext.ContextMode.PAINT
                && context.getPaintContext() != null) {
            paint((PaintContext) context.getPaintContext());
        }
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }

    public abstract void paint(PaintContext context);
}
