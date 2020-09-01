/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.theme.icon;

import static junit.framework.Assert.fail;

import static org.junit.Assert.assertEquals;

import android.annotation.DrawableRes;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;
import android.util.TypedValue;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.XmlUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class IconPackOverlayTest {
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String[] SYSTEMUI_ICON_PACK_OVERLAY_PACKAGES = {
            "com.android.theme.icon_pack.circular.systemui",
            "com.android.theme.icon_pack.rounded.systemui",
            "com.android.theme.icon_pack.filled.systemui",
    };
    private static final String ANDROID_PACKAGE = "android";
    private static final String[] ANDROID_ICON_PACK_OVERLAY_PACKAGES = {
            "com.android.theme.icon_pack.circular.android",
            "com.android.theme.icon_pack.rounded.android",
            "com.android.theme.icon_pack.filled.android",
    };
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String[] SETTINGS_ICON_PACK_OVERLAY_PACKAGES = {
            "com.android.theme.icon_pack.circular.settings",
            "com.android.theme.icon_pack.rounded.settings",
            "com.android.theme.icon_pack.filled.settings",
    };

    private static final int[] VECTOR_ATTRIBUTES = {
            android.R.attr.tint,
            android.R.attr.height,
            android.R.attr.width,
            android.R.attr.alpha,
            android.R.attr.autoMirrored,
    };

    private final TypedValue mTargetTypedValue = new TypedValue();
    private final TypedValue mOverlayTypedValue = new TypedValue();
    private Context mContext;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getContext();
    }

    /**
     * Ensure that drawable icons in icon packs targeting android have corresponding underlying
     * drawables in android. This test fails if you remove/rename an overlaid icon in android.
     * If so, make the same change to the corresponding drawables in the overlay packages.
     */
    @Test
    public void testAndroidFramework_containsAllOverlayedIcons() {
        containsAllOverlayedIcons(ANDROID_PACKAGE, ANDROID_ICON_PACK_OVERLAY_PACKAGES);
    }

    /**
     * Ensure that drawable icons in icon packs targeting settings have corresponding underlying
     * drawables in settings. This test fails if you remove/rename an overlaid icon in settings.
     * If so, make the same change to the corresponding drawables in the overlay packages.
     */
    @Test
    public void testSettings_containsAllOverlayedIcons() {
        containsAllOverlayedIcons(SETTINGS_PACKAGE, SETTINGS_ICON_PACK_OVERLAY_PACKAGES);
    }

    /**
     * Ensure that drawable icons in icon packs targeting systemui have corresponding underlying
     * drawables in systemui. This test fails if you remove/rename an overlaid icon in systemui.
     * If so, make the same change to the corresponding drawables in the overlay packages.
     */
    @Test
    public void testSystemUI_containAllOverlayedIcons() {
        containsAllOverlayedIcons(SYSTEMUI_PACKAGE, SYSTEMUI_ICON_PACK_OVERLAY_PACKAGES);
    }

    /**
     * Ensures that all overlay icons have the same values for {@link #VECTOR_ATTRIBUTES} as the
     * underlying drawable in android. To fix this test, make the attribute change to all of the
     * corresponding drawables in the overlay packages.
     */
    @Test
    public void testAndroidFramework_hasEqualVectorDrawableAttributes() {
        hasEqualVectorDrawableAttributes(ANDROID_PACKAGE, ANDROID_ICON_PACK_OVERLAY_PACKAGES);
    }

    /**
     * Ensures that all overlay icons have the same values for {@link #VECTOR_ATTRIBUTES} as the
     * underlying drawable in settings. To fix this test, make the attribute change to all of the
     * corresponding drawables in the overlay packages.
     */
    @Test
    public void testSettings_hasEqualVectorDrawableAttributes() {
        hasEqualVectorDrawableAttributes(SETTINGS_PACKAGE, SETTINGS_ICON_PACK_OVERLAY_PACKAGES);
    }

    /**
     * Ensures that all overlay icons have the same values for {@link #VECTOR_ATTRIBUTES} as the
     * underlying drawable in systemui. To fix this test, make the attribute change to all of the
     * corresponding drawables in the overlay packages.
     */
    @Test
    public void testSystemUI_hasEqualVectorDrawableAttributes() {
        hasEqualVectorDrawableAttributes(SYSTEMUI_PACKAGE, SYSTEMUI_ICON_PACK_OVERLAY_PACKAGES);
    }

    private void containsAllOverlayedIcons(String targetPkg, String[] overlayPkgs) {
        final Resources targetResources;
        try {
            targetResources = mContext.getPackageManager()
                    .getResourcesForApplication(targetPkg);
        } catch (PackageManager.NameNotFoundException e) {
            return; // No need to test overlays if target package does not exist on the system.
        }

        StringBuilder errors = new StringBuilder();
        for (String overlayPackage : overlayPkgs) {
            final ApplicationInfo info;
            try {
                info = mContext.getPackageManager().getApplicationInfo(overlayPackage, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue; // No need to test overlay resources if apk is not on the system.
            }
            final List<String> iconPackDrawables = getDrawablesFromOverlay(info);
            for (int i = 0; i < iconPackDrawables.size(); i++) {
                String resourceName = iconPackDrawables.get(i);
                int targetRid = targetResources.getIdentifier(resourceName, "drawable", targetPkg);
                if (targetRid == Resources.ID_NULL) {
                    errors.append(String.format("[%s] is not contained in the target package [%s]",
                            resourceName, targetPkg));
                }
            }
        }

        if (!TextUtils.isEmpty(errors)) {
            fail(errors.toString());
        }
    }

    private void hasEqualVectorDrawableAttributes(String targetPkg, String[] overlayPackages) {
        final Resources targetRes;
        try {
            targetRes = mContext.getPackageManager().getResourcesForApplication(targetPkg);
        } catch (PackageManager.NameNotFoundException e) {
            return; // No need to test overlays if target package does not exist on the system.
        }

        StringBuilder errors = new StringBuilder();

        for (String overlayPkg : overlayPackages) {
            final ApplicationInfo info;
            try {
                info = mContext.getPackageManager().getApplicationInfo(overlayPkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                continue; // No need to test overlay resources if apk is not on the system.
            }
            final List<String> iconPackDrawables = getDrawablesFromOverlay(info);
            final Resources overlayRes;
            try {
                overlayRes = mContext.getPackageManager().getResourcesForApplication(overlayPkg);
            } catch (PackageManager.NameNotFoundException e) {
                continue; // No need to test overlay resources if apk is not on the system.
            }

            for (int i = 0; i < iconPackDrawables.size(); i++) {
                String resourceName = iconPackDrawables.get(i);
                int targetRid = targetRes.getIdentifier(resourceName, "drawable", targetPkg);
                int overlayRid = overlayRes.getIdentifier(resourceName, "drawable", overlayPkg);
                TypedArray targetAttrs = getAVDAttributes(targetRes, targetRid);
                if (targetAttrs == null) {
                    errors.append(String.format(
                            "[%s] in pkg [%s] does not exist or is not a valid vector drawable.\n",
                            resourceName, targetPkg));
                    continue;
                }

                TypedArray overlayAttrs = getAVDAttributes(overlayRes, overlayRid);
                if (overlayAttrs == null) {
                    errors.append(String.format(
                            "[%s] in pkg [%s] does not exist or is not a valid vector drawable.\n",
                            resourceName, overlayPkg));
                    continue;
                }

                if (!attributesEquals(targetAttrs, overlayAttrs)) {
                    errors.append(String.format("[drawable/%s] in [%s] does not have the same "
                                    + "attributes as the corresponding drawable from [%s]\n",
                            resourceName, targetPkg, overlayPkg));
                }
                targetAttrs.recycle();
                overlayAttrs.recycle();
            }
        }

        if (!TextUtils.isEmpty(errors)) {
            fail(errors.toString());
        }
    }

    private TypedArray getAVDAttributes(Resources resources, @DrawableRes int rid) {
        try {
            XmlResourceParser parser = resources.getXml(rid);
            XmlUtils.nextElement(parser);
            // Always use the the test apk theme to resolve attributes.
            return mContext.getTheme().obtainStyledAttributes(parser, VECTOR_ATTRIBUTES, 0, 0);
        } catch (XmlPullParserException | IOException  | Resources.NotFoundException e) {
            return null;
        }
    }

    private boolean attributesEquals(TypedArray target, TypedArray overlay) {
        assertEquals(target.length(), overlay.length());
        for (int i = 0; i < target.length(); i++) {
            target.getValue(i, mTargetTypedValue);
            overlay.getValue(i, mOverlayTypedValue);
            if (!attributesEquals(mTargetTypedValue, mOverlayTypedValue)) {
                return false;
            }
        }
        return true;
    }

    private static boolean attributesEquals(TypedValue target, TypedValue overlay) {
        return target.type == overlay.type && target.data == overlay.data;
    }

    private static List<String> getDrawablesFromOverlay(ApplicationInfo applicationInfo) {
        try {
            final ArrayList<String> drawables = new ArrayList<>();
            ZipFile file = new ZipFile(applicationInfo.sourceDir);
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry element = entries.nextElement();
                String name = element.getName();
                if (name.contains("/drawable/")) {
                    name = name.substring(name.lastIndexOf('/') + 1);
                    if (name.contains(".")) {
                        name = name.substring(0, name.indexOf('.'));
                    }
                    drawables.add(name);
                }
            }
            return drawables;
        } catch (IOException e) {
            fail(String.format("Failed to retrieve drawables from package [%s] with message [%s]",
                    applicationInfo.packageName, e.getMessage()));
            return null;
        }
    }
}
