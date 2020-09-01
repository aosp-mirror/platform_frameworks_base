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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static org.mockito.ArgumentMatchers.any;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.platform.test.annotations.Presubmit;
import android.view.Surface;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link TaskSnapshotSurface}.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowContainerThumbnailTest
 *
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowContainerThumbnailTest extends WindowTestsBase {
    private WindowContainerThumbnail buildThumbnail() {
        final GraphicBuffer buffer = GraphicBuffer.create(1, 1, PixelFormat.RGBA_8888,
                GraphicBuffer.USAGE_SW_READ_RARELY | GraphicBuffer.USAGE_SW_WRITE_NEVER);
        final ActivityRecord mockAr = mock(ActivityRecord.class);
        when(mockAr.getPendingTransaction()).thenReturn(new StubTransaction());
        when(mockAr.makeChildSurface(any())).thenReturn(new MockSurfaceControlBuilder());
        when(mockAr.makeSurface()).thenReturn(new MockSurfaceControlBuilder());
        return new WindowContainerThumbnail(new StubTransaction(), mockAr,
                buffer, false, mock(Surface.class), mock(SurfaceAnimator.class));
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testDestroy_nullsSurface() {
        final WindowContainerThumbnail t = buildThumbnail();
        assertNotNull(t.getSurfaceControl());
        t.destroy();
        assertNull(t.getSurfaceControl());
    }
}
