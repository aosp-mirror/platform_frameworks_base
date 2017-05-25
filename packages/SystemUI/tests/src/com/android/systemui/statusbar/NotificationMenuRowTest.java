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

package com.android.systemui.statusbar;

import static junit.framework.Assert.assertTrue;

import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.ViewUtils;
import android.testing.ViewUtils;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class NotificationMenuRowTest extends LeakCheckedTest {

    @Before
    public void setup() {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
    }

    @Test
    public void testAttachDetach() {
        NotificationMenuRowPlugin row = new NotificationMenuRow(mContext);
        row.createMenu(null);
        ViewUtils.attachView(row.getMenuView());
        TestableLooper.get(this).processAllMessages();
        ViewUtils.detachView(row.getMenuView());
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testRecreateMenu() {
        NotificationMenuRowPlugin row = new NotificationMenuRow(mContext);
        row.createMenu(null);
        assertTrue(row.getMenuView() != null);
        row.createMenu(null);
        assertTrue(row.getMenuView() != null);
    }
}
