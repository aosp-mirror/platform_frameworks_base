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
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.SystemClock;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class StatusBarWindowViewTest extends SysuiTestCase {

    private StatusBarWindowView mView;
    private StatusBar mStatusBar;
    private DragDownHelper mDragDownHelper;
    private NotificationStackScrollLayout mStackScrollLayout;

    @Before
    public void setUp() {
        mDependency.injectMockDependency(StatusBarStateController.class);
        mView = spy(new StatusBarWindowView(getContext(), null));
        mStackScrollLayout = mock(NotificationStackScrollLayout.class);
        when(mView.getStackScrollLayout()).thenReturn(mStackScrollLayout);
        mStatusBar = mock(StatusBar.class);
        mView.setService(mStatusBar);
        mDragDownHelper = mock(DragDownHelper.class);
        mView.setDragDownHelper(mDragDownHelper);
    }

    @Test
    public void testDragDownHelperCalledWhenDraggingDown() throws Exception {
        when(Dependency.get(StatusBarStateController.class).getState())
                .thenReturn(StatusBarState.SHADE);
        when(mDragDownHelper.isDraggingDown()).thenReturn(true);
        long now = SystemClock.elapsedRealtime();
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 0 /* x */, 0 /* y */,
                0 /* meta */);
        mView.onTouchEvent(ev);
        verify(mDragDownHelper).onTouchEvent(ev);
        ev.recycle();
    }
}
