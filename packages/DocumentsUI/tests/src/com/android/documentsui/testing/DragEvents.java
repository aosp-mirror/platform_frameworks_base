/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui.testing;

import android.content.ClipData;
import android.view.DragEvent;

import org.mockito.Mockito;

/**
 * Test support for working with {@link DragEvents} instances.
 */
public final class DragEvents {

    private DragEvents() {}

    public static DragEvent createTestDragEvent(int actionId) {
        final DragEvent mockEvent = Mockito.mock(DragEvent.class);
        Mockito.when(mockEvent.getAction()).thenReturn(actionId);

        return mockEvent;
    }

    public static DragEvent createTestDropEvent(ClipData clipData) {
        final DragEvent dropEvent = createTestDragEvent(DragEvent.ACTION_DROP);
        Mockito.when(dropEvent.getClipData()).thenReturn(clipData);

        return dropEvent;
    }
}
