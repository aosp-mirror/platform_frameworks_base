/*
 * Copyright (C) 2024 The Android Open Source Project
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
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.KeyboardShortcutInfo;

import androidx.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

/**
 * Test class for {@link ModifierShortcutManager}.
 *
 * Build/Install/Run:
 *  atest ModifierShortcutManagerTests
 */

@SmallTest
public class ModifierShortcutManagerTests {
    private ModifierShortcutManager mModifierShortcutManager;
    private Handler mHandler;
    private Context mContext;
    private Resources mResources;

    @Before
    public void setUp() {
        mHandler = new Handler(Looper.getMainLooper());
        mContext = spy(getInstrumentation().getTargetContext());
        mResources = spy(mContext.getResources());

        XmlResourceParser testBookmarks = mResources.getXml(
                com.android.frameworks.wmtests.R.xml.bookmarks);

        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getXml(R.xml.bookmarks)).thenReturn(testBookmarks);

        mModifierShortcutManager = new ModifierShortcutManager(mContext, mHandler);
    }

    @Test
    public void test_getApplicationLaunchKeyboardShortcuts() {
        KeyboardShortcutGroup group =
                mModifierShortcutManager.getApplicationLaunchKeyboardShortcuts(-1);
        assertEquals(8, group.getItems().size());
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
