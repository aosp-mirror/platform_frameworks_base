/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import static com.google.common.truth.Truth.assertThat;

import android.app.Notification;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class MenuNotificationFactoryTest extends SysuiTestCase {
    private MenuNotificationFactory mMenuNotificationFactory;

    @Before
    public void setUp() {
        mMenuNotificationFactory = new MenuNotificationFactory(mContext);
    }

    @Test
    public void createHiddenNotification_hasUndoAndDeleteAction() {
        Notification notification = mMenuNotificationFactory.createHiddenNotification();

        assertThat(notification.contentIntent.getIntent().getAction()).isEqualTo(
                MenuNotificationFactory.ACTION_UNDO);
        assertThat(notification.deleteIntent.getIntent().getAction()).isEqualTo(
                MenuNotificationFactory.ACTION_DELETE);
    }
}
