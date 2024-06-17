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

import static com.google.common.truth.Truth.assertThat;

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

import android.app.Notification;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.NotificationContentDescription;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class StatusBarIconViewTest extends SysuiTestCase {

    private static final int TEST_STATUS_BAR_HEIGHT = 150;

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
                Icon.createWithResource(mContext, R.drawable.ic_android), 0, 0, "",
                StatusBarIcon.Type.SystemIcon);
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
                ContrastColorUtil.satisfiesTextContrast(Color.WHITE, color));

        mIconView.setStaticDrawableColor(Color.GREEN);
        color = mIconView.getContrastedStaticDrawableColor(0xcc000000);
        assertEquals("Transparent backgrounds should fallback to drawable color",
                color, mIconView.getStaticDrawableColor());
    }

    @Test
    public void testGiantImageNotAllowed() {
        Bitmap largeBitmap = Bitmap.createBitmap(6000, 6000, Bitmap.Config.ARGB_8888);
        Icon icon = Icon.createWithBitmap(largeBitmap);
        StatusBarIcon largeIcon = new StatusBarIcon(UserHandle.ALL, "mockPackage",
                icon, 0, 0, "", StatusBarIcon.Type.SystemIcon);
        assertTrue(mIconView.set(largeIcon));

        // The view should downscale the bitmap.
        BitmapDrawable drawable = (BitmapDrawable) mIconView.getDrawable();
        assertThat(drawable.getBitmap().getWidth()).isLessThan(1000);
        assertThat(drawable.getBitmap().getHeight()).isLessThan(1000);
    }

    @Test
    public void testNullNotifInfo() {
        Bitmap bitmap = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888);
        Icon icon = Icon.createWithBitmap(bitmap);
        StatusBarIcon largeIcon = new StatusBarIcon(UserHandle.ALL, "mockPackage",
                icon, 0, 0, "", StatusBarIcon.Type.SystemIcon);
        mIconView.setNotification(getMockSbn());
        mIconView.getIcon(largeIcon);
        // no crash? good

        mIconView.setNotification(null);
        mIconView.getIcon(largeIcon);
        // no crash? good
    }

    @Test
    public void testNullIcon() {
        Icon mockIcon = mock(Icon.class);
        when(mockIcon.loadDrawableAsUser(any(), anyInt())).thenReturn(null);
        mStatusBarIcon.icon = mockIcon;
        mIconView.set(mStatusBarIcon);

        Bitmap bitmap = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888);
        Icon icon = Icon.createWithBitmap(bitmap);
        StatusBarIcon largeIcon = new StatusBarIcon(UserHandle.ALL, "mockPackage",
                icon, 0, 0, "", StatusBarIcon.Type.SystemIcon);
        mIconView.getIcon(largeIcon);
        // No crash? good
    }

    @Test
    public void testContentDescForNotification_invalidAi_noCrash() {
        Notification n = new Notification.Builder(mContext, "test")
                .setSmallIcon(0)
                .build();
        // should be ApplicationInfo
        n.extras.putParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO, new Bundle());
        NotificationContentDescription.contentDescForNotification(mContext, n);

        // no crash, good
    }

    @Test
    public void testUpdateIconScale_constrainedDrawableSizeLessThanDpIconSize() {
        int dpIconSize = 60;
        int dpDrawingSize = 30;
        // the icon view layout size would be 60x150
        //   (the height is always 150 due to TEST_STATUS_BAR_HEIGHT)
        setUpIconView(dpIconSize, dpDrawingSize, dpIconSize);
        mIconView.setNotification(getMockSbn());
        // the raw drawable size is 50x50. When put the drawable into iconView whose
        // layout size is 60x150, the drawable size would not be constrained and thus keep 50x50
        setIconDrawableWithSize(/* width= */ 50, /* height= */ 50);
        mIconView.maybeUpdateIconScaleDimens();

        // WHEN both the constrained drawable width/height are less than dpIconSize,
        // THEN the icon is scaled down from dpIconSize to fit the dpDrawingSize
        float scaleToFitDrawingSize = (float) dpDrawingSize / dpIconSize;
        assertEquals(scaleToFitDrawingSize, mIconView.getIconScale(), 0.01f);
    }

    @Test
    public void testUpdateIconScale_constrainedDrawableHeightLargerThanDpIconSize() {
        int dpIconSize = 60;
        int dpDrawingSize = 30;
        // the icon view layout size would be 60x150
        //   (the height is always 150 due to TEST_STATUS_BAR_HEIGHT)
        setUpIconView(dpIconSize, dpDrawingSize, dpIconSize);
        mIconView.setNotification(getMockSbn());
        // the raw drawable size is 50x100. When put the drawable into iconView whose
        // layout size is 60x150, the drawable size would not be constrained and thus keep 50x100
        setIconDrawableWithSize(/* width= */ 50, /* height= */ 100);
        mIconView.maybeUpdateIconScaleDimens();

        // WHEN constrained drawable larger side length 100 >= dpIconSize
        // THEN the icon is scaled down from larger side length 100 to ensure both side
        //      length fit in dpDrawingSize.
        float scaleToFitDrawingSize = (float) dpDrawingSize / 100;
        assertEquals(scaleToFitDrawingSize, mIconView.getIconScale(), 0.01f);
    }

    @Test
    public void testUpdateIconScale_constrainedDrawableWidthLargerThanDpIconSize() {
        int dpIconSize = 60;
        int dpDrawingSize = 30;
        // the icon view layout size would be 60x150
        //   (the height is always 150 due to TEST_STATUS_BAR_HEIGHT)
        setUpIconView(dpIconSize, dpDrawingSize, dpIconSize);
        mIconView.setNotification(getMockSbn());
        // the raw drawable size is 100x50. When put the drawable into iconView whose
        // layout size is 60x150, the drawable size would be constrained to 60x30
        setIconDrawableWithSize(/* width= */ 100, /* height= */ 50);
        mIconView.maybeUpdateIconScaleDimens();

        // WHEN constrained drawable larger side length 60 >= dpIconSize
        // THEN the icon is scaled down from larger side length 60 to ensure both side
        //      length fit in dpDrawingSize.
        float scaleToFitDrawingSize = (float) dpDrawingSize / 60;
        assertEquals(scaleToFitDrawingSize, mIconView.getIconScale(), 0.01f);
    }

    @Test
    public void testUpdateIconScale_smallerFontAndRawDrawableSizeLessThanDpIconSize() {
        int dpIconSize = 60;
        int dpDrawingSize = 30;
        // smaller font scaling causes the spIconSize < dpIconSize
        int spIconSize = 40;
        // the icon view layout size would be 40x150
        //   (the height is always 150 due to TEST_STATUS_BAR_HEIGHT)
        setUpIconView(dpIconSize, dpDrawingSize, spIconSize);
        mIconView.setNotification(getMockSbn());
        // the raw drawable size is 50x50. When put the drawable into iconView whose
        // layout size is 40x150, the drawable size would be constrained to 40x40
        setIconDrawableWithSize(/* width= */ 50, /* height= */ 50);
        mIconView.maybeUpdateIconScaleDimens();

        // WHEN both the raw/constrained drawable width/height are less than dpIconSize,
        // THEN the icon is scaled up from constrained drawable size to the raw drawable size
        float scaleToBackRawDrawableSize = (float) 50 / 40;
        // THEN the icon is scaled down from dpIconSize to fit the dpDrawingSize
        float scaleToFitDrawingSize = (float) dpDrawingSize / dpIconSize;
        // THEN the scaled icon should be scaled down further to fit spIconSize
        float scaleToFitSpIconSize = (float) spIconSize / dpIconSize;
        assertEquals(scaleToBackRawDrawableSize * scaleToFitDrawingSize * scaleToFitSpIconSize,
                mIconView.getIconScale(), 0.01f);
    }

    @Test
    public void testUpdateIconScale_smallerFontAndConstrainedDrawableSizeLessThanDpIconSize() {
        int dpIconSize = 60;
        int dpDrawingSize = 30;
        // smaller font scaling causes the spIconSize < dpIconSize
        int spIconSize = 40;
        // the icon view layout size would be 40x150
        //   (the height is always 150 due to TEST_STATUS_BAR_HEIGHT)
        setUpIconView(dpIconSize, dpDrawingSize, spIconSize);
        mIconView.setNotification(getMockSbn());
        // the raw drawable size is 70x70. When put the drawable into iconView whose
        // layout size is 40x150, the drawable size would be constrained to 40x40
        setIconDrawableWithSize(/* width= */ 70, /* height= */ 70);
        mIconView.maybeUpdateIconScaleDimens();

        // WHEN the raw drawable width/height are larger than dpIconSize,
        //      but the constrained drawable width/height are less than dpIconSize,
        // THEN the icon is scaled up from constrained drawable size to fit dpIconSize
        float scaleToFitDpIconSize = (float) dpIconSize / 40;
        // THEN the icon is scaled down from dpIconSize to fit the dpDrawingSize
        float scaleToFitDrawingSize = (float) dpDrawingSize / dpIconSize;
        // THEN the scaled icon should be scaled down further to fit spIconSize
        float scaleToFitSpIconSize = (float) spIconSize / dpIconSize;
        assertEquals(scaleToFitDpIconSize * scaleToFitDrawingSize * scaleToFitSpIconSize,
                mIconView.getIconScale(), 0.01f);
    }

    @Test
    public void testUpdateIconScale_smallerFontAndConstrainedDrawableHeightLargerThanDpIconSize() {
        int dpIconSize = 60;
        int dpDrawingSize = 30;
        // smaller font scaling causes the spIconSize < dpIconSize
        int spIconSize = 40;
        // the icon view layout size would be 40x150
        //   (the height is always 150 due to TEST_STATUS_BAR_HEIGHT)
        setUpIconView(dpIconSize, dpDrawingSize, spIconSize);
        mIconView.setNotification(getMockSbn());
        // the raw drawable size is 50x100. When put the drawable into iconView whose
        // layout size is 40x150, the drawable size would be constrained to 40x80
        setIconDrawableWithSize(/* width= */ 50, /* height= */ 100);
        mIconView.maybeUpdateIconScaleDimens();

        // WHEN constrained drawable larger side length 80 >= dpIconSize
        // THEN the icon is scaled down from larger side length 80 to ensure both side
        //      length fit in dpDrawingSize.
        float scaleToFitDrawingSize = (float) dpDrawingSize / 80;
        // THEN the scaled icon should be scaled down further to fit spIconSize
        float scaleToFitSpIconSize = (float) spIconSize / dpIconSize;
        assertEquals(scaleToFitDrawingSize * scaleToFitSpIconSize, mIconView.getIconScale(), 0.01f);
    }

    @Test
    public void testUpdateIconScale_largerFontAndConstrainedDrawableSizeLessThanDpIconSize() {
        int dpIconSize = 60;
        int dpDrawingSize = 30;
        // larger font scaling causes the spIconSize > dpIconSize
        int spIconSize = 80;
        // the icon view layout size would be 80x150
        //   (the height is always 150 due to TEST_STATUS_BAR_HEIGHT)
        setUpIconView(dpIconSize, dpDrawingSize, spIconSize);
        mIconView.setNotification(getMockSbn());
        // the raw drawable size is 50x50. When put the drawable into iconView whose
        // layout size is 80x150, the drawable size would not be constrained and thus keep 50x50
        setIconDrawableWithSize(/* width= */ 50, /* height= */ 50);
        mIconView.maybeUpdateIconScaleDimens();

        // WHEN both the constrained drawable width/height are less than dpIconSize,
        // THEN the icon is scaled down from dpIconSize to fit the dpDrawingSize
        float scaleToFitDrawingSize = (float) dpDrawingSize / dpIconSize;
        // THEN the scaled icon should be scaled up to fit spIconSize
        float scaleToFitSpIconSize = (float) spIconSize / dpIconSize;
        assertEquals(scaleToFitDrawingSize * scaleToFitSpIconSize, mIconView.getIconScale(), 0.01f);
    }

    @Test
    public void testUpdateIconScale_largerFontAndConstrainedDrawableHeightLargerThanDpIconSize() {
        int dpIconSize = 60;
        int dpDrawingSize = 30;
        // larger font scaling causes the spIconSize > dpIconSize
        int spIconSize = 80;
        // the icon view layout size would be 80x150
        //   (the height is always 150 due to TEST_STATUS_BAR_HEIGHT)
        setUpIconView(dpIconSize, dpDrawingSize, spIconSize);
        mIconView.setNotification(getMockSbn());
        // the raw drawable size is 50x100. When put the drawable into iconView whose
        // layout size is 80x150, the drawable size would not be constrained and thus keep 50x100
        setIconDrawableWithSize(/* width= */ 50, /* height= */ 100);
        mIconView.maybeUpdateIconScaleDimens();

        // WHEN constrained drawable larger side length 100 >= dpIconSize
        // THEN the icon is scaled down from larger side length 100 to ensure both side
        //      length fit in dpDrawingSize.
        float scaleToFitDrawingSize = (float) dpDrawingSize / 100;
        // THEN the scaled icon should be scaled up to fit spIconSize
        float scaleToFitSpIconSize = (float) spIconSize / dpIconSize;
        assertEquals(scaleToFitDrawingSize * scaleToFitSpIconSize, mIconView.getIconScale(), 0.01f);
    }

    @Test
    public void testUpdateIconScale_largerFontAndConstrainedDrawableWidthLargerThanDpIconSize() {
        int dpIconSize = 60;
        int dpDrawingSize = 30;
        // larger font scaling causes the spIconSize > dpIconSize
        int spIconSize = 80;
        // the icon view layout size would be 80x150
        //   (the height is always 150 due to TEST_STATUS_BAR_HEIGHT)
        setUpIconView(dpIconSize, dpDrawingSize, spIconSize);
        mIconView.setNotification(getMockSbn());
        // the raw drawable size is 100x50. When put the drawable into iconView whose
        // layout size is 80x150, the drawable size would not be constrained and thus keep 80x40
        setIconDrawableWithSize(/* width= */ 100, /* height= */ 50);
        mIconView.maybeUpdateIconScaleDimens();

        // WHEN constrained drawable larger side length 80 >= dpIconSize
        // THEN the icon is scaled down from larger side length 80 to ensure both side
        //      length fit in dpDrawingSize.
        float scaleToFitDrawingSize = (float) dpDrawingSize / 80;
        // THEN the scaled icon should be scaled up to fit spIconSize
        float scaleToFitSpIconSize = (float) spIconSize / dpIconSize;
        assertEquals(scaleToFitDrawingSize * scaleToFitSpIconSize,
                mIconView.getIconScale(), 0.01f);
    }

    private static StatusBarNotification getMockSbn() {
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        when(sbn.getNotification()).thenReturn(mock(Notification.class));
        return sbn;
    }

    /**
     * Setup iconView dimens for testing. The result icon view layout width would
     * be spIconSize and height would be 150.
     *
     * @param dpIconSize corresponding to status_bar_icon_size
     * @param dpDrawingSize corresponding to status_bar_icon_drawing_size
     * @param spIconSize corresponding to status_bar_icon_size_sp under different font scaling
     */
    private void setUpIconView(int dpIconSize, int dpDrawingSize, int spIconSize) {
        mIconView.setIncreasedSize(false);
        mIconView.mOriginalStatusBarIconSize = dpIconSize;
        mIconView.mStatusBarIconDrawingSize = dpDrawingSize;

        mIconView.mNewStatusBarIconSize = spIconSize;
        mIconView.mScaleToFitNewIconSize = (float) spIconSize / dpIconSize;

        // the layout width would be spIconSize + 2 * iconPadding, and we assume iconPadding
        // is 0 here.
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(spIconSize, TEST_STATUS_BAR_HEIGHT);
        mIconView.setLayoutParams(lp);
    }

    private void setIconDrawableWithSize(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888);
        Icon icon = Icon.createWithBitmap(bitmap);
        mStatusBarIcon = new StatusBarIcon(UserHandle.ALL, "mockPackage",
                icon, 0, 0, "", StatusBarIcon.Type.SystemIcon);
        // Since we only want to verify icon scale logic here, we directly use
        // {@link StatusBarIconView#setImageDrawable(Drawable)} to set the image drawable
        // to iconView instead of call {@link StatusBarIconView#set(StatusBarIcon)}. It's to prevent
        // the icon drawable size being scaled down when internally calling
        // {@link StatusBarIconView#getIcon(Context,Context,StatusBarIcon)}.
        mIconView.setImageDrawable(icon.loadDrawable(mContext));
    }
}
