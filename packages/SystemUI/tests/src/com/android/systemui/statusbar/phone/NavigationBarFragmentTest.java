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

import android.content.Context;
import android.view.WindowManager;

import com.android.systemui.FragmentTestCase;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.recents.Recents;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;

import org.junit.Before;
import org.junit.Test;

public class NavigationBarFragmentTest extends FragmentTestCase {

    public NavigationBarFragmentTest() {
        super(NavigationBarFragment.class);
    }

    @Before
    public void setup() {
        mContext.putComponent(CommandQueue.class, mock(CommandQueue.class));
        mContext.putComponent(PhoneStatusBar.class, mock(PhoneStatusBar.class));
        mContext.putComponent(Recents.class, mock(Recents.class));
        mContext.putComponent(Divider.class, mock(Divider.class));
    }

    @Test
    public void testHomeLongPress() {
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mock(WindowManager.class));
        NavigationBarFragment navigationBarFragment = (NavigationBarFragment) mFragment;

        postAndWait(() -> mFragments.dispatchResume());
        navigationBarFragment.onHomeLongClick(navigationBarFragment.getView());
    }

}
