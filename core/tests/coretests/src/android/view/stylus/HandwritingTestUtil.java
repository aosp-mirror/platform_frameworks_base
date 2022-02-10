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

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.platform.app.InstrumentationRegistry;

public class HandwritingTestUtil {
    public static View createView(Rect handwritingArea) {
        return createView(handwritingArea, true);
    }

    public static View createView(Rect handwritingArea, boolean autoHandwritingEnabled) {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        // mock a parent so that HandwritingInitiator can get
        final ViewGroup parent = new ViewGroup(context) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                // We don't layout this view.
            }
            @Override
            public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset) {
                r.set(handwritingArea);
                return true;
            }
        };

        View view = spy(new View(context));
        when(view.isAttachedToWindow()).thenReturn(true);
        when(view.isAggregatedVisible()).thenReturn(true);
        when(view.getHandwritingArea()).thenReturn(handwritingArea);
        view.setAutoHandwritingEnabled(autoHandwritingEnabled);
        parent.addView(view);
        return view;
    }
}
