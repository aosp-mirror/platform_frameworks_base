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
package com.android.internal.widget.remotecompose.core.operations.layout.modifiers;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.operations.DrawBase4;
import com.android.internal.widget.remotecompose.core.operations.layout.DecoratorComponent;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

/**
 * Support clip with a rectangle
 */
public class RoundedClipRectModifierOperation extends DrawBase4
        implements ModifierOperation, DecoratorComponent {

    public static final Companion COMPANION =
            new Companion(Operations.MODIFIER_ROUNDED_CLIP_RECT) {
                @Override
                public Operation construct(float x1,
                                           float y1,
                                           float x2,
                                           float y2) {
                    return new RoundedClipRectModifierOperation(x1, y1, x2, y2);
                }
            };
    float mWidth;
    float mHeight;


    public RoundedClipRectModifierOperation(
            float topStart,
            float topEnd,
            float bottomStart,
            float bottomEnd) {
        super(topStart, topEnd, bottomStart, bottomEnd);
        mName = "ModifierRoundedClipRect";
    }

    @Override
    public void paint(PaintContext context) {
        context.roundedClipRect(mWidth, mHeight, mX1, mY1, mX2, mY2);
    }

    @Override
    public void layout(RemoteContext context, float width, float height) {
        this.mWidth = width;
        this.mHeight = height;
    }

    @Override
    public void onClick(float x, float y) {
        // nothing
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(
                indent, "ROUND_CLIP = [" + mWidth + ", " + mHeight
                        + ", " + mX1 + ", " + mY1
                        + ", " + mX2 + ", " + mY2 + "]");
    }
}
