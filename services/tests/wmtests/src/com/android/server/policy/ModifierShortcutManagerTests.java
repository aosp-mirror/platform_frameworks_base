/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.policy;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.KeyboardShortcutInfo;

import androidx.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.util.Collections;

/**
 * Test class for {@link ModifierShortcutManager}.
 *
 * Build/Install/Run:
 *  atest ModifierShortcutManagerTests
 */

@SmallTest
@EnableFlags(com.android.hardware.input.Flags.FLAG_MODIFIER_SHORTCUT_MANAGER_REFACTOR)
public class ModifierShortcutManagerTests {

    @ClassRule public static final SetFlagsRule.ClassRule SET_FLAGS_CLASS_RULE =
            new SetFlagsRule.ClassRule();
    @Rule public final SetFlagsRule mSetFlagsRule = SET_FLAGS_CLASS_RULE.createSetFlagsRule();

    private ModifierShortcutManager mModifierShortcutManager;
    private Handler mHandler;
    private Context mContext;
    private Resources mResources;
    private PackageManager mPackageManager;

    @Before
    public void setUp() {
        mHandler = new Handler(Looper.getMainLooper());
        mContext = spy(getInstrumentation().getTargetContext());
        mResources = spy(mContext.getResources());
        mPackageManager = spy(mContext.getPackageManager());

        XmlResourceParser testBookmarks = mResources.getXml(
                com.android.frameworks.wmtests.R.xml.bookmarks);

        doReturn(mContext).when(mContext).createContextAsUser(anyObject(), anyInt());
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mResources.getXml(R.xml.bookmarks)).thenReturn(testBookmarks);
        try {
            // Keep packageName / className in sync with
            // services/tests/wmtests/res/xml/bookmarks.xml
            ActivityInfo testActivityInfo = new ActivityInfo();
            testActivityInfo.applicationInfo = new ApplicationInfo();
            testActivityInfo.packageName =
                    testActivityInfo.applicationInfo.packageName = "com.test";
            ResolveInfo testResolveInfo = new ResolveInfo();
            testResolveInfo.activityInfo = testActivityInfo;

            doReturn(testActivityInfo).when(mPackageManager).getActivityInfo(
                    eq(new ComponentName("com.test", "com.test.BookmarkTest")), anyInt());
            doReturn(testResolveInfo).when(mPackageManager).resolveActivity(anyObject(), anyInt());
            doThrow(new PackageManager.NameNotFoundException("com.test3")).when(mPackageManager)
                    .getActivityInfo(eq(new ComponentName("com.test3", "com.test.BookmarkTest")),
                        anyInt());
        } catch (PackageManager.NameNotFoundException ignored) { }
        doReturn(new String[] { "com.test" }).when(mPackageManager)
                .canonicalToCurrentPackageNames(aryEq(new String[] { "com.test2" }));


