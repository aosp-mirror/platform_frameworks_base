/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view;

import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.BaseRecordingCanvas;
import android.graphics.CanvasProperty;
import android.graphics.Paint;

/**
 * This class exists temporarily to workaround broken apps
 *
 * b/119066174
 *
 * @hide
 */
public abstract class DisplayListCanvas extends BaseRecordingCanvas {

    /** @hide */
    protected DisplayListCanvas(long nativeCanvas) {
        super(nativeCanvas);
    }

    /**
     * TODO: Public API alternative
     * @hide
     */
    @UnsupportedAppUsage
    public abstract void drawRoundRect(CanvasProperty<Float> left, CanvasProperty<Float> top,
            CanvasProperty<Float> right, CanvasProperty<Float> bottom, CanvasProperty<Float> rx,
            CanvasProperty<Float> ry, CanvasProperty<Paint> paint);

    /**
     * TODO: Public API alternative
     * @hide
     */
    @UnsupportedAppUsage
    public abstract void drawCircle(CanvasProperty<Float> cx, CanvasProperty<Float> cy,
            CanvasProperty<Float> radius, CanvasProperty<Paint> paint);
}
