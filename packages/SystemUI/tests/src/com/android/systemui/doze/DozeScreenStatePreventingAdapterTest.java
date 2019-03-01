/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.doze;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.Display;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.DozeParameters;

import org.junit.Before;
import org.junit.Test;

@SmallTest
public class DozeScreenStatePreventingAdapterTest extends SysuiTestCase {

    private DozeMachine.Service mInner;
    private DozeScreenStatePreventingAdapter mWrapper;

    @Before
    public void setup() throws Exception {
        mInner = mock(DozeMachine.Service.class);
        mWrapper = new DozeScreenStatePreventingAdapter(mInner);
    }

    @Test
    public void forwards_finish() throws Exception {
        mWrapper.finish();
        verify(mInner).finish();
    }

    @Test
    public void forwards_setDozeScreenState_on() throws Exception {
        mWrapper.setDozeScreenState(Display.STATE_ON);
        verify(mInner).setDozeScreenState(Display.STATE_ON);
    }

    @Test
    public void forwards_setDozeScreenState_off() throws Exception {
        mWrapper.setDozeScreenState(Display.STATE_OFF);
        verify(mInner).setDozeScreenState(Display.STATE_OFF);
    }

    @Test
    public void forwards_setDozeScreenState_doze() throws Exception {
        mWrapper.setDozeScreenState(Display.STATE_DOZE);
        verify(mInner).setDozeScreenState(Display.STATE_ON);
    }

    @Test
    public void forwards_setDozeScreenState_doze_suspend() throws Exception {
        mWrapper.setDozeScreenState(Display.STATE_DOZE_SUSPEND);
        verify(mInner).setDozeScreenState(Display.STATE_ON_SUSPEND);
    }

    @Test
    public void forwards_requestWakeUp() throws Exception {
        mWrapper.requestWakeUp();
        verify(mInner).requestWakeUp();
    }

    @Test
    public void wrapIfNeeded_needed() throws Exception {
        DozeParameters params = mock(DozeParameters.class);
        when(params.getDisplayStateSupported()).thenReturn(false);

        assertEquals(DozeScreenStatePreventingAdapter.class,
                DozeScreenStatePreventingAdapter.wrapIfNeeded(mInner, params).getClass());
    }

    @Test
    public void wrapIfNeeded_not_needed() throws Exception {
        DozeParameters params = mock(DozeParameters.class);
        when(params.getDisplayStateSupported()).thenReturn(true);

        assertSame(mInner, DozeScreenStatePreventingAdapter.wrapIfNeeded(mInner, params));
    }
}
