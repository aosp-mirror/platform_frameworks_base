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

package com.android.systemui.dreams;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import android.content.Intent;
import android.os.IBinder;
import android.service.dreams.IDreamOverlay;
import android.service.dreams.IDreamOverlayCallback;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamOverlayServiceTest extends SysuiTestCase {
    private FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private FakeExecutor mMainExecutor = new FakeExecutor(mFakeSystemClock);

    @Rule
    public final LeakCheckedTest.SysuiLeakCheck mLeakCheck = new LeakCheckedTest.SysuiLeakCheck();

    @Rule
    public SysuiTestableContext mContext = new SysuiTestableContext(
            InstrumentationRegistry.getContext(), mLeakCheck);

    WindowManager.LayoutParams mWindowParams = new WindowManager.LayoutParams();

    @Mock
    IDreamOverlayCallback mDreamOverlayCallback;

    @Mock
    WindowManagerImpl mWindowManager;

    @Mock
    OverlayProvider mProvider;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(WindowManager.class, mWindowManager);
    }

    @Test
    public void testInteraction() throws Exception {
        final DreamOverlayService service = new DreamOverlayService(mContext, mMainExecutor);
        final IBinder proxy = service.onBind(new Intent());
        final IDreamOverlay overlay = IDreamOverlay.Stub.asInterface(proxy);
        clearInvocations(mWindowManager);

        // Inform the overlay service of dream starting.
        overlay.startDream(mWindowParams, mDreamOverlayCallback);
        mMainExecutor.runAllReady();
        verify(mWindowManager).addView(any(), any());

        // Add overlay.
        service.addOverlay(mProvider);
        mMainExecutor.runAllReady();

        final ArgumentCaptor<OverlayHost.CreationCallback> creationCallbackCapture =
                ArgumentCaptor.forClass(OverlayHost.CreationCallback.class);
        final ArgumentCaptor<OverlayHost.InteractionCallback> interactionCallbackCapture =
                ArgumentCaptor.forClass(OverlayHost.InteractionCallback.class);

        // Ensure overlay provider is asked to create view.
        verify(mProvider).onCreateOverlay(any(), creationCallbackCapture.capture(),
                interactionCallbackCapture.capture());
        mMainExecutor.runAllReady();

        // Inform service of overlay view creation.
        final View view = new View(mContext);
        creationCallbackCapture.getValue().onCreated(view, new ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Ask service to exit.
        interactionCallbackCapture.getValue().onExit();
        mMainExecutor.runAllReady();

        // Ensure service informs dream host of exit.
        verify(mDreamOverlayCallback).onExitRequested();
    }
}
