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

package android.view.stylus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.test.platform.app.InstrumentationRegistry;

class HandwritingTestUtil {
    static View createView(Rect handwritingArea) {
        return createView(handwritingArea, true /* autoHandwritingEnabled */,
                true /* isStylusHandwritingAvailable */);
    }

    static View createView(Rect handwritingArea, boolean autoHandwritingEnabled,
            boolean isStylusHandwritingAvailable) {
        return createView(handwritingArea, autoHandwritingEnabled, isStylusHandwritingAvailable,
                0, 0, 0, 0);
    }

    static View createView(Rect handwritingArea, boolean autoHandwritingEnabled,
            boolean isStylusHandwritingAvailable,
            float handwritingBoundsOffsetLeft, float handwritingBoundsOffsetTop,
            float handwritingBoundsOffsetRight, float handwritingBoundsOffsetBottom) {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        View view = spy(new View(context));
        mockSpy(view, handwritingArea, autoHandwritingEnabled, isStylusHandwritingAvailable,
                handwritingBoundsOffsetLeft, handwritingBoundsOffsetTop,
                handwritingBoundsOffsetRight, handwritingBoundsOffsetBottom);
        return view;
    }

    static EditText createEditText(Rect handwritingArea, boolean autoHandwritingEnabled,
            boolean isStylusHandwritingAvailable) {
        return createEditText(handwritingArea, autoHandwritingEnabled, isStylusHandwritingAvailable,
                0, 0, 0, 0);
    }

    static EditText createEditText(Rect handwritingArea, boolean autoHandwritingEnabled,
            boolean isStylusHandwritingAvailable,
            float handwritingBoundsOffsetLeft, float handwritingBoundsOffsetTop,
            float handwritingBoundsOffsetRight, float handwritingBoundsOffsetBottom) {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        EditText view = spy(new EditText(context));
        doAnswer(invocation -> {
            int[] outLocation = invocation.getArgument(0);
            outLocation[0] = handwritingArea.left;
            outLocation[1] = handwritingArea.top;
            return null;
        }).when(view).getLocationInWindow(any());
        when(view.getOffsetForPosition(anyFloat(), anyFloat())).thenReturn(0);
        mockSpy(view, handwritingArea, autoHandwritingEnabled, isStylusHandwritingAvailable,
                handwritingBoundsOffsetLeft, handwritingBoundsOffsetTop,
                handwritingBoundsOffsetRight, handwritingBoundsOffsetBottom);
        return view;
    }

    private static void mockSpy(View viewSpy, Rect handwritingArea,
            boolean autoHandwritingEnabled, boolean isStylusHandwritingAvailable,
            float handwritingBoundsOffsetLeft, float handwritingBoundsOffsetTop,
            float handwritingBoundsOffsetRight, float handwritingBoundsOffsetBottom) {
        // Mock a parent so that HandwritingInitiator can get visible rect and hit region.
        final ViewGroup parent = new ViewGroup(viewSpy.getContext()) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                // We don't layout this view.
            }
            @Override
            public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset) {
                r.set(handwritingArea);
                return true;
            }

            @Override
            public boolean getChildLocalHitRegion(View child, Region region, Matrix matrix,
                    boolean isHover) {
                matrix.reset();
                region.set(handwritingArea);
                return true;
            }
        };

        when(viewSpy.isAttachedToWindow()).thenReturn(true);
        when(viewSpy.isAggregatedVisible()).thenReturn(true);
        when(viewSpy.isStylusHandwritingAvailable()).thenReturn(isStylusHandwritingAvailable);
        when(viewSpy.getHandwritingArea()).thenReturn(handwritingArea);
        when(viewSpy.getHandwritingBoundsOffsetLeft()).thenReturn(handwritingBoundsOffsetLeft);
        when(viewSpy.getHandwritingBoundsOffsetTop()).thenReturn(handwritingBoundsOffsetTop);
        when(viewSpy.getHandwritingBoundsOffsetRight()).thenReturn(handwritingBoundsOffsetRight);
        when(viewSpy.getHandwritingBoundsOffsetBottom()).thenReturn(handwritingBoundsOffsetBottom);
        viewSpy.setAutoHandwritingEnabled(autoHandwritingEnabled);
        parent.addView(viewSpy);
    }
}
