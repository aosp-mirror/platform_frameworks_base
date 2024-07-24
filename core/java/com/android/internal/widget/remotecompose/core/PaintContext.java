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
 * Specify an abstract paint context used by RemoteCompose commands to draw
 */
public abstract class PaintContext {
    protected RemoteContext mContext;

    public PaintContext(RemoteContext context) {
        this.mContext = context;
    }

    public void setContext(RemoteContext context) {
        this.mContext = context;
    }

    public abstract void drawBitmap(int imageId,
                             int srcLeft, int srcTop, int srcRight, int srcBottom,
                             int dstLeft, int dstTop, int dstRight, int dstBottom,
                             int cdId);

    public abstract void scale(float scaleX, float scaleY);
    public abstract void translate(float translateX, float translateY);
}

