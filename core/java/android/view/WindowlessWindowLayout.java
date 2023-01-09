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

package android.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.WindowConfiguration.WindowingMode;
import android.graphics.Rect;
import android.view.WindowInsets.Type.InsetsType;
import android.window.ClientWindowFrames;

// TODO(b/262891212) use the real WindowLayout and remove WindowlessWindowLayout

/**
 * Computes window frames for the windowless window.
 *
 * @hide
 */
public class WindowlessWindowLayout extends WindowLayout {

    @Override
    public void computeFrames(WindowManager.LayoutParams attrs, InsetsState state,
            Rect displayCutoutSafe, Rect windowBounds, @WindowingMode int windowingMode,
            int requestedWidth, int requestedHeight, @InsetsType int requestedVisibleTypes,
            float compatScale, ClientWindowFrames frames) {
        if (frames.attachedFrame == null) {
            frames.frame.set(0, 0, attrs.width, attrs.height);
            frames.parentFrame.set(frames.frame);
            frames.displayFrame.set(frames.frame);
            return;
        }

        final int height = calculateLength(attrs.height, requestedHeight,
                frames.attachedFrame.height());
        final int width = calculateLength(attrs.width, requestedWidth,
                frames.attachedFrame.width());
        Gravity.apply(attrs.gravity, width, height, frames.attachedFrame,
                (int) (attrs.x + attrs.horizontalMargin),
                (int) (attrs.y + attrs.verticalMargin),
                frames.frame);
        frames.displayFrame.set(frames.frame);
        frames.parentFrame.set(frames.attachedFrame);
    }

    private static int calculateLength(int attrLength, int requestedLength, int parentLength) {
        if (attrLength == MATCH_PARENT) {
            return parentLength;
        }
        if (attrLength == WRAP_CONTENT) {
            return requestedLength;
        }
        return attrLength;
    }
}
