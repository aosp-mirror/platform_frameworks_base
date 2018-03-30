/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.NotificationColorUtil;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class FooterViewTest extends SysuiTestCase {

    FooterView mView;

    @Before
    public void setUp() {
        mView = (FooterView) LayoutInflater.from(mContext).inflate(
                R.layout.status_bar_notification_footer, null, false);
        mView.setDuration(0);
    }

    @Test
    public void testViewsNotNull() {
        assertNotNull(mView.findContentView());
        assertNotNull(mView.findSecondaryView());
    }

    @Test
    public void setDismissOnClick() {
        mView.setDismissButtonClickListener(mock(View.OnClickListener.class));
        assertTrue(mView.findSecondaryView().hasOnClickListeners());
    }

    @Test
    public void setManageOnClick() {
        mView.setManageButtonClickListener(mock(View.OnClickListener.class));
        assertTrue(mView.findViewById(R.id.manage_text).hasOnClickListeners());
    }

    @Test
    public void testPerformVisibilityAnimation() {
        mView.setInvisible();
        assertFalse(mView.isVisible());

        Runnable test = new Runnable() {
            @Override
            public void run() {
                assertEquals(1.0f, mView.findContentView().getAlpha());
                assertEquals(0.0f, mView.findSecondaryView().getAlpha());
                assertTrue(mView.isVisible());
            }
        };
        mView.performVisibilityAnimation(true, test);
    }

    @Test
    public void testPerformSecondaryVisibilityAnimation() {
        mView.setInvisible();
        assertFalse(mView.isSecondaryVisible());

        Runnable test = new Runnable() {
            @Override
            public void run() {
                assertEquals(0.0f, mView.findContentView().getAlpha());
                assertEquals(1.0f, mView.findSecondaryView().getAlpha());
                assertTrue(mView.isSecondaryVisible());
            }
        };
        mView.performSecondaryVisibilityAnimation(true, test);
    }
}

