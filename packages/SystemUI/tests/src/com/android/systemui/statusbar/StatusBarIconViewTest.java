/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.graphics.drawable.Icon;
import android.os.Debug;
import android.os.UserHandle;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static junit.framework.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class StatusBarIconViewTest extends SysuiTestCase {

    private StatusBarIconView mIconView;
    private StatusBarIcon mStatusBarIcon = mock(StatusBarIcon.class);

    @Before
    public void setUp() {
        mIconView = new StatusBarIconView(getContext(), "slot", null);
        mStatusBarIcon = new StatusBarIcon(UserHandle.ALL, getContext().getPackageName(),
                Icon.createWithResource(getContext(), R.drawable.ic_android), 0, 0, "");
    }

    @Test
    public void testSetClearsGrayscale() {
        mIconView.setTag(R.id.icon_is_grayscale, true);
        mIconView.set(mStatusBarIcon);
        assertNull(mIconView.getTag(R.id.icon_is_grayscale));
    }

}