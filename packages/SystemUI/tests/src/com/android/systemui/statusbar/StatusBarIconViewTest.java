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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

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
public class StatusBarIconViewTest extends SysuiTestCase {

    @Rule
    public ExpectedException mThrown = ExpectedException.none();

    private StatusBarIconView mIconView;
    private StatusBarIcon mStatusBarIcon = mock(StatusBarIcon.class);

    private PackageManager mPackageManagerSpy;
    private Context mContext;
    private Resources mMockResources;

    @Before
    public void setUp() throws Exception {
        // Set up context such that asking for "mockPackage" resources returns mMockResources.
        mMockResources = mock(Resources.class);
        mPackageManagerSpy = spy(getContext().getPackageManager());
        doReturn(mMockResources).when(mPackageManagerSpy)
                .getResourcesForApplicationAsUser(eq("mockPackage"), anyInt());
        doReturn(mMockResources).when(mPackageManagerSpy)
                .getResourcesForApplication(eq("mockPackage"));
        doReturn(mMockResources).when(mPackageManagerSpy).getResourcesForApplication(argThat(
                (ArgumentMatcher<ApplicationInfo>) o -> "mockPackage".equals(o.packageName)));
        mContext = new ContextWrapper(getContext()) {
            @Override
            public PackageManager getPackageManager() {
                return mPackageManagerSpy;
            }
        };

        mIconView = new StatusBarIconView(mContext, "test_slot", null);
        mStatusBarIcon = new StatusBarIcon(UserHandle.ALL, "mockPackage",
                Icon.createWithResource(mContext, R.drawable.ic_android), 0, 0, "");
    }

    @Test
    public void testSetClearsGrayscale() {
        mIconView.setTag(R.id.icon_is_grayscale, true);
        mIconView.set(mStatusBarIcon);
        assertNull(mIconView.getTag(R.id.icon_is_grayscale));
    }

    @Test
    public void testSettingOomingIconDoesNotThrowOom() {
        when(mMockResources.getDrawable(anyInt(), any())).thenThrow(new OutOfMemoryError("mocked"));
        mStatusBarIcon.icon = Icon.createWithResource("mockPackage", R.drawable.ic_android);

        assertFalse(mIconView.set(mStatusBarIcon));
    }

    @Test
    public void testGetContrastedStaticDrawableColor() {
        mIconView.setStaticDrawableColor(Color.DKGRAY);
        int color = mIconView.getContrastedStaticDrawableColor(Color.WHITE);
        assertEquals("Color should not change when we have enough contrast",
                Color.DKGRAY, color);

        mIconView.setStaticDrawableColor(Color.WHITE);
        color = mIconView.getContrastedStaticDrawableColor(Color.WHITE);
        assertTrue("Similar colors should be shifted to satisfy contrast",
                NotificationColorUtil.satisfiesTextContrast(Color.WHITE, color));

        mIconView.setStaticDrawableColor(Color.GREEN);
        color = mIconView.getContrastedStaticDrawableColor(0xcc000000);
        assertEquals("Transparent backgrounds should fallback to drawable color",
                color, mIconView.getStaticDrawableColor());
    }

    @Test
    public void testGiantImageNotAllowed() {
        Bitmap largeBitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888);
        Icon icon = Icon.createWithBitmap(largeBitmap);
        StatusBarIcon largeIcon = new StatusBarIcon(UserHandle.ALL, "mockPackage",
                icon, 0, 0, "");
        assertFalse(mIconView.set(largeIcon));
    }
}