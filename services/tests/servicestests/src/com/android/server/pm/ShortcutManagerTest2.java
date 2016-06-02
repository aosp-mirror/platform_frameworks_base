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

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertExpectException;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.parceled;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.set;

import android.Manifest.permission;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.frameworks.servicestests.R;
import com.android.server.SystemService;

/**
 * Tests for ShortcutService and ShortcutManager.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutManagerTest2 \
 -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
public class ShortcutManagerTest2 extends BaseShortcutManagerTest {
    // ShortcutInfo tests

    public void testShortcutInfoMissingMandatoryFields() {
        assertExpectException(
                IllegalArgumentException.class,
                "ID must be provided",
                () -> new ShortcutInfo.Builder(getTestContext()).build());
        assertExpectException(
                NullPointerException.class,
                "Intent action must be set",
                () -> new ShortcutInfo.Builder(getTestContext()).setIntent(new Intent()));
        assertExpectException(
                NullPointerException.class,
                "activity must be provided",
                () -> new ShortcutInfo.Builder(getTestContext()).setId("id").build()
                        .enforceMandatoryFields());
        assertExpectException(
                IllegalArgumentException.class,
                "title must be provided",
                () -> new ShortcutInfo.Builder(getTestContext()).setId("id")
                        .setActivity(
                                new ComponentName(getTestContext().getPackageName(), "s"))
                        .build()
                        .enforceMandatoryFields());
        assertExpectException(
                NullPointerException.class,
                "Intent must be provided",
                () -> new ShortcutInfo.Builder(getTestContext()).setId("id")
                        .setActivity(
                                new ComponentName(getTestContext().getPackageName(), "s"))
                        .setTitle("x").build()
                        .enforceMandatoryFields());
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
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals("abc", si.getBitmapPath());
        assertEquals(456, si.getIconResourceId());
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

        si = parceled(si);

        assertEquals(getTestContext().getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(123, si.getIcon().getResId());
        assertEquals(10, si.getTitleResId());
        assertEquals(11, si.getTextResId());
        assertEquals(12, si.getDisabledMessageResourceId());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals("abc", si.getBitmapPath());
        assertEquals(456, si.getIconResourceId());
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
                .setExtras(pb)
                .build();
        sorig.addFlags(ShortcutInfo.FLAG_PINNED);
        sorig.setBitmapPath("abc");
        sorig.setIconResourceId(456);

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
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals("abc", si.getBitmapPath());
        assertEquals(456, si.getIconResourceId());

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
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());

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
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);

        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(null, si.getActivity());
        assertEquals(null, si.getIcon());
        assertEquals(null, si.getTitle());
        assertEquals(null, si.getText());
        assertEquals(null, si.getDisabledMessage());
        assertEquals(null, si.getCategories());
        assertEquals(null, si.getIntent());
        assertEquals(0, si.getRank());
        assertEquals(null, si.getExtras());

        assertEquals(ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_KEY_FIELDS_ONLY, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());
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

        ShortcutInfo si = sorig.clone(/* clone flags*/ 0);

        assertEquals(USER_11, si.getUserId());
        assertEquals(HANDLE_USER_11, si.getUserHandle());
        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(123, si.getIcon().getResId());
        assertEquals(10, si.getTitleResId());
        assertEquals(11, si.getTextResId());
        assertEquals(12, si.getDisabledMessageResourceId());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals("abc", si.getBitmapPath());
        assertEquals(456, si.getIconResourceId());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_CREATOR);

        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(null, si.getIcon());
        assertEquals(10, si.getTitleResId());
        assertEquals(11, si.getTextResId());
        assertEquals(12, si.getDisabledMessageResourceId());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER);

        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivity());
        assertEquals(null, si.getIcon());
        assertEquals(10, si.getTitleResId());
        assertEquals(11, si.getTextResId());
        assertEquals(12, si.getDisabledMessageResourceId());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals(null, si.getIntent());
        assertEquals(123, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);

        assertEquals(mClientContext.getPackageName(), si.getPackage());
        assertEquals("id", si.getId());
        assertEquals(null, si.getActivity());
        assertEquals(null, si.getIcon());
        assertEquals(0, si.getTitleResId());
        assertEquals(0, si.getTextResId());
        assertEquals(0, si.getDisabledMessageResourceId());
        assertEquals(null, si.getCategories());
        assertEquals(null, si.getIntent());
        assertEquals(0, si.getRank());
        assertEquals(null, si.getExtras());

        assertEquals(ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_KEY_FIELDS_ONLY, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());
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

        ShortcutInfo si;

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setActivity(new ComponentName("x", "y")).build());
        assertEquals("text", si.getText());
        assertEquals(new ComponentName("x", "y"), si.getActivity());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setIcon(Icon.createWithResource(mClientContext, 456)).build());
        assertEquals("text", si.getText());
        assertEquals(456, si.getIcon().getResId());

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

        // Make sure the timestamp gets updated too.

        final long timestamp = si.getLastChangedTimestamp();
        Thread.sleep(2);

        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setTitle("xyz").build());

        assertTrue(si.getLastChangedTimestamp() > timestamp);
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

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setTitle("xyz").build());
        assertEquals(11, si.getTextResId());
        assertEquals("xyz", si.getTitle());
        assertEquals(0, si.getTitleResId());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setTitleResId(123).build());
        assertEquals(11, si.getTextResId());
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

        // Make sure the timestamp gets updated too.

        final long timestamp = si.getLastChangedTimestamp();
        Thread.sleep(2);

        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setTitle("xyz").build());

        assertTrue(si.getLastChangedTimestamp() > timestamp);
    }

    public void testShortcutInfoSaveAndLoad() throws InterruptedException {
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
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(123)
                .setExtras(pb)
                .build();

        mManager.addDynamicShortcuts(list(sorig));

        Thread.sleep(2);
        final long now = System.currentTimeMillis();

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
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals("dismes", si.getDisabledMessage());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_HAS_ICON_FILE, si.getFlags());
        assertNotNull(si.getBitmapPath()); // Something should be set.
        assertEquals(0, si.getIconResourceId());
        assertTrue(si.getLastChangedTimestamp() < now);
    }

    public void testShortcutInfoSaveAndLoad_resId() throws InterruptedException {
        setCaller(CALLING_PACKAGE_1, USER_10);

        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setActivity(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setIcon(bmp32x32)
                .setTitleResId(10)
                .setTextResId(11)
                .setDisabledMessageResId(12)
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(123)
                .setExtras(pb)
                .build();

        mManager.addDynamicShortcuts(list(sorig));

        Thread.sleep(2);
        final long now = System.currentTimeMillis();

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
        assertEquals(11, si.getTextResId());
        assertEquals(12, si.getDisabledMessageResourceId());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_HAS_ICON_FILE, si.getFlags());
        assertNotNull(si.getBitmapPath()); // Something should be set.
        assertEquals(0, si.getIconResourceId());
        assertTrue(si.getLastChangedTimestamp() < now);
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
                .setRank(123)
                .setExtras(pb)
                .build();

        mManager.addDynamicShortcuts(list(sorig));

        // Dynamic shortcuts won't be backed up, so we need to pin it.
        setCaller(LAUNCHER_1, USER_0);
        mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("id"), HANDLE_USER_0);

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
        assertEquals(123, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertNull(si.getBitmapPath()); // No icon.
        assertEquals(0, si.getIconResourceId());
    }

    public void testShortcutInfoSaveAndLoad_forBackup_resId() {
        setCaller(CALLING_PACKAGE_1, USER_0);

        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setActivity(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setIcon(bmp32x32)
                .setTitleResId(10)
                .setTextResId(11)
                .setDisabledMessageResId(12)
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setRank(123)
                .setExtras(pb)
                .build();

        mManager.addDynamicShortcuts(list(sorig));

        // Dynamic shortcuts won't be backed up, so we need to pin it.
        setCaller(LAUNCHER_1, USER_0);
        mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("id"), HANDLE_USER_0);

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
        assertEquals(11, si.getTextResId());
        assertEquals(12, si.getDisabledMessageResourceId());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getRank());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertNull(si.getBitmapPath()); // No icon.
        assertEquals(0, si.getIconResourceId());
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

        final long origSequenceNumber = mService.getLocaleChangeSequenceNumber();

        // onSystemLocaleChangedNoLock before boot completed will be ignored.
        mInternal.onSystemLocaleChangedNoLock();
        assertEquals(origSequenceNumber, mService.getLocaleChangeSequenceNumber());

        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mInternal.onSystemLocaleChangedNoLock();
        assertEquals(origSequenceNumber + 1, mService.getLocaleChangeSequenceNumber());

        // Note at this point only user-0 is loaded, and the counters are reset for this user,
        // but it will work for other users too, because we persist when

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

        mService.saveDirtyInfo();
        initService();

        // Make sure the counter is persisted.
        assertEquals(origSequenceNumber + 1, mService.getLocaleChangeSequenceNumber());
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
                CALLING_UID_1, ActivityManager.PROCESS_STATE_TOP_SLEEPING);
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
                CALLING_UID_1, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
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
                CALLING_UID_1, ActivityManager.PROCESS_STATE_TOP_SLEEPING);

        mInjectedCurrentTimeMillis++;

        // Different app comes to foreground briefly, and goes back to background.
        // Now, make sure package 2's counter is reset, even in this case.
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_2, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_2, ActivityManager.PROCESS_STATE_TOP_SLEEPING);

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
                CALLING_UID_2, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_2, ActivityManager.PROCESS_STATE_TOP_SLEEPING);

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
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertEquals(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(3, mManager.getRemainingCallCount()); // Still 3!
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
        mService.onApplicationActive(CALLING_PACKAGE_1, USER_0);

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

        mService.onApplicationActive(CALLING_PACKAGE_3, USER_0);

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

        mService.onApplicationActive(CALLING_PACKAGE_1, USER_10);

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
}
