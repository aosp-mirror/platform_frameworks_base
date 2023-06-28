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

package com.android.dream.lowlight;

import static com.android.dream.lowlight.LowLightDreamManager.AMBIENT_LIGHT_MODE_LOW_LIGHT;
import static com.android.dream.lowlight.LowLightDreamManager.AMBIENT_LIGHT_MODE_REGULAR;
import static com.android.dream.lowlight.LowLightDreamManager.AMBIENT_LIGHT_MODE_UNKNOWN;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.DreamManager;
import android.content.ComponentName;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class LowLightDreamManagerTest {
    @Mock
    private DreamManager mDreamManager;

    @Mock
    private ComponentName mDreamComponent;

    LowLightDreamManager mLowLightDreamManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mLowLightDreamManager = new LowLightDreamManager(mDreamManager,
                mDreamComponent);
    }

    @Test
    public void setAmbientLightMode_lowLight_setSystemDream() {
        mLowLightDreamManager.setAmbientLightMode(AMBIENT_LIGHT_MODE_LOW_LIGHT);

        verify(mDreamManager).setSystemDreamComponent(mDreamComponent);
    }

    @Test
    public void setAmbientLightMode_regularLight_clearSystemDream() {
        mLowLightDreamManager.setAmbientLightMode(AMBIENT_LIGHT_MODE_REGULAR);

        verify(mDreamManager).setSystemDreamComponent(null);
    }

    @Test
    public void setAmbientLightMode_defaultUnknownMode_clearSystemDream() {
        // Set to low light first.
        mLowLightDreamManager.setAmbientLightMode(AMBIENT_LIGHT_MODE_LOW_LIGHT);
        clearInvocations(mDreamManager);

        // Return to default unknown mode.
        mLowLightDreamManager.setAmbientLightMode(AMBIENT_LIGHT_MODE_UNKNOWN);

        verify(mDreamManager).setSystemDreamComponent(null);
    }

    @Test
    public void setAmbientLightMode_dreamComponentNotSet_doNothing() {
        final LowLightDreamManager lowLightDreamManager = new LowLightDreamManager(mDreamManager,
                null /*dream component*/);

        lowLightDreamManager.setAmbientLightMode(AMBIENT_LIGHT_MODE_LOW_LIGHT);

        verify(mDreamManager, never()).setSystemDreamComponent(any());
    }
}
