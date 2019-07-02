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
package com.android.keyguard.clock;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;

import android.graphics.Bitmap;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Supplier;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public final class ClockInfoTest extends SysuiTestCase {

    @Mock
    private Supplier<Bitmap> mMockSupplier;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testGetName() {
        final String name = "name";
        ClockInfo info = ClockInfo.builder().setName(name).build();
        assertThat(info.getName()).isEqualTo(name);
    }

    @Test
    public void testGetTitle() {
        final String title = "title";
        ClockInfo info = ClockInfo.builder().setTitle(title).build();
        assertThat(info.getTitle()).isEqualTo(title);
    }

    @Test
    public void testGetId() {
        final String id = "id";
        ClockInfo info = ClockInfo.builder().setId(id).build();
        assertThat(info.getId()).isEqualTo(id);
    }

    @Test
    public void testGetThumbnail() {
        ClockInfo info = ClockInfo.builder().setThumbnail(mMockSupplier).build();
        info.getThumbnail();
        verify(mMockSupplier).get();
    }

    @Test
    public void testGetPreview() {
        ClockInfo info = ClockInfo.builder().setPreview(mMockSupplier).build();
        info.getPreview();
        verify(mMockSupplier).get();
    }
}
