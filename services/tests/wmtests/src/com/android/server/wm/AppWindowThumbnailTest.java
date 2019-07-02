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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.platform.test.annotations.Presubmit;
import android.view.Surface;
import android.view.SurfaceControl;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Test class for {@link TaskSnapshotSurface}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTest:AppWindowThumbnailTest
 *
 */
@SmallTest
@Presubmit
public class AppWindowThumbnailTest extends WindowTestsBase {
    private AppWindowThumbnail buildThumbnail() {
        final GraphicBuffer buffer = GraphicBuffer.create(1, 1, PixelFormat.RGBA_8888,
                GraphicBuffer.USAGE_SW_READ_RARELY | GraphicBuffer.USAGE_SW_WRITE_NEVER);
        final AppWindowToken mockAwt = mock(AppWindowToken.class);
        when(mockAwt.getPendingTransaction()).thenReturn(new StubTransaction());
        when(mockAwt.makeSurface()).thenReturn(new MockSurfaceControlBuilder());
        return new AppWindowThumbnail(new StubTransaction(), mockAwt,
                buffer, false, mock(Surface.class), mock(SurfaceAnimator.class));
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testDestroy_nullsSurface() {
        final AppWindowThumbnail t = buildThumbnail();
        assertNotNull(t.getSurfaceControl());
        t.destroy();
        assertNull(t.getSurfaceControl());
    }
}