        mModifierShortcutManager = new ModifierShortcutManager(
                mContext, mHandler, UserHandle.SYSTEM);
    }

    @Test
    public void test_getApplicationLaunchKeyboardShortcuts() {
        // Expected values here determined by the number of shortcuts defined in
        // services/tests/wmtests/res/xml/bookmarks.xml

        // Total valid shortcuts.
        KeyboardShortcutGroup group =
                mModifierShortcutManager.getApplicationLaunchKeyboardShortcuts(-1);
        assertEquals(13, group.getItems().size());

        // Total valid shift shortcuts.
        assertEquals(3, group.getItems().stream()
                .filter(s -> s.getModifiers() == (KeyEvent.META_SHIFT_ON | KeyEvent.META_META_ON))
                .count());
    }

    @Test
    public void test_shortcutInfoFromIntent_appIntent() {
        Intent mockIntent = mock(Intent.class);
        ActivityInfo mockActivityInfo = mock(ActivityInfo.class);
        when(mockActivityInfo.loadLabel(anyObject())).thenReturn("label");
        mockActivityInfo.packageName = "android";
        when(mockActivityInfo.getIconResource()).thenReturn(R.drawable.sym_def_app_icon);
        when(mockIntent.resolveActivityInfo(anyObject(), anyInt())).thenReturn(mockActivityInfo);

        KeyboardShortcutInfo info = mModifierShortcutManager.shortcutInfoFromIntent(
                'a', mockIntent, true);

        assertEquals("label", info.getLabel().toString());
        assertEquals('a', info.getBaseCharacter());
        assertEquals(R.drawable.sym_def_app_icon, info.getIcon().getResId());
        assertEquals(KeyEvent.META_META_ON | KeyEvent.META_SHIFT_ON, info.getModifiers());

    }

    @Test
    public void test_shortcutInfoFromIntent_resolverIntent() {
        Intent mockIntent = mock(Intent.class);
        Intent mockSelector = mock(Intent.class);
        ActivityInfo mockActivityInfo = mock(ActivityInfo.class);
        mockActivityInfo.name = com.android.internal.app.ResolverActivity.class.getName();
        when(mockIntent.resolveActivityInfo(anyObject(), anyInt())).thenReturn(mockActivityInfo);
        when(mockIntent.getSelector()).thenReturn(mockSelector);
        when(mockSelector.getCategories()).thenReturn(
                Collections.singleton(Intent.CATEGORY_APP_BROWSER));

        KeyboardShortcutInfo info = mModifierShortcutManager.shortcutInfoFromIntent(
                'a', mockIntent, false);

        assertEquals(mContext.getString(R.string.keyboard_shortcut_group_applications_browser),
                info.getLabel().toString());
        assertEquals('a', info.getBaseCharacter());
        assertEquals(R.drawable.sym_def_app_icon, info.getIcon().getResId());
        assertEquals(KeyEvent.META_META_ON, info.getModifiers());

        // validate that an unknown category that we can't present a label to the user for
        // returns null shortcut info.
        when(mockSelector.getCategories()).thenReturn(
                Collections.singleton("not_a_category"));
        assertEquals(null,  mModifierShortcutManager.shortcutInfoFromIntent(
                'a', mockIntent, false));
    }

    @Test
    public void test_getIntentCategoryLabel() {
        assertEquals(
                mContext.getString(R.string.keyboard_shortcut_group_applications_browser),
                ModifierShortcutManager.getIntentCategoryLabel(
                    mContext, Intent.CATEGORY_APP_BROWSER));
        assertEquals(
                mContext.getString(R.string.keyboard_shortcut_group_applications_contacts),
                ModifierShortcutManager.getIntentCategoryLabel(
                    mContext, Intent.CATEGORY_APP_CONTACTS));
        assertEquals(
                mContext.getString(R.string.keyboard_shortcut_group_applications_email),
                ModifierShortcutManager.getIntentCategoryLabel(
                    mContext, Intent.CATEGORY_APP_EMAIL));
        assertEquals(
                mContext.getString(R.string.keyboard_shortcut_group_applications_calendar),
                ModifierShortcutManager.getIntentCategoryLabel(
                    mContext, Intent.CATEGORY_APP_CALENDAR));
        assertEquals(
                mContext.getString(R.string.keyboard_shortcut_group_applications_maps),
                ModifierShortcutManager.getIntentCategoryLabel(
                    mContext, Intent.CATEGORY_APP_MAPS));
        assertEquals(
                mContext.getString(R.string.keyboard_shortcut_group_applications_music),
                ModifierShortcutManager.getIntentCategoryLabel(
                    mContext, Intent.CATEGORY_APP_MUSIC));
        assertEquals(
                mContext.getString(R.string.keyboard_shortcut_group_applications_sms),
                ModifierShortcutManager.getIntentCategoryLabel(
                    mContext, Intent.CATEGORY_APP_MESSAGING));
        assertEquals(
                mContext.getString(R.string.keyboard_shortcut_group_applications_calculator),
                ModifierShortcutManager.getIntentCategoryLabel(
                    mContext, Intent.CATEGORY_APP_CALCULATOR));
        assertEquals(null, ModifierShortcutManager.getIntentCategoryLabel(mContext, "foo"));
    }
}
