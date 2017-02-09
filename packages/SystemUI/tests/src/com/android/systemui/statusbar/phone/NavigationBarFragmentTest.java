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

package com.android.systemui.statusbar.phone;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;

import com.android.systemui.Dependency;
import com.android.systemui.FragmentTestCase;
import com.android.systemui.SysUIRunner;
import com.android.systemui.recents.Recents;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.utils.TestableLooper.RunWithLooper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(SysUIRunner.class)
@RunWithLooper(setAsMainLooper = true)
public class NavigationBarFragmentTest extends FragmentTestCase {

    public NavigationBarFragmentTest() {
        super(NavigationBarFragment.class);
    }

    @Before
    public void setup() {
        injectTestDependency(Dependency.BG_LOOPER, Looper.getMainLooper());
        mContext.putComponent(CommandQueue.class, mock(CommandQueue.class));
        mContext.putComponent(StatusBar.class, mock(StatusBar.class));
        mContext.putComponent(Recents.class, mock(Recents.class));
        mContext.putComponent(Divider.class, mock(Divider.class));
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        WindowManager windowManager = mock(WindowManager.class);
        Display defaultDisplay = mContext.getSystemService(WindowManager.class).getDefaultDisplay();
        when(windowManager.getDefaultDisplay()).thenReturn(
                defaultDisplay);
        mContext.addMockSystemService(Context.WINDOW_SERVICE, windowManager);
    }

    @Test
    public void testHomeLongPress() {
        NavigationBarFragment navigationBarFragment = (NavigationBarFragment) mFragment;

        mFragments.dispatchResume();
        processAllMessages();
        navigationBarFragment.onHomeLongClick(navigationBarFragment.getView());
    }

}
