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

package com.android.systemui;

import static junit.framework.Assert.fail;

import static org.junit.Assert.assertEquals;

import android.annotation.DrawableRes;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;
import android.util.TypedValue;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.XmlUtils;
import com.android.systemui.tests.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class IconPackOverlayTest extends SysuiTestCase {

    private static final String[] ICON_PACK_OVERLAY_PACKAGES = {
            "com.android.theme.icon_pack.circular.systemui",
            "com.android.theme.icon_pack.rounded.systemui",
            "com.android.theme.icon_pack.filled.systemui",
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

    private Resources mResources;
    private TypedArray mOverlayableIcons;

    @Before
    public void setup() {
        mResources = mContext.getResources();
        mOverlayableIcons = mResources.obtainTypedArray(R.array.overlayable_icons);
    }

    @After
    public void teardown() {
        mOverlayableIcons.recycle();
    }

    /**
     * Ensure that all icons contained in overlayable_icons_test.xml exist in all 3 overlay icon
     * packs for systemui. This test fails if you remove or rename an overlaid icon. If so,
     * make the same change to the corresponding drawables in {@link #ICON_PACK_OVERLAY_PACKAGES}.
     */
    @Test
    public void testIconPack_containAllOverlayedIcons() {
        StringBuilder errors = new StringBuilder();

        for (String overlayPackage : ICON_PACK_OVERLAY_PACKAGES) {
            Resources overlayResources;
            try {
                overlayResources = mContext.getPackageManager()
                        .getResourcesForApplication(overlayPackage);
            } catch (PackageManager.NameNotFoundException e) {
                continue; // No need to test overlay resources if apk is not on the system.
            }

            for (int i = 0; i < mOverlayableIcons.length(); i++) {
                int sysuiRid = mOverlayableIcons.getResourceId(i, 0);
                String sysuiResourceName = mResources.getResourceName(sysuiRid);
                String overlayResourceName = sysuiResourceName
                        .replace(mContext.getPackageName(), overlayPackage);
                if (overlayResources.getIdentifier(overlayResourceName, null, null)
                        == Resources.ID_NULL) {
                    errors.append(String.format("[%s] is not contained in overlay package [%s]",
                            overlayResourceName, overlayPackage));
                }
            }
        }

        if (!TextUtils.isEmpty(errors)) {
            fail(errors.toString());
        }
    }

    /**
     * Ensures that all overlay icons have the same values for {@link #VECTOR_ATTRIBUTES} as the
     * underlying drawable in systemui. To fix this test, make the attribute change to all of the
     * corresponding drawables in {@link #ICON_PACK_OVERLAY_PACKAGES}.
     */
    @Test
    public void testIconPacks_haveEqualVectorDrawableAttributes() {
        StringBuilder errors = new StringBuilder();

        for (String overlayPackage : ICON_PACK_OVERLAY_PACKAGES) {
            Resources overlayResources;
            try {
                overlayResources = mContext.getPackageManager()
                        .getResourcesForApplication(overlayPackage);
            } catch (PackageManager.NameNotFoundException e) {
                continue; // No need to test overlay resources if apk is not on the system.
            }

            for (int i = 0; i < mOverlayableIcons.length(); i++) {
                int sysuiRid = mOverlayableIcons.getResourceId(i, 0);
                String sysuiResourceName = mResources.getResourceName(sysuiRid);
                TypedArray sysuiAttrs = getAVDAttributes(mResources, sysuiRid);
                if (sysuiAttrs == null) {
                    errors.append(String.format("[%s] does not exist or is not a valid AVD.",
                            sysuiResourceName));
                    continue;
                }

                String overlayResourceName = sysuiResourceName
                        .replace(mContext.getPackageName(), overlayPackage);
                int overlayRid = overlayResources.getIdentifier(overlayResourceName, null, null);
                TypedArray overlayAttrs = getAVDAttributes(overlayResources, overlayRid);
                if (overlayAttrs == null) {
                    errors.append(String.format("[%s] does not exist or is not a valid AVD.",
                            overlayResourceName));
                    continue;
                }

                if (!attributesEquals(sysuiAttrs, overlayAttrs)) {
                    errors.append(String.format("[%s] AVD attributes do not match [%s]\n",
                            sysuiResourceName, overlayResourceName));
                }
                sysuiAttrs.recycle();
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
            return resources.obtainAttributes(parser, VECTOR_ATTRIBUTES);
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

    private boolean attributesEquals(TypedValue target, TypedValue overlay) {
        return target.type == overlay.type && target.data == overlay.data;
    }
}
