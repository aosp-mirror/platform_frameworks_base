/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.tuner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.LeakCheck.Tracker;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Test;

@SmallTest
public class TunablePaddingTest extends LeakCheckedTest {

    private static final String KEY = "KEY";
    private static final int DEFAULT = 42;
    private View mView;
    private TunablePadding mTunablePadding;
    private TunerService mTunerService;

    @Before
    public void setup() {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        mView = mock(View.class);
        when(mView.getContext()).thenReturn(mContext);

        mTunerService = mDependency.injectMockDependency(TunerService.class);
        Tracker tracker = mLeakCheck.getTracker("tuner");
        doAnswer(invocation -> {
            tracker.getLeakInfo(invocation.getArguments()[0]).addAllocation(new Throwable());
            return null;
        }).when(mTunerService).addTunable(any(), any());
        doAnswer(invocation -> {
            tracker.getLeakInfo(invocation.getArguments()[0]).clearAllocations();
            return null;
        }).when(mTunerService).removeTunable(any());
    }

    @Test
    public void testFlags() {
        mTunablePadding = TunablePadding.addTunablePadding(mView, KEY, DEFAULT,
                TunablePadding.FLAG_START);
        mTunablePadding.onTuningChanged(null, null);
        verify(mView).setPadding(eq(DEFAULT), eq(0), eq(0), eq(0));
        mTunablePadding.destroy();

        mTunablePadding = TunablePadding.addTunablePadding(mView, KEY, DEFAULT,
                TunablePadding.FLAG_TOP);
        mTunablePadding.onTuningChanged(null, null);
        verify(mView).setPadding(eq(0), eq(DEFAULT), eq(0), eq(0));
        mTunablePadding.destroy();

        mTunablePadding = TunablePadding.addTunablePadding(mView, KEY, DEFAULT,
                TunablePadding.FLAG_END);
        mTunablePadding.onTuningChanged(null, null);
        verify(mView).setPadding(eq(0), eq(0), eq(DEFAULT), eq(0));
        mTunablePadding.destroy();

        mTunablePadding = TunablePadding.addTunablePadding(mView, KEY, DEFAULT,
                TunablePadding.FLAG_BOTTOM);
        mTunablePadding.onTuningChanged(null, null);
        verify(mView).setPadding(eq(0), eq(0), eq(0), eq(DEFAULT));
        mTunablePadding.destroy();
    }

    @Test
    public void testRtl() {
        when(mView.isLayoutRtl()).thenReturn(true);

        mTunablePadding = TunablePadding.addTunablePadding(mView, KEY, DEFAULT,
                TunablePadding.FLAG_END);
        mTunablePadding.onTuningChanged(null, null);
        verify(mView).setPadding(eq(DEFAULT), eq(0), eq(0), eq(0));
        mTunablePadding.destroy();

        mTunablePadding = TunablePadding.addTunablePadding(mView, KEY, DEFAULT,
                TunablePadding.FLAG_START);
        mTunablePadding.onTuningChanged(null, null);
        verify(mView).setPadding(eq(0), eq(0), eq(DEFAULT), eq(0));
        mTunablePadding.destroy();
    }

    @Test
    public void testTuning() {
        int value = 3;
        mTunablePadding = TunablePadding.addTunablePadding(mView, KEY, DEFAULT,
                TunablePadding.FLAG_START);
        mTunablePadding.onTuningChanged(KEY, String.valueOf(value));

        DisplayMetrics metrics = new DisplayMetrics();
        mContext.getSystemService(WindowManager.class).getDefaultDisplay().getMetrics(metrics);
        int output = (int) (metrics.density * value);
        verify(mView).setPadding(eq(output), eq(0), eq(0), eq(0));

        mTunablePadding.destroy();
    }
}