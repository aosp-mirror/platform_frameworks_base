/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.common;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.Surface.ROTATION_0;
import static android.view.WindowInsets.Type.ime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.graphics.Insets;
import android.graphics.Point;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.internal.view.IInputMethodManager;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.Executor;

@SmallTest
public class DisplayImeControllerTest {

    private SurfaceControl.Transaction mT;
    private DisplayImeController.PerDisplay mPerDisplay;
    private IInputMethodManager mMock;
    private Executor mExecutor;

    @Before
    public void setUp() throws Exception {
        mT = mock(SurfaceControl.Transaction.class);
        mMock = mock(IInputMethodManager.class);
        mExecutor = spy(Runnable::run);
        mPerDisplay = new DisplayImeController(null, null, mExecutor, new TransactionPool() {
            @Override
            public SurfaceControl.Transaction acquire() {
                return mT;
            }

            @Override
            public void release(SurfaceControl.Transaction t) {
            }
        }) {
            @Override
            public IInputMethodManager getImms() {
                return mMock;
            }
            @Override
            void removeImeSurface() { }
        }.new PerDisplay(DEFAULT_DISPLAY, ROTATION_0);
    }

    @Test
    public void insetsControlChanged_schedulesNoWorkOnExecutor() {
        mPerDisplay.insetsControlChanged(insetsStateWithIme(false), insetsSourceControl());
        verifyZeroInteractions(mExecutor);
    }

    @Test
    public void insetsChanged_schedulesNoWorkOnExecutor() {
        mPerDisplay.insetsChanged(insetsStateWithIme(false));
        verifyZeroInteractions(mExecutor);
    }

    @Test
    public void showInsets_schedulesNoWorkOnExecutor() {
        mPerDisplay.showInsets(ime(), true);
        verifyZeroInteractions(mExecutor);
    }

    @Test
    public void hideInsets_schedulesNoWorkOnExecutor() {
        mPerDisplay.hideInsets(ime(), true);
        verifyZeroInteractions(mExecutor);
    }

    @Test
    public void reappliesVisibilityToChangedLeash() {
        verifyZeroInteractions(mT);
        mPerDisplay.mImeShowing = true;

        mPerDisplay.insetsControlChanged(insetsStateWithIme(false), insetsSourceControl());

        assertFalse(mPerDisplay.mImeShowing);
        verify(mT).hide(any());

        mPerDisplay.mImeShowing = true;
        mPerDisplay.insetsControlChanged(insetsStateWithIme(true), insetsSourceControl());

        assertTrue(mPerDisplay.mImeShowing);
        verify(mT).show(any());
    }

    private InsetsSourceControl[] insetsSourceControl() {
        return new InsetsSourceControl[]{
                new InsetsSourceControl(
                        ITYPE_IME, mock(SurfaceControl.class), new Point(0, 0), Insets.NONE)
        };
    }

    private InsetsState insetsStateWithIme(boolean visible) {
        InsetsState state = new InsetsState();
        state.addSource(new InsetsSource(ITYPE_IME));
        state.setSourceVisible(ITYPE_IME, visible);
        return state;
    }

}
