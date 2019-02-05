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
 * limitations under the License.
 */
package com.android.server.pm;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertBundlesEqual;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertExpectException;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertWith;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.makeBundle;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.parceled;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.set;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.Manifest.permission;
import android.app.ActivityManager;
import android.app.Person;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.frameworks.servicestests.R;
import com.android.server.pm.ShortcutUser.PackageWithUser;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

/**
 * Tests for ShortcutService and ShortcutManager.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutManagerTest2 \
 -w com.android.frameworks.servicestests/androidx.test.runner.AndroidJUnitRunner
 */
@SmallTest
public class ShortcutManagerTest2 extends BaseShortcutManagerTest {
    // ShortcutInfo tests

    public void testShortcutInfoMissingMandatoryFields() {
        // Disable throttling.
        mService.updateConfigurationLocked(
                ShortcutService.ConfigConstants.KEY_MAX_UPDATES_PER_INTERVAL + "=99999999,"
                + ShortcutService.ConfigConstants.KEY_MAX_SHORTCUTS + "=99999999"
        );

        assertExpectException(
                IllegalArgumentException.class,
                "ID must be provided",
                () -> new ShortcutInfo.Builder(getTestContext()).build());

        assertExpectException(
                RuntimeException.class,
                "id cannot be empty",
                () -> new ShortcutInfo.Builder(getTestContext(), null));

        assertExpectException(
                RuntimeException.class,
                "id cannot be empty",
                () -> new ShortcutInfo.Builder(getTestContext(), ""));

        assertExpectException(
                RuntimeException.class,
                "intents cannot contain null",
                () -> new ShortcutInfo.Builder(getTestContext(), "id").setIntent(null));

        assertExpectException(
                RuntimeException.class,
                "action must be set",
                () -> new ShortcutInfo.Builder(getTestContext(), "id").setIntent(new Intent()));

        assertExpectException(
                RuntimeException.class,
                "action must be set",
                () -> new ShortcutInfo.Builder(getTestContext(), "id")
                        .setIntents(new Intent[]{new Intent("action"), new Intent()}));

        assertExpectException(
                RuntimeException.class,
                "activity cannot be null",
                () -> new ShortcutInfo.Builder(getTestContext(), "id").setActivity(null));

        assertExpectException(
                RuntimeException.class,
                "shortLabel cannot be empty",
                () -> new ShortcutInfo.Builder(getTestContext(), "id").setShortLabel(null));

        assertExpectException(
                RuntimeException.class,
                "shortLabel cannot be empty",
                () -> new ShortcutInfo.Builder(getTestContext(), "id").setShortLabel(""));

        assertExpectException(
                RuntimeException.class,
                "longLabel cannot be empty",
                () -> new ShortcutInfo.Builder(getTestContext(), "id").setLongLabel(null));

        assertExpectException(
                RuntimeException.class,
                "longLabel cannot be empty",
                () -> new ShortcutInfo.Builder(getTestContext(), "id").setLongLabel(""));

        assertExpectException(
                RuntimeException.class,
                "disabledMessage cannot be empty",
                () -> new ShortcutInfo.Builder(getTestContext(), "id").setDisabledMessage(null));

        assertExpectException(
                RuntimeException.class,
                "disabledMessage cannot be empty",
                () -> new ShortcutInfo.Builder(getTestContext(), "id").setDisabledMessage(""));

        assertExpectException(NullPointerException.class, "action must be set",
                () -> new ShortcutInfo.Builder(getTestContext(), "id").setIntent(new Intent()));

        assertExpectException(
                IllegalArgumentException.class, "Short label must be provided", () -> {
            ShortcutInfo si = new ShortcutInfo.Builder(getClientContext(), "id")
                    .setActivity(new ComponentName(getClientContext().getPackageName(), "s"))
                    .build();
            assertTrue(getManager().setDynamicShortcuts(list(si)));
        });

        // same for add.
        assertExpectException(
                IllegalArgumentException.class, "Short label must be provided", () -> {
            ShortcutInfo si = new ShortcutInfo.Builder(getClientContext(), "id")
                    .setActivity(new ComponentName(getClientContext().getPackageName(), "s"))
                    .build();
            assertTrue(getManager().addDynamicShortcuts(list(si)));
        });

        assertExpectException(NullPointerException.class, "Intent must be provided", () -> {
            ShortcutInfo si = new ShortcutInfo.Builder(getClientContext(), "id")
                    .setActivity(new ComponentName(getClientContext().getPackageName(), "s"))
                    .setShortLabel("x")
                    .build();
            assertTrue(getManager().setDynamicShortcuts(list(si)));
        });

        // same for add.
        assertExpectException(NullPointerException.class, "Intent must be provided", () -> {
            ShortcutInfo si = new ShortcutInfo.Builder(getClientContext(), "id")
                    .setActivity(new ComponentName(getClientContext().getPackageName(), "s"))
                    .setShortLabel("x")
                    .build();
            assertTrue(getManager().addDynamicShortcuts(list(si)));
        });

        assertExpectException(
                IllegalStateException.class, "does not belong to package", () -> {
            ShortcutInfo si = new ShortcutInfo.Builder(getClientContext(), "id")
                    .setActivity(new ComponentName("xxx", "s"))
                    .build();
            assertTrue(getManager().setDynamicShortcuts(list(si)));
        });

        // same for add.
        assertExpectException(
                IllegalStateException.class, "does not belong to package", () -> {
            ShortcutInfo si = new ShortcutInfo.Builder(getClientContext(), "id")
                    .setActivity(new ComponentName("xxx", "s"))
                    .build();
            assertTrue(getManager().addDynamicShortcuts(list(si)));
        });

        // Now all activities are not main.
        mMainActivityChecker = (component, userId) -> false;

        assertExpectException(
                IllegalStateException.class, "is not main", () -> {
                    ShortcutInfo si = new ShortcutInfo.Builder(getClientContext(), "id")
                            .setActivity(new ComponentName(getClientContext(), "s"))
                            .build();
                    assertTrue(getManager().setDynamicShortcuts(list(si)));
                });
        // For add
        assertExpectException(
                IllegalStateException.class, "is not main", () -> {
                    ShortcutInfo si = new ShortcutInfo.Builder(getClientContext(), "id")
                            .setActivity(new ComponentName(getClientContext(), "s"))
                            .build();
                    assertTrue(getManager().addDynamicShortcuts(list(si)));
                });
        // For update
        assertExpectException(
                IllegalStateException.class, "is not main", () -> {
                    ShortcutInfo si = new ShortcutInfo.Builder(getClientContext(), "id")
                            .setActivity(new ComponentName(getClientContext(), "s"))
                            .build();
                    assertTrue(getManager().updateShortcuts(list(si)));
                });
    }

