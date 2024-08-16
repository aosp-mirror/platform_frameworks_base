/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.internal.accessibility.util;

import static com.android.internal.accessibility.util.AccessibilityUtils.ACCESSIBILITY_MENU_IN_SYSTEM;
import static com.android.internal.accessibility.util.AccessibilityUtils.MENU_SERVICE_RELATIVE_CLASS_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.text.ParcelableSpan;
import android.text.SpannableString;
import android.text.style.LocaleSpan;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Locale;

/**
 * Unit tests for AccessibilityUtils.
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityUtilsTest {
    private static final int USER_ID = 123;
    @Mock
    private PackageManager mMockPackageManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void textOrSpanChanged_stringChange_returnTextChange() {
        final CharSequence beforeText = "a";

        final CharSequence afterText = "b";

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertThat(type).isEqualTo(AccessibilityUtils.TEXT);
    }

    @Test
    public void textOrSpanChanged_stringNotChange_returnNoneChange() {
        final CharSequence beforeText = "a";

        final CharSequence afterText = "a";

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertThat(type).isEqualTo(AccessibilityUtils.NONE);
    }

    @Test
    public void textOrSpanChanged_nonSpanToNonParcelableSpan_returnNoneChange() {
        final Object nonParcelableSpan = new Object();
        final CharSequence beforeText = new SpannableString("a");

        final SpannableString afterText = new SpannableString("a");
        afterText.setSpan(nonParcelableSpan, 0, 1, 0);

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertThat(type).isEqualTo(AccessibilityUtils.NONE);
    }

    @Test
    public void textOrSpanChanged_nonSpanToParcelableSpan_returnParcelableSpanChange() {
        final ParcelableSpan parcelableSpan = new LocaleSpan(Locale.ENGLISH);
        final CharSequence beforeText = new SpannableString("a");

        final SpannableString afterText = new SpannableString("a");
        afterText.setSpan(parcelableSpan, 0, 1, 0);

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertThat(type).isEqualTo(AccessibilityUtils.PARCELABLE_SPAN);
    }

    @Test
    public void textOrSpanChanged_nonParcelableSpanToParcelableSpan_returnParcelableSpanChange() {
        final Object nonParcelableSpan = new Object();
        final ParcelableSpan parcelableSpan = new LocaleSpan(Locale.ENGLISH);
        final SpannableString beforeText = new SpannableString("a");
        beforeText.setSpan(nonParcelableSpan, 0, 1, 0);

        SpannableString afterText = new SpannableString("a");
        afterText.setSpan(parcelableSpan, 0, 1, 0);

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertThat(type).isEqualTo(AccessibilityUtils.PARCELABLE_SPAN);
    }

    @Test
    public void textOrSpanChanged_nonParcelableSpanChange_returnNoneChange() {
        final Object nonParcelableSpan = new Object();
        final SpannableString beforeText = new SpannableString("a");
        beforeText.setSpan(nonParcelableSpan, 0, 1, 0);

        final SpannableString afterText = new SpannableString("a");
        afterText.setSpan(nonParcelableSpan, 1, 1, 0);

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertThat(type).isEqualTo(AccessibilityUtils.NONE);
    }

    @Test
    public void textOrSpanChanged_parcelableSpanChange_returnParcelableSpanChange() {
        final ParcelableSpan parcelableSpan = new LocaleSpan(Locale.ENGLISH);
        final SpannableString beforeText = new SpannableString("a");
        beforeText.setSpan(parcelableSpan, 0, 1, 0);

        final SpannableString afterText = new SpannableString("a");
        afterText.setSpan(parcelableSpan, 1, 1, 0);

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertThat(type).isEqualTo(AccessibilityUtils.PARCELABLE_SPAN);
    }

    @Test
    public void getAccessibilityMenuComponentToMigrate_isNull_whenNoMenuComponents() {
        when(mMockPackageManager.queryIntentServicesAsUser(any(), any(),
                eq(USER_ID))).thenReturn(List.of());

        final ComponentName result = AccessibilityUtils.getAccessibilityMenuComponentToMigrate(
                mMockPackageManager, USER_ID);

        assertThat(result).isNull();
    }

    @Test
    public void getAccessibilityMenuComponentToMigrate_isNull_whenTooManyMenuComponents() {
        when(mMockPackageManager.queryIntentServicesAsUser(any(), any(),
                eq(USER_ID))).thenReturn(List.of(
                createResolveInfo(ComponentName.createRelative("external1",
                        MENU_SERVICE_RELATIVE_CLASS_NAME)),
                createResolveInfo(ComponentName.createRelative("external2",
                        MENU_SERVICE_RELATIVE_CLASS_NAME)),
                createResolveInfo(ComponentName.createRelative("external3",
                        MENU_SERVICE_RELATIVE_CLASS_NAME))));

        final ComponentName result = AccessibilityUtils.getAccessibilityMenuComponentToMigrate(
                mMockPackageManager, USER_ID);

        assertThat(result).isNull();
    }

    @Test
    public void getAccessibilityMenuComponentToMigrate_isNull_whenMenuInSystemNotFound() {
        when(mMockPackageManager.queryIntentServicesAsUser(any(), any(),
                eq(USER_ID))).thenReturn(List.of(
                createResolveInfo(ComponentName.createRelative("external1",
                        MENU_SERVICE_RELATIVE_CLASS_NAME)),
                createResolveInfo(ComponentName.createRelative("external2",
                        MENU_SERVICE_RELATIVE_CLASS_NAME))));

        final ComponentName result = AccessibilityUtils.getAccessibilityMenuComponentToMigrate(
                mMockPackageManager, USER_ID);

        assertThat(result).isNull();
    }

    @Test
    public void getAccessibilityMenuComponentToMigrate_returnsMenuOutsideSystem() {
        ComponentName menuOutsideSystem = ComponentName.createRelative("external1",
                MENU_SERVICE_RELATIVE_CLASS_NAME);
        when(mMockPackageManager.queryIntentServicesAsUser(any(), any(),
                eq(USER_ID))).thenReturn(List.of(
                createResolveInfo(menuOutsideSystem),
                createResolveInfo(ACCESSIBILITY_MENU_IN_SYSTEM)));

        final ComponentName result = AccessibilityUtils.getAccessibilityMenuComponentToMigrate(
                mMockPackageManager, USER_ID);

        assertThat(result).isEqualTo(menuOutsideSystem);
    }

    private static ResolveInfo createResolveInfo(ComponentName componentName) {
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = new ServiceInfo();
        resolveInfo.serviceInfo.packageName = componentName.getPackageName();
        resolveInfo.serviceInfo.name = componentName.getClassName();
        return resolveInfo;
    }
}
