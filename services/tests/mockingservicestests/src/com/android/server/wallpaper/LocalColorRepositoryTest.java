/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wallpaper;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import static java.util.Arrays.asList;

import android.app.ILocalWallpaperColorConsumer;
import android.app.WallpaperColors;
import android.graphics.RectF;
import android.os.IBinder;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

import android.util.ArraySet;
import java.util.List;
import java.util.function.Consumer;


@RunWith(AndroidJUnit4.class)
public class LocalColorRepositoryTest {
    private LocalColorRepository mRepo = new LocalColorRepository();
    @Mock
    private IBinder mBinder1;
    @Mock
    private IBinder mBinder2;
    @Mock
    private ILocalWallpaperColorConsumer mCallback1;
    @Mock
    private ILocalWallpaperColorConsumer mCallback2;

    @Before
    public void setUp() {
        initMocks(this);
        when(mCallback1.asBinder()).thenReturn(mBinder1);
        when(mCallback2.asBinder()).thenReturn(mBinder2);
    }

    @Test
    public void testDisplayAreas() {
        RectF area1 = new RectF(1, 0, 0, 0);
        RectF area2 = new RectF(2, 1, 1, 1);
        ArraySet<RectF> expectedAreas = new ArraySet(asList(area1, area2));

        mRepo.addAreas(mCallback1, asList(area1), 0);
        mRepo.addAreas(mCallback2, asList(area2), 0);
        mRepo.addAreas(mCallback1, asList(new RectF(3, 1, 1, 1)), 1);

        assertEquals(expectedAreas, new ArraySet(mRepo.getAreasByDisplayId(0)));
        assertEquals(new ArraySet(asList(new RectF(3, 1, 1, 1))),
                new ArraySet(mRepo.getAreasByDisplayId(1)));
        assertEquals(new ArraySet(), new ArraySet(mRepo.getAreasByDisplayId(2)));
    }

    @Test
    public void testAddAndRemoveAreas() {
        RectF area1 = new RectF(1, 0, 0, 0);
        RectF area2 = new RectF(2, 1, 1, 1);

        mRepo.addAreas(mCallback1, asList(area1), 0);
        mRepo.addAreas(mCallback1, asList(area2), 0);
        mRepo.addAreas(mCallback2, asList(area2), 1);

        List<RectF> removed = mRepo.removeAreas(mCallback1, asList(area1), 0);
        assertEquals(new ArraySet(asList(area1)), new ArraySet(removed));
        // since we have another callback with a different area, we don't purge rid of any areas
        removed = mRepo.removeAreas(mCallback1, asList(area2), 0);
        assertEquals(new ArraySet(), new ArraySet(removed));
    }

    @Test
    public void testAreaCallback() {
        Consumer<ILocalWallpaperColorConsumer> consumer = mock(Consumer.class);
        WallpaperColors colors = mock(WallpaperColors.class);
        RectF area1 = new RectF(1, 0, 0, 0);
        RectF area2 = new RectF(2, 1, 1, 1);

        mRepo.addAreas(mCallback1, asList(area1), 0);
        mRepo.addAreas(mCallback1, asList(area2), 0);
        mRepo.addAreas(mCallback2, asList(area2), 0);

        mRepo.forEachCallback(consumer, area1, 0);
        Mockito.verify(consumer, times(1)).accept(eq(mCallback1));
        Mockito.verify(consumer, times(0)).accept(eq(mCallback2));
        mRepo.forEachCallback(consumer, area2, 0);
        Mockito.verify(consumer, times(2)).accept(eq(mCallback1));
        Mockito.verify(consumer, times(1)).accept(eq(mCallback2));
    }

    @Test
    public void unregisterCallbackWhenNoAreas() {
        RectF area1 = new RectF(1, 0, 0, 0);
        RectF area2 = new RectF(2, 1, 1, 1);

        assertFalse(mRepo.isCallbackAvailable(mCallback1));

        mRepo.addAreas(mCallback1, asList(area1), 0);
        mRepo.addAreas(mCallback1, asList(area2), 0);

        mRepo.removeAreas(mCallback1, asList(area1, area2), 0);
        assertFalse(mRepo.isCallbackAvailable(mCallback1));

        mRepo.addAreas(mCallback1, asList(area1), 0);
        assertTrue(mRepo.isCallbackAvailable(mCallback1));
    }
}