    public void testShortcutInfoParcel() {
        setCaller(CALLING_PACKAGE_1, USER_10);
        ShortcutInfo si = parceled(new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setTitle("title")
                .setIntent(makeIntent("action", ShortcutActivity.class))
                .build());
        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals(USER_10, si.getUserId());
        assertEquals(HANDLE_USER_10, si.getUserHandle());
        assertEquals("id", si.getId());
        assertEquals("title", si.getTitle());
        assertEquals("action", si.getIntent().getAction());

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);

        si = new ShortcutInfo.Builder(getTestContext())
                .setId("id")
                .setActivity(new ComponentName("a", "b"))
                .setIcon(Icon.createWithResource(mClientContext, 123))
                .setTitle("title")
                .setText("text")
                .setDisabledMessage("dismes")
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setRank(123)
                .setPerson(makePerson("person", "personKey", "personUri"))
                .setLongLived()
                .setExtras(pb)
                .build();
        si.addFlags(ShortcutInfo.FLAG_PINNED);
        si.setBitmapPath("abc");
        si.setIconResourceId(456);

        si = parceled(si);

        assertEquals(getTestContext().getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(123, si.getIcon().getResId());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals("dismes", si.getDisabledMessage());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getRank());
        assertEquals("person", si.getPersons()[0].getName());
        assertEquals("personKey", si.getPersons()[0].getKey());
        assertEquals("personUri", si.getPersons()[0].getUri());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_LONG_LIVED, si.getFlags());
        assertEquals("abc", si.getBitmapPath());
        assertEquals(456, si.getIconResourceId());

        assertEquals(0, si.getTitleResId());
        assertEquals(null, si.getTitleResName());
        assertEquals(0, si.getTextResId());
        assertEquals(null, si.getTextResName());
        assertEquals(0, si.getDisabledMessageResourceId());
        assertEquals(null, si.getDisabledMessageResName());
    }

    public void testShortcutInfoParcel_resId() {
        setCaller(CALLING_PACKAGE_1, USER_10);
        ShortcutInfo si;

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);

        si = new ShortcutInfo.Builder(getTestContext())
                .setId("id")
                .setActivity(new ComponentName("a", "b"))
                .setIcon(Icon.createWithResource(mClientContext, 123))
                .setTitleResId(10)
                .setTextResId(11)
                .setDisabledMessageResId(12)
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setRank(123)
                .setExtras(pb)
                .build();
        si.addFlags(ShortcutInfo.FLAG_PINNED);
        si.setBitmapPath("abc");
        si.setIconResourceId(456);

        lookupAndFillInResourceNames(si);

        si = parceled(si);

        assertEquals(getTestContext().getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(123, si.getIcon().getResId());
        assertEquals(10, si.getTitleResId());
        assertEquals("r10", si.getTitleResName());
        assertEquals(11, si.getTextResId());
        assertEquals("r11", si.getTextResName());
        assertEquals(12, si.getDisabledMessageResourceId());
        assertEquals("r12", si.getDisabledMessageResName());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals("abc", si.getBitmapPath());
        assertEquals(456, si.getIconResourceId());
        assertEquals("string/r456", si.getIconResName());
    }

    public void testShortcutInfoClone() {
        setCaller(CALLING_PACKAGE_1, USER_11);

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setActivity(new ComponentName("a", "b"))
                .setIcon(Icon.createWithResource(mClientContext, 123))
                .setTitle("title")
                .setText("text")
                .setDisabledMessage("dismes")
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(123)
                .setPerson(makePerson("person", "personKey", "personUri"))
                .setLongLived()
                .setExtras(pb)
                .build();
        sorig.addFlags(ShortcutInfo.FLAG_PINNED);
        sorig.setBitmapPath("abc");
        sorig.setIconResourceId(456);

        lookupAndFillInResourceNames(sorig);

        ShortcutInfo si = sorig.clone(/* clone flags*/ 0);

        assertEquals(USER_11, si.getUserId());
        assertEquals(HANDLE_USER_11, si.getUserHandle());
        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(123, si.getIcon().getResId());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals("dismes", si.getDisabledMessage());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getRank());
        assertEquals("person", si.getPersons()[0].getName());
        assertEquals("personKey", si.getPersons()[0].getKey());
        assertEquals("personUri", si.getPersons()[0].getUri());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_LONG_LIVED, si.getFlags());
        assertEquals("abc", si.getBitmapPath());
        assertEquals(456, si.getIconResourceId());
        assertEquals("string/r456", si.getIconResName());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_CREATOR);

        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(null, si.getIcon());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals("dismes", si.getDisabledMessage());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getRank());
        assertEquals("person", si.getPersons()[0].getName());
        assertEquals("personKey", si.getPersons()[0].getKey());
        assertEquals("personUri", si.getPersons()[0].getUri());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_LONG_LIVED, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());
        assertEquals(null, si.getIconResName());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER);

        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(null, si.getIcon());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals("dismes", si.getDisabledMessage());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals(null, si.getIntent());
        assertEquals(123, si.getRank());
        assertEquals("person", si.getPersons()[0].getName());
        assertEquals("personKey", si.getPersons()[0].getKey());
        assertEquals("personUri", si.getPersons()[0].getUri());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_LONG_LIVED, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());
        assertEquals(null, si.getIconResName());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);

        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(null, si.getIcon());
        assertEquals(null, si.getTitle());
        assertEquals(null, si.getText());
        assertEquals(null, si.getDisabledMessage());
        assertEquals(null, si.getCategories());
        assertEquals(null, si.getIntent());
        assertEquals(0, si.getRank());
        assertEquals(null, si.getPersons());
        assertEquals(null, si.getExtras());

        assertEquals(ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_KEY_FIELDS_ONLY
                | ShortcutInfo.FLAG_LONG_LIVED, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());
        assertEquals(null, si.getIconResName());
    }

    public void testShortcutInfoClone_resId() {
        setCaller(CALLING_PACKAGE_1, USER_11);

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setActivity(new ComponentName("a", "b"))
                .setIcon(Icon.createWithResource(mClientContext, 123))
                .setTitleResId(10)
                .setTextResId(11)
                .setDisabledMessageResId(12)
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(123)
                .setExtras(pb)
                .build();
        sorig.addFlags(ShortcutInfo.FLAG_PINNED);
        sorig.setBitmapPath("abc");
        sorig.setIconResourceId(456);

        lookupAndFillInResourceNames(sorig);

        ShortcutInfo si = sorig.clone(/* clone flags*/ 0);

        assertEquals(USER_11, si.getUserId());
        assertEquals(HANDLE_USER_11, si.getUserHandle());
        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(123, si.getIcon().getResId());
        assertEquals(10, si.getTitleResId());
        assertEquals("r10", si.getTitleResName());
        assertEquals(11, si.getTextResId());
        assertEquals("r11", si.getTextResName());
        assertEquals(12, si.getDisabledMessageResourceId());
        assertEquals("r12", si.getDisabledMessageResName());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals("abc", si.getBitmapPath());
        assertEquals(456, si.getIconResourceId());
        assertEquals("string/r456", si.getIconResName());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_CREATOR);

        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(null, si.getIcon());
        assertEquals(10, si.getTitleResId());
        assertEquals(null, si.getTitleResName());
        assertEquals(11, si.getTextResId());
        assertEquals(null, si.getTextResName());
        assertEquals(12, si.getDisabledMessageResourceId());
        assertEquals(null, si.getDisabledMessageResName());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());
        assertEquals(null, si.getIconResName());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER);

        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(null, si.getIcon());
        assertEquals(10, si.getTitleResId());
        assertEquals(null, si.getTitleResName());
        assertEquals(11, si.getTextResId());
        assertEquals(null, si.getTextResName());
        assertEquals(12, si.getDisabledMessageResourceId());
        assertEquals(null, si.getDisabledMessageResName());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals(null, si.getIntent());
        assertEquals(123, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());
        assertEquals(null, si.getIconResName());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);

        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(null, si.getIcon());
        assertEquals(0, si.getTitleResId());
        assertEquals(null, si.getTitleResName());
        assertEquals(0, si.getTextResId());
        assertEquals(null, si.getTextResName());
        assertEquals(0, si.getDisabledMessageResourceId());
        assertEquals(null, si.getDisabledMessageResName());
        assertEquals(null, si.getCategories());
        assertEquals(null, si.getIntent());
        assertEquals(0, si.getRank());
        assertEquals(null, si.getExtras());

        assertEquals(ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_KEY_FIELDS_ONLY, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());
        assertEquals(null, si.getIconResName());
    }

    public void testShortcutInfoClone_minimum() {
        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(getTestContext())
                .setId("id")
                .setTitle("title")
                .setIntent(makeIntent("action", ShortcutActivity.class))
                .build();
        ShortcutInfo si = sorig.clone(/* clone flags*/ 0);

        assertEquals(getTestContext().getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals("title", si.getTitle());
        assertEquals("action", si.getIntent().getAction());
        assertEquals(null, si.getCategories());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_CREATOR);

        assertEquals(getTestContext().getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals("title", si.getTitle());
        assertEquals("action", si.getIntent().getAction());
        assertEquals(null, si.getCategories());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER);

        assertEquals(getTestContext().getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals("title", si.getTitle());
        assertEquals(null, si.getIntent());
        assertEquals(null, si.getCategories());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);

        assertEquals(getTestContext().getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(null, si.getTitle());
        assertEquals(null, si.getIntent());
        assertEquals(null, si.getCategories());
    }

    public void testShortcutInfoCopyNonNullFieldsFrom() throws InterruptedException {
        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(getTestContext())
                .setId("id")
                .setActivity(new ComponentName("a", "b"))
                .setIcon(Icon.createWithResource(mClientContext, 123))
                .setTitle("title")
                .setText("text")
                .setDisabledMessage("dismes")
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(123)
                .setExtras(pb)
                .build();
        sorig.addFlags(ShortcutInfo.FLAG_PINNED);
        sorig.setBitmapPath("abc");
        sorig.setIconResourceId(456);

        lookupAndFillInResourceNames(sorig);

        ShortcutInfo si;

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setActivity(new ComponentName("x", "y")).build());
        assertEquals("text", si.getText());
        assertEquals(123, si.getRank());
        assertEquals(new ComponentName("x", "y"), si.getActivity());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setIcon(Icon.createWithResource(mClientContext, 456)).build());
        assertEquals("text", si.getText());
        assertEquals(456, si.getIcon().getResId());
        assertEquals(0, si.getIconResourceId());
        assertEquals(null, si.getIconResName());
        assertEquals(null, si.getBitmapPath());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setTitle("xyz").build());
        assertEquals("text", si.getText());
        assertEquals("xyz", si.getTitle());
        assertEquals(0, si.getTitleResId());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setTitleResId(123).build());
        assertEquals("text", si.getText());
        assertEquals(null, si.getTitle());
        assertEquals(123, si.getTitleResId());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setText("xxx").build());
        assertEquals(123, si.getRank());
        assertEquals("xxx", si.getText());
        assertEquals(0, si.getTextResId());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setTextResId(1111).build());
        assertEquals(123, si.getRank());
        assertEquals(null, si.getText());
        assertEquals(1111, si.getTextResId());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setDisabledMessage("xxx").build());
        assertEquals(123, si.getRank());
        assertEquals("xxx", si.getDisabledMessage());
        assertEquals(0, si.getDisabledMessageResourceId());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setDisabledMessageResId(11111).build());
        assertEquals(123, si.getRank());
        assertEquals(null, si.getDisabledMessage());
        assertEquals(11111, si.getDisabledMessageResourceId());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setCategories(set()).build());
        assertEquals("text", si.getText());
        assertEquals(set(), si.getCategories());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setCategories(set("x")).build());
        assertEquals("text", si.getText());
        assertEquals(set("x"), si.getCategories());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setPerson(makePerson("person", "", "")).build());
        assertEquals("text", si.getText());
        assertEquals("person", si.getPersons()[0].getName());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setIntent(makeIntent("action2", ShortcutActivity.class)).build());
        assertEquals("text", si.getText());
        assertEquals("action2", si.getIntent().getAction());
        assertEquals(null, si.getIntent().getStringExtra("key"));

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setIntent(makeIntent("action3", ShortcutActivity.class, "key", "x")).build());
        assertEquals("text", si.getText());
        assertEquals("action3", si.getIntent().getAction());
        assertEquals("x", si.getIntent().getStringExtra("key"));

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setRank(999).build());
        assertEquals("text", si.getText());
        assertEquals(999, si.getRank());


        PersistableBundle pb2 = new PersistableBundle();
        pb2.putInt("x", 99);

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setExtras(pb2).build());
        assertEquals("text", si.getText());
        assertEquals(99, si.getExtras().getInt("x"));
    }

    public void testShortcutInfoCopyNonNullFieldsFrom_resId() throws InterruptedException {
        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(getTestContext())
                .setId("id")
                .setActivity(new ComponentName("a", "b"))
                .setIcon(Icon.createWithResource(mClientContext, 123))
                .setTitleResId(10)
                .setTextResId(11)
                .setDisabledMessageResId(12)
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(123)
                .setExtras(pb)
                .build();
        sorig.addFlags(ShortcutInfo.FLAG_PINNED);
        sorig.setBitmapPath("abc");
        sorig.setIconResourceId(456);

        ShortcutInfo si;

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setActivity(new ComponentName("x", "y")).build());
        assertEquals(11, si.getTextResId());
        assertEquals(new ComponentName("x", "y"), si.getActivity());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setIcon(Icon.createWithResource(mClientContext, 456)).build());
        assertEquals(11, si.getTextResId());
        assertEquals(456, si.getIcon().getResId());
        assertEquals(0, si.getIconResourceId());
        assertEquals(null, si.getIconResName());
        assertEquals(null, si.getBitmapPath());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setTitle("xyz").build());
        assertEquals(11, si.getTextResId());
        assertEquals("xyz", si.getTitle());
        assertEquals(0, si.getTitleResId());
        assertEquals(null, si.getTitleResName());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setTitleResId(123).build());
        assertEquals(11, si.getTextResId());
        assertEquals(null, si.getTitle());
        assertEquals(123, si.getTitleResId());
        assertEquals(null, si.getTitleResName());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setText("xxx").build());
        assertEquals(123, si.getRank());
        assertEquals("xxx", si.getText());
        assertEquals(0, si.getTextResId());
        assertEquals(null, si.getTextResName());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setTextResId(1111).build());
        assertEquals(123, si.getRank());
        assertEquals(null, si.getText());
        assertEquals(1111, si.getTextResId());
        assertEquals(null, si.getTextResName());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setDisabledMessage("xxx").build());
        assertEquals(123, si.getRank());
        assertEquals("xxx", si.getDisabledMessage());
        assertEquals(0, si.getDisabledMessageResourceId());
        assertEquals(null, si.getDisabledMessageResName());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setDisabledMessageResId(11111).build());
        assertEquals(123, si.getRank());
        assertEquals(null, si.getDisabledMessage());
        assertEquals(11111, si.getDisabledMessageResourceId());
        assertEquals(null, si.getDisabledMessageResName());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setCategories(set()).build());
        assertEquals(11, si.getTextResId());
        assertEquals(set(), si.getCategories());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setCategories(set("x")).build());
        assertEquals(11, si.getTextResId());
        assertEquals(set("x"), si.getCategories());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setIntent(makeIntent("action2", ShortcutActivity.class)).build());
        assertEquals(11, si.getTextResId());
        assertEquals("action2", si.getIntent().getAction());
        assertEquals(null, si.getIntent().getStringExtra("key"));

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setIntent(makeIntent("action3", ShortcutActivity.class, "key", "x")).build());
        assertEquals(11, si.getTextResId());
        assertEquals("action3", si.getIntent().getAction());
        assertEquals("x", si.getIntent().getStringExtra("key"));

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setRank(999).build());
        assertEquals(11, si.getTextResId());
        assertEquals(999, si.getRank());


        PersistableBundle pb2 = new PersistableBundle();
        pb2.putInt("x", 99);

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setExtras(pb2).build());
        assertEquals(11, si.getTextResId());
        assertEquals(99, si.getExtras().getInt("x"));
    }

    public void testShortcutInfoSaveAndLoad() throws InterruptedException {
        mRunningUsers.put(USER_10, true);

        setCaller(CALLING_PACKAGE_1, USER_10);

        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setActivity(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setIcon(bmp32x32)
                .setTitle("title")
                .setText("text")
                .setDisabledMessage("dismes")
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setPerson(makePerson("person", "personKey", "personUri"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(123)
                .setExtras(pb)
                .build();
        sorig.setTimestamp(mInjectedCurrentTimeMillis);

        ShortcutInfo sorig2 = new ShortcutInfo.Builder(mClientContext)
                .setId("id2")
                .setTitle("x")
                .setActivity(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setPersons(list(makePerson("person1", "personKey1", "personUri1"),
                        makePerson("person2", "personKey2", "personUri2")).toArray(new Person[2]))
                .setRank(456)
                .build();
        sorig2.setTimestamp(mInjectedCurrentTimeMillis);

        mManager.addDynamicShortcuts(list(sorig, sorig2));

        mInjectedCurrentTimeMillis += 1;
        final long now = mInjectedCurrentTimeMillis;
        mInjectedCurrentTimeMillis += 1;

        dumpsysOnLogcat("before save");

        // Save and load.
        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_10);

        dumpUserFile(USER_10);
        dumpsysOnLogcat("after load");

        ShortcutInfo si;
        si = mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "id", USER_10);

        assertEquals(USER_10, si.getUserId());
        assertEquals(HANDLE_USER_10, si.getUserHandle());
        assertEquals(CALLING_PACKAGE_1, si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(ShortcutActivity2.class.getName(), si.getActivity().getClassName());
        assertEquals(null, si.getIcon());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals("dismes", si.getDisabledMessage());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(1, si.getPersons().length);
        assertEquals("person", si.getPersons()[0].getName());
        assertEquals("personKey", si.getPersons()[0].getKey());
        assertEquals("personUri", si.getPersons()[0].getUri());
        assertEquals(0, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_HAS_ICON_FILE
                | ShortcutInfo.FLAG_STRINGS_RESOLVED, si.getFlags());
        assertNotNull(si.getBitmapPath()); // Something should be set.
        assertEquals(0, si.getIconResourceId());
        assertTrue(si.getLastChangedTimestamp() < now);

        // Make sure ranks are saved too.  Because of the auto-adjusting, we need two shortcuts
        // to test it.
        si = mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "id2", USER_10);
        assertEquals(1, si.getRank());
        assertEquals(2, si.getPersons().length);
        assertEquals("personUri2", si.getPersons()[1].getUri());

        dumpUserFile(USER_10);
    }

    public void testShortcutInfoSaveAndLoad_maskableBitmap() throws InterruptedException {
        mRunningUsers.put(USER_10, true);

        setCaller(CALLING_PACKAGE_1, USER_10);

        final Icon bmp32x32 = Icon.createWithAdaptiveBitmap(BitmapFactory.decodeResource(
            getTestContext().getResources(), R.drawable.black_32x32));

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(mClientContext)
            .setId("id")
            .setActivity(new ComponentName(mClientContext, ShortcutActivity2.class))
            .setIcon(bmp32x32)
            .setTitle("title")
            .setText("text")
            .setDisabledMessage("dismes")
            .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
            .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
            .setRank(123)
            .setExtras(pb)
            .build();
        sorig.setTimestamp(mInjectedCurrentTimeMillis);

        mManager.addDynamicShortcuts(list(sorig));

        mInjectedCurrentTimeMillis += 1;
        final long now = mInjectedCurrentTimeMillis;
        mInjectedCurrentTimeMillis += 1;

        dumpsysOnLogcat("before save");

        // Save and load.
        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_10);

        dumpUserFile(USER_10);
        dumpsysOnLogcat("after load");

        ShortcutInfo si;
        si = mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "id", USER_10);

        assertEquals(USER_10, si.getUserId());
        assertEquals(HANDLE_USER_10, si.getUserHandle());
        assertEquals(CALLING_PACKAGE_1, si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(ShortcutActivity2.class.getName(), si.getActivity().getClassName());
        assertEquals(null, si.getIcon());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals("dismes", si.getDisabledMessage());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(0, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_HAS_ICON_FILE
            | ShortcutInfo.FLAG_STRINGS_RESOLVED | ShortcutInfo.FLAG_ADAPTIVE_BITMAP,
            si.getFlags());
        assertNotNull(si.getBitmapPath()); // Something should be set.
        assertEquals(0, si.getIconResourceId());
        assertTrue(si.getLastChangedTimestamp() < now);

        dumpUserFile(USER_10);
    }

    public void testShortcutInfoSaveAndLoad_resId() throws InterruptedException {
        mRunningUsers.put(USER_10, true);

        setCaller(CALLING_PACKAGE_1, USER_10);

        final Icon res32x32 = Icon.createWithResource(mClientContext, R.drawable.black_32x32);

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setActivity(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setIcon(res32x32)
                .setTitleResId(10)
                .setTextResId(11)
                .setDisabledMessageResId(12)
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(123)
                .setExtras(pb)
                .build();
        sorig.setTimestamp(mInjectedCurrentTimeMillis);

        ShortcutInfo sorig2 = new ShortcutInfo.Builder(mClientContext)
                .setId("id2")
                .setTitle("x")
                .setActivity(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(456)
                .build();
        sorig2.setTimestamp(mInjectedCurrentTimeMillis);

        mManager.addDynamicShortcuts(list(sorig, sorig2));

        mInjectedCurrentTimeMillis += 1;
        final long now = mInjectedCurrentTimeMillis;
        mInjectedCurrentTimeMillis += 1;

        // Save and load.
        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_10);

        ShortcutInfo si;
        si = mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "id", USER_10);

        assertEquals(USER_10, si.getUserId());
        assertEquals(HANDLE_USER_10, si.getUserHandle());
        assertEquals(CALLING_PACKAGE_1, si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(ShortcutActivity2.class.getName(), si.getActivity().getClassName());
        assertEquals(null, si.getIcon());
        assertEquals(10, si.getTitleResId());
        assertEquals("r10", si.getTitleResName());
        assertEquals(11, si.getTextResId());
        assertEquals("r11", si.getTextResName());
        assertEquals(12, si.getDisabledMessageResourceId());
        assertEquals("r12", si.getDisabledMessageResName());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(0, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_HAS_ICON_RES
                | ShortcutInfo.FLAG_STRINGS_RESOLVED, si.getFlags());
        assertNull(si.getBitmapPath());
        assertEquals(R.drawable.black_32x32, si.getIconResourceId());
        assertTrue(si.getLastChangedTimestamp() < now);

        // Make sure ranks are saved too.  Because of the auto-adjusting, we need two shortcuts
        // to test it.
        si = mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "id2", USER_10);
        assertEquals(1, si.getRank());
    }

    public void testShortcutInfoSaveAndLoad_forBackup() {
        setCaller(CALLING_PACKAGE_1, USER_0);

        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setActivity(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setIcon(bmp32x32)
                .setTitle("title")
                .setText("text")
                .setDisabledMessage("dismes")
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setPerson(makePerson("person", "personKey", "personUri"))
                .setRank(123)
                .setExtras(pb)
                .build();

        ShortcutInfo sorig2 = new ShortcutInfo.Builder(mClientContext)
                .setId("id2")
                .setTitle("x")
                .setActivity(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(456)
                .build();

        mManager.addDynamicShortcuts(list(sorig, sorig2));

        // Dynamic shortcuts won't be backed up, so we need to pin it.
        setCaller(LAUNCHER_1, USER_0);
        mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("id", "id2"), HANDLE_USER_0);

        // Do backup & restore.
        backupAndRestore();

        mService.handleUnlockUser(USER_0); // Load user-0.

        ShortcutInfo si;
        si = mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "id", USER_0);

        assertEquals(CALLING_PACKAGE_1, si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(ShortcutActivity2.class.getName(), si.getActivity().getClassName());
        assertEquals(null, si.getIcon());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals("dismes", si.getDisabledMessage());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(0, si.getPersons().length); // Don't backup the persons field
        assertEquals(0, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_STRINGS_RESOLVED
                | ShortcutInfo.FLAG_SHADOW , si.getFlags());
        assertNull(si.getBitmapPath()); // No icon.
        assertEquals(0, si.getIconResourceId());

        // Note when restored from backup, it's no longer dynamic, so shouldn't have a rank.
        si = mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "id2", USER_0);
        assertEquals(0, si.getRank());
    }

    public void testShortcutInfoSaveAndLoad_forBackup_resId() {
        setCaller(CALLING_PACKAGE_1, USER_0);

        final Icon res32x32 = Icon.createWithResource(mClientContext, R.drawable.black_32x32);

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setActivity(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setIcon(res32x32)
                .setTitleResId(10)
                .setTextResId(11)
                .setDisabledMessageResId(12)
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(123)
                .setExtras(pb)
                .build();

        ShortcutInfo sorig2 = new ShortcutInfo.Builder(mClientContext)
                .setId("id2")
                .setTitle("x")
                .setActivity(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(456)
                .build();

        mManager.addDynamicShortcuts(list(sorig, sorig2));

        // Dynamic shortcuts won't be backed up, so we need to pin it.
        setCaller(LAUNCHER_1, USER_0);
        mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("id", "id2"), HANDLE_USER_0);

        // Do backup & restore.
        backupAndRestore();

        mService.handleUnlockUser(USER_0); // Load user-0.

        ShortcutInfo si;
        si = mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "id", USER_0);

        assertEquals(CALLING_PACKAGE_1, si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(ShortcutActivity2.class.getName(), si.getActivity().getClassName());
        assertEquals(null, si.getIcon());
        assertEquals(10, si.getTitleResId());
        assertEquals("r10", si.getTitleResName());
        assertEquals(11, si.getTextResId());
        assertEquals("r11", si.getTextResName());
        assertEquals(12, si.getDisabledMessageResourceId());
        assertEquals("r12", si.getDisabledMessageResName());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(0, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_STRINGS_RESOLVED
                | ShortcutInfo.FLAG_SHADOW , si.getFlags());
        assertNull(si.getBitmapPath()); // No icon.
        assertEquals(0, si.getIconResourceId());
        assertEquals(null, si.getIconResName());

        // Note when restored from backup, it's no longer dynamic, so shouldn't have a rank.
        si = mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "id2", USER_0);
        assertEquals(0, si.getRank());
    }

    private void checkShortcutInfoSaveAndLoad_intents(Intent intent) {
        assertTrue(mManager.setDynamicShortcuts(list(
                makeShortcutWithIntent("s1", intent))));
        initService();
        mService.handleUnlockUser(USER_0);

        assertWith(getCallerShortcuts())
                .haveIds("s1")
                .forShortcutWithId("s1", si -> {
                    assertEquals(intent.getAction(), si.getIntent().getAction());
                    assertEquals(intent.getData(), si.getIntent().getData());
                    assertEquals(intent.getComponent(), si.getIntent().getComponent());
                    assertBundlesEqual(intent.getExtras(), si.getIntent().getExtras());
                });
    }

    private void checkShortcutInfoSaveAndLoad_intents(Intent... intents) {
        assertTrue(mManager.setDynamicShortcuts(list(
                makeShortcutWithIntents("s1", intents))));
        initService();
        mService.handleUnlockUser(USER_0);

        assertWith(getCallerShortcuts())
                .haveIds("s1")
                .forShortcutWithId("s1", si -> {

                    final Intent[] actual = si.getIntents();
                    assertEquals(intents.length, actual.length);

                    for (int i = 0; i < intents.length; i++) {
                        assertEquals(intents[i].getAction(), actual[i].getAction());
                        assertEquals(intents[i].getData(), actual[i].getData());
                        assertEquals(intents[i].getComponent(), actual[i].getComponent());
                        assertEquals(intents[i].getFlags(), actual[i].getFlags());
                        assertBundlesEqual(intents[i].getExtras(), actual[i].getExtras());
                    }
                });
    }

    public void testShortcutInfoSaveAndLoad_intents() {
        checkShortcutInfoSaveAndLoad_intents(new Intent(Intent.ACTION_VIEW));

        mInjectedCurrentTimeMillis += INTERVAL; // reset throttling.

        checkShortcutInfoSaveAndLoad_intents(new Intent(Intent.ACTION_MAIN));

        mInjectedCurrentTimeMillis += INTERVAL; // reset throttling.

        checkShortcutInfoSaveAndLoad_intents(new Intent(Intent.ACTION_VIEW,
                Uri.parse("http://www.example.com/")));

        mInjectedCurrentTimeMillis += INTERVAL; // reset throttling.

        checkShortcutInfoSaveAndLoad_intents(new Intent(Intent.ACTION_MAIN,
                Uri.parse("http://www.example.com/")));

        mInjectedCurrentTimeMillis += INTERVAL; // reset throttling.

        checkShortcutInfoSaveAndLoad_intents(new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName("a", "b")));

        mInjectedCurrentTimeMillis += INTERVAL; // reset throttling.

        checkShortcutInfoSaveAndLoad_intents(new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName("a", "b")));

        mInjectedCurrentTimeMillis += INTERVAL; // reset throttling.

        checkShortcutInfoSaveAndLoad_intents(new Intent(Intent.ACTION_VIEW)
                .putExtras(makeBundle("a", "b")));

        mInjectedCurrentTimeMillis += INTERVAL; // reset throttling.


        checkShortcutInfoSaveAndLoad_intents(new Intent(Intent.ACTION_MAIN)
                .putExtras(makeBundle("a", "b")));

        mInjectedCurrentTimeMillis += INTERVAL; // reset throttling.

        // Multi-intents
        checkShortcutInfoSaveAndLoad_intents(
                new Intent(Intent.ACTION_MAIN).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK),
                new Intent(Intent.ACTION_VIEW).setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
        );

        checkShortcutInfoSaveAndLoad_intents(
                new Intent(Intent.ACTION_MAIN).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .setComponent(new ComponentName("a", "b")),
                new Intent(Intent.ACTION_VIEW)
                        .setComponent(new ComponentName("a", "b"))
                );

        checkShortcutInfoSaveAndLoad_intents(
                new Intent(Intent.ACTION_MAIN)
                        .setComponent(new ComponentName("a", "b")),
                new Intent(Intent.ACTION_VIEW).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .setComponent(new ComponentName("a", "b")),
                new Intent("xyz").setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FILL_IN_COMPONENT)
                        .setComponent(new ComponentName("a", "b")).putExtras(
                        makeBundle("xx", "yy"))
                );
    }

    public void testThrottling() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Reached the max

        mInjectedCurrentTimeMillis++;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Still throttled
        mInjectedCurrentTimeMillis = START_TIME + INTERVAL - 1;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Now it should work.
        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1))); // fail
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 2, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 2, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 2, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeMillis++;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 2, mManager.getRateLimitResetTime());

        // 4 hours later...
        mInjectedCurrentTimeMillis = START_TIME + 4 * INTERVAL;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 5, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 5, mManager.getRateLimitResetTime());

        // Make sure getRemainingCallCount() itself gets reset without calling setDynamicShortcuts().
        mInjectedCurrentTimeMillis = START_TIME + 8 * INTERVAL;
        assertEquals(3, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 9, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 9, mManager.getRateLimitResetTime());
    }

    public void testThrottling_rewind() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeMillis = 12345; // Clock reset!

        // Since the clock looks invalid, the counter shouldn't have reset.
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Forward again.  Still haven't reset yet.
        mInjectedCurrentTimeMillis = START_TIME + INTERVAL - 1;
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Now rewind -- this will reset the counters.
        mInjectedCurrentTimeMillis = START_TIME - 100000;
        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());

        // Forward again, should be reset now.
        mInjectedCurrentTimeMillis += INTERVAL;
        assertEquals(3, mManager.getRemainingCallCount());
    }

    public void testThrottling_perPackage() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());

        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());

        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // Reached the max

        mInjectedCurrentTimeMillis++;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));

        // Try from a different caller.
        mInjectedClientPackage = CALLING_PACKAGE_2;
        mInjectedCallingUid = CALLING_UID_2;

        // Need to create a new one wit the updated package name.
        final ShortcutInfo si2 = makeShortcut("shortcut1");

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertEquals(2, mManager.getRemainingCallCount());

        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertEquals(1, mManager.getRemainingCallCount());

        // Back to the original caller, still throttled.
        mInjectedClientPackage = CALLING_PACKAGE_1;
        mInjectedCallingUid = CALLING_UID_1;

        mInjectedCurrentTimeMillis = START_TIME + INTERVAL - 1;
        assertEquals(0, mManager.getRemainingCallCount());
        assertFalse(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // Now it should work.
        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));

        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));

        mInjectedCurrentTimeMillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));

        mInjectedCurrentTimeMillis++;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));

        mInjectedCurrentTimeMillis = START_TIME + 4 * INTERVAL;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertFalse(mManager.setDynamicShortcuts(list(si1)));

        mInjectedClientPackage = CALLING_PACKAGE_2;
        mInjectedCallingUid = CALLING_UID_2;

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertFalse(mManager.setDynamicShortcuts(list(si2)));
    }

    public void testThrottling_localeChanges() {
        prepareCrossProfileDataSet();

        dumpsysOnLogcat("Before save & load");

        mService.saveDirtyInfo();
        initService();

        mInjectedLocale = Locale.CHINA;
        mService.mReceiver.onReceive(mServiceContext, new Intent(Intent.ACTION_LOCALE_CHANGED));

        // Note at this point only user-0 is loaded, and the counters are reset for this user,
        // but it will work for other users too because we check the locale change at any
        // API entry point.

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });

        // Make sure even if we receive ACTION_LOCALE_CHANGED, if the locale hasn't actually
        // changed, we don't reset throttling.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.updateShortcuts(list());
            assertEquals(2, mManager.getRemainingCallCount());
        });

        mService.mReceiver.onReceive(mServiceContext, new Intent(Intent.ACTION_LOCALE_CHANGED));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(2, mManager.getRemainingCallCount()); // Still 2.
        });

        mService.saveDirtyInfo();
        initService();

        // The locale should be persisted, so it still shouldn't reset throttling.
        mService.mReceiver.onReceive(mServiceContext, new Intent(Intent.ACTION_LOCALE_CHANGED));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(2, mManager.getRemainingCallCount()); // Still 2.
        });
    }

    public void testThrottling_foreground() throws Exception {
        prepareCrossProfileDataSet();

        dumpsysOnLogcat("Before save & load");

        mService.saveDirtyInfo();
        initService();

        // We need to update the current time from time to time, since some of the internal checks
        // rely on the time being correctly incremented.
        mInjectedCurrentTimeMillis++;

        // First, all packages have less than 3 (== initial value) remaining calls.

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mInjectedCurrentTimeMillis++;

        // State changed, but not foreground, so no resetting.
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_1, ActivityManager.PROCESS_STATE_TOP_SLEEPING, 0);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mInjectedCurrentTimeMillis++;

        // State changed, package1 foreground, reset.
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_1, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_1, ActivityManager.PROCESS_STATE_TOP_SLEEPING, 0);

        mInjectedCurrentTimeMillis++;

        // Different app comes to foreground briefly, and goes back to background.
        // Now, make sure package 2's counter is reset, even in this case.
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_2, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0);
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_2, ActivityManager.PROCESS_STATE_TOP_SLEEPING, 0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mInjectedCurrentTimeMillis++;

        // Do the same thing one more time.  This would catch the bug with mixuing up
        // the current time and the elapsed time.
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            mManager.updateShortcuts(list(makeShortcut("s")));
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_2, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0);
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_2, ActivityManager.PROCESS_STATE_TOP_SLEEPING, 0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mInjectedCurrentTimeMillis++;

        // Package 1 on user-10 comes to foreground.
        // Now, also try calling some APIs and make sure foreground apps don't get throttled.
        mService.mUidObserver.onUidStateChanged(
                UserHandle.getUid(USER_10, CALLING_UID_1),
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
            assertFalse(mManager.isRateLimitingActive());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(2, mManager.getRemainingCallCount());
            assertFalse(mManager.isRateLimitingActive());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(1, mManager.getRemainingCallCount());
            assertFalse(mManager.isRateLimitingActive());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
            assertTrue(mManager.isRateLimitingActive());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
            assertTrue(mManager.isRateLimitingActive());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
            assertTrue(mManager.isRateLimitingActive());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
            assertTrue(mManager.isRateLimitingActive());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
            assertTrue(mManager.isRateLimitingActive());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertEquals(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(3, mManager.getRemainingCallCount()); // Still 3!
            assertFalse(mManager.isRateLimitingActive());
        });
    }


    public void testThrottling_resetByInternalCall() throws Exception {
        prepareCrossProfileDataSet();

        dumpsysOnLogcat("Before save & load");

        mService.saveDirtyInfo();
        initService();

        // First, all packages have less than 3 (== initial value) remaining calls.

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        // Simulate a call from sys UI.
        mCallerPermissions.add(permission.RESET_SHORTCUT_MANAGER_THROTTLING);
        mManager.onApplicationActive(CALLING_PACKAGE_1, USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mManager.onApplicationActive(CALLING_PACKAGE_3, USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mManager.onApplicationActive(CALLING_PACKAGE_1, USER_10);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
    }

    public void testReportShortcutUsed() {
        mRunningUsers.put(USER_10, true);

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            reset(mMockUsageStatsManagerInternal);

            // Report with an nonexistent shortcut.
            mManager.reportShortcutUsed("s1");
            verify(mMockUsageStatsManagerInternal, times(0)).reportShortcutUsage(
                    anyString(), anyString(), anyInt());

            // Publish s2, but s1 still doesn't exist.
            mManager.setDynamicShortcuts(list(makeShortcut("s2")));
            mManager.reportShortcutUsed("s1");
            verify(mMockUsageStatsManagerInternal, times(0)).reportShortcutUsage(
                    anyString(), anyString(), anyInt());

            mManager.reportShortcutUsed("s2");
            verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                    eq(CALLING_PACKAGE_1), eq("s2"), eq(USER_10));

        });
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            // Try with a different package.
            reset(mMockUsageStatsManagerInternal);

            // Report with an nonexistent shortcut.
            mManager.reportShortcutUsed("s2");
            verify(mMockUsageStatsManagerInternal, times(0)).reportShortcutUsage(
                    anyString(), anyString(), anyInt());

            // Publish s2, but s1 still doesn't exist.
            mManager.setDynamicShortcuts(list(makeShortcut("s3")));
            mManager.reportShortcutUsed("s2");
            verify(mMockUsageStatsManagerInternal, times(0)).reportShortcutUsage(
                    anyString(), anyString(), anyInt());

            mManager.reportShortcutUsed("s3");
            verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                    eq(CALLING_PACKAGE_2), eq("s3"), eq(USER_10));

        });
    }

    // Test for a ShortcutInfo method.
    public void testGetResourcePackageName() {
        assertEquals(null, ShortcutInfo.getResourcePackageName(""));
        assertEquals(null, ShortcutInfo.getResourcePackageName("abc"));
        assertEquals("p", ShortcutInfo.getResourcePackageName("p:"));
        assertEquals("p", ShortcutInfo.getResourcePackageName("p:xx"));
        assertEquals("pac", ShortcutInfo.getResourcePackageName("pac:"));
    }

    // Test for a ShortcutInfo method.
    public void testGetResourceTypeName() {
        assertEquals(null, ShortcutInfo.getResourceTypeName(""));
        assertEquals(null, ShortcutInfo.getResourceTypeName(":"));
        assertEquals(null, ShortcutInfo.getResourceTypeName("/"));
        assertEquals(null, ShortcutInfo.getResourceTypeName("/:"));
        assertEquals("a", ShortcutInfo.getResourceTypeName(":a/"));
        assertEquals("type", ShortcutInfo.getResourceTypeName("xxx:type/yyy"));
    }

    // Test for a ShortcutInfo method.
    public void testGetResourceTypeAndEntryName() {
        assertEquals(null, ShortcutInfo.getResourceTypeAndEntryName(""));
        assertEquals(null, ShortcutInfo.getResourceTypeAndEntryName("abc"));
        assertEquals("", ShortcutInfo.getResourceTypeAndEntryName("p:"));
        assertEquals("x", ShortcutInfo.getResourceTypeAndEntryName(":x"));
        assertEquals("x", ShortcutInfo.getResourceTypeAndEntryName("p:x"));
        assertEquals("xyz", ShortcutInfo.getResourceTypeAndEntryName("pac:xyz"));
    }

    // Test for a ShortcutInfo method.
    public void testGetResourceEntryName() {
        assertEquals(null, ShortcutInfo.getResourceEntryName(""));
        assertEquals(null, ShortcutInfo.getResourceEntryName("ab:"));
        assertEquals("", ShortcutInfo.getResourceEntryName("/"));
        assertEquals("abc", ShortcutInfo.getResourceEntryName("/abc"));
        assertEquals("abc", ShortcutInfo.getResourceEntryName("xyz/abc"));
    }

    // Test for a ShortcutInfo method.
    public void testLookUpResourceName_systemResources() {
        // For android system resources, lookUpResourceName will simply return the value as a
        // string, regardless of "withType".
        final Resources res = getTestContext().getResources();

        assertEquals("" + android.R.string.cancel, ShortcutInfo.lookUpResourceName(res,
                android.R.string.cancel, true, getTestContext().getPackageName()));
        assertEquals("" + android.R.drawable.alert_dark_frame, ShortcutInfo.lookUpResourceName(res,
                android.R.drawable.alert_dark_frame, true, getTestContext().getPackageName()));
        assertEquals("" + android.R.string.cancel, ShortcutInfo.lookUpResourceName(res,
                android.R.string.cancel, false, getTestContext().getPackageName()));
    }

    public void testLookUpResourceName_appResources() {
        final Resources res = getTestContext().getResources();

        assertEquals("shortcut_text1", ShortcutInfo.lookUpResourceName(res,
                R.string.shortcut_text1, false, getTestContext().getPackageName()));
        assertEquals("string/shortcut_text1", ShortcutInfo.lookUpResourceName(res,
                R.string.shortcut_text1, true, getTestContext().getPackageName()));

        assertEquals("black_16x64", ShortcutInfo.lookUpResourceName(res,
                R.drawable.black_16x64, false, getTestContext().getPackageName()));
        assertEquals("drawable/black_16x64", ShortcutInfo.lookUpResourceName(res,
                R.drawable.black_16x64, true, getTestContext().getPackageName()));
    }

    // Test for a ShortcutInfo method.
    public void testLookUpResourceId_systemResources() {
        final Resources res = getTestContext().getResources();

        assertEquals(android.R.string.cancel, ShortcutInfo.lookUpResourceId(res,
                "" + android.R.string.cancel, null,
                getTestContext().getPackageName()));
        assertEquals(android.R.drawable.alert_dark_frame, ShortcutInfo.lookUpResourceId(res,
                "" + android.R.drawable.alert_dark_frame, null,
                getTestContext().getPackageName()));
    }

    // Test for a ShortcutInfo method.
    public void testLookUpResourceId_appResources() {
        final Resources res = getTestContext().getResources();

        assertEquals(R.string.shortcut_text1,
                ShortcutInfo.lookUpResourceId(res, "shortcut_text1", "string",
                        getTestContext().getPackageName()));

        assertEquals(R.string.shortcut_text1,
                ShortcutInfo.lookUpResourceId(res, "string/shortcut_text1", null,
                        getTestContext().getPackageName()));

        assertEquals(R.drawable.black_16x64,
                ShortcutInfo.lookUpResourceId(res, "black_16x64", "drawable",
                        getTestContext().getPackageName()));

        assertEquals(R.drawable.black_16x64,
                ShortcutInfo.lookUpResourceId(res, "drawable/black_16x64", null,
                        getTestContext().getPackageName()));
    }

    public void testDumpCheckin() throws IOException {
        prepareCrossProfileDataSet();

        // prepareCrossProfileDataSet() doesn't set any icons, so do set here.
        final Icon res32x32 = Icon.createWithResource(getTestContext(), R.drawable.black_32x32);
        final Icon res64x64 = Icon.createWithResource(getTestContext(), R.drawable.black_64x64);
        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));
        final Icon bmp64x64 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_64x64));

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("res32x32", res32x32),
                    makeShortcutWithIcon("res64x64", res64x64),
                    makeShortcutWithIcon("bmp32x32", bmp32x32),
                    makeShortcutWithIcon("bmp64x64", bmp64x64))));
        });

        // We can't predict the compressed bitmap sizes, so get the real sizes here.
        final long bitmapTotal =
                new File(getBitmapAbsPath(USER_0, CALLING_PACKAGE_2, "bmp32x32")).length() +
                new File(getBitmapAbsPath(USER_0, CALLING_PACKAGE_2, "bmp64x64")).length();

        // Read the expected output and inject the bitmap size.
        final String expected = readTestAsset("shortcut/dumpsys_expected.txt")
                .replace("***BITMAP_SIZE***", String.valueOf(bitmapTotal));

        assertEquals(expected, dumpCheckin());
    }

    /**
     * Make sure the legacy file format that only supported a single intent per shortcut
     * can still be read.
     */
    public void testLoadLegacySavedFile() throws Exception {
        final File path = mService.getUserFile(USER_0);
        path.getParentFile().mkdirs();
        try (Writer w = new FileWriter(path)) {
            w.write(readTestAsset("shortcut/shortcut_legacy_file.xml"));
        };
        initService();
        mService.handleUnlockUser(USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("manifest-shortcut-storage")
                    .forShortcutWithId("manifest-shortcut-storage", si -> {
                        assertEquals("android.settings.INTERNAL_STORAGE_SETTINGS",
                                si.getIntent().getAction());
                        assertEquals(12345, si.getIntent().getIntExtra("key", 0));
                    });
        });
    }

    public void testIsUserUnlocked() {
        mRunningUsers.clear();
        mUnlockedUsers.clear();

        assertFalse(mService.isUserUnlockedL(USER_0));
        assertFalse(mService.isUserUnlockedL(USER_10));

        // Start user 0, still locked.
        mRunningUsers.put(USER_0, true);
        assertFalse(mService.isUserUnlockedL(USER_0));
        assertFalse(mService.isUserUnlockedL(USER_10));

        // Unlock user.
        mUnlockedUsers.put(USER_0, true);
        assertTrue(mService.isUserUnlockedL(USER_0));
        assertFalse(mService.isUserUnlockedL(USER_10));

        // Clear again.
        mRunningUsers.clear();
        mUnlockedUsers.clear();

        // Directly call the lifecycle event.  Now also locked.
        mService.handleUnlockUser(USER_0);
        assertTrue(mService.isUserUnlockedL(USER_0));
        assertFalse(mService.isUserUnlockedL(USER_10));

        // Directly call the stop lifecycle event.  Goes back to the initial state.
        mService.handleStopUser(USER_0);
        assertFalse(mService.isUserUnlockedL(USER_0));
        assertFalse(mService.isUserUnlockedL(USER_10));
    }

    public void testEphemeralApp() {
        mRunningUsers.put(USER_10, true); // this test needs user 10.

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(mManager.getDynamicShortcuts()).isEmpty();
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertWith(mManager.getDynamicShortcuts()).isEmpty();
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertWith(mManager.getDynamicShortcuts()).isEmpty();
        });
        // Make package 1 ephemeral.
        mEphemeralPackages.add(PackageWithUser.of(USER_0, CALLING_PACKAGE_1));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertExpectException(IllegalStateException.class, "Ephemeral apps", () -> {
                mManager.getDynamicShortcuts();
            });
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertWith(mManager.getDynamicShortcuts()).isEmpty();
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertWith(mManager.getDynamicShortcuts()).isEmpty();
        });
    }
}
