
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

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.test.AndroidTestCase;

import com.android.internal.util.Preconditions;
import com.android.server.testutis.TestUtils;

/**
 * Tests for {@link ShortcutInfo}.

 m FrameworksServicesTests &&
 adb install \
   -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutInfoTest \
   -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner

 */
public class ShortcutInfoTest extends AndroidTestCase {

    public void testMissingMandatoryFields() {
        TestUtils.assertExpectException(
                IllegalArgumentException.class,
                "ID must be provided",
                () -> new ShortcutInfo.Builder(mContext).build());
        TestUtils.assertExpectException(
                IllegalArgumentException.class,
                "title must be provided",
                () -> new ShortcutInfo.Builder(mContext).setId("id").build()
                        .enforceMandatoryFields());
        TestUtils.assertExpectException(
                NullPointerException.class,
                "Intent must be provided",
                () -> new ShortcutInfo.Builder(mContext).setId("id").setTitle("x").build()
                        .enforceMandatoryFields());
    }

    private ShortcutInfo parceled(ShortcutInfo si) {
        Parcel p = Parcel.obtain();
        p.writeParcelable(si, 0);
        p.setDataPosition(0);
        ShortcutInfo si2 = p.readParcelable(getClass().getClassLoader());
        p.recycle();
        return si2;
    }

    private Intent makeIntent(String action, Object... bundleKeysAndValues) {
        final Intent intent = new Intent(action);
        intent.replaceExtras(ShortcutManagerTest.makeBundle(bundleKeysAndValues));
        return intent;
    }

    public void testParcel() {
        ShortcutInfo si = parceled(new ShortcutInfo.Builder(getContext())
                .setId("id")
                .setTitle("title")
                .setIntent(makeIntent("action"))
                .build());
        assertEquals(getContext().getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals("title", si.getTitle());
        assertEquals("action", si.getIntent().getAction());

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);

        si = new ShortcutInfo.Builder(getContext())
                .setId("id")
                .setActivityComponent(new ComponentName("a", "b"))
                .setIcon(Icon.createWithContentUri("content://a.b.c/"))
                .setTitle("title")
                .setText("text")
                .setIntent(makeIntent("action", "key", "val"))
                .setWeight(123)
                .setExtras(pb)
                .build();
        si.addFlags(ShortcutInfo.FLAG_PINNED);
        si.setBitmapPath("abc");
        si.setIconResourceId(456);

        si = parceled(si);

        assertEquals(getContext().getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivityComponent());
        assertEquals("content://a.b.c/", si.getIcon().getUriString());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getWeight());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals("abc", si.getBitmapPath());
        assertEquals(456, si.getIconResourceId());
    }

    public void testClone() {
        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(getContext())
                .setId("id")
                .setActivityComponent(new ComponentName("a", "b"))
                .setIcon(Icon.createWithContentUri("content://a.b.c/"))
                .setTitle("title")
                .setText("text")
                .setIntent(makeIntent("action", "key", "val"))
                .setWeight(123)
                .setExtras(pb)
                .build();
        sorig.addFlags(ShortcutInfo.FLAG_PINNED);
        sorig.setBitmapPath("abc");
        sorig.setIconResourceId(456);

        ShortcutInfo si = sorig.clone(/* clone flags*/ 0);

        assertEquals(getContext().getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivityComponent());
        assertEquals("content://a.b.c/", si.getIcon().getUriString());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getWeight());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals("abc", si.getBitmapPath());
        assertEquals(456, si.getIconResourceId());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_CREATOR);

        assertEquals(getContext().getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivityComponent());
        assertEquals(null, si.getIcon());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getWeight());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals(null, si.getBitmapPath());
        assertEquals(0, si.getIconResourceId());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER);

        assertEquals(getContext().getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivityComponent());
        assertEquals(null, si.getIcon());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals(null, si.getIntent());
        assertEquals(123, si.getWeight());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals(null, si.getBitmapPath());
        assertEquals(0, si.getIconResourceId());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);

        assertEquals(getContext().getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(null, si.getActivityComponent());
        assertEquals(null, si.getIcon());
        assertEquals(null, si.getTitle());
        assertEquals(null, si.getText());
        assertEquals(null, si.getIntent());
        assertEquals(0, si.getWeight());
        assertEquals(null, si.getExtras());

        assertEquals(ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_KEY_FIELDS_ONLY, si.getFlags());
        assertEquals(null, si.getBitmapPath());
        assertEquals(0, si.getIconResourceId());
    }


    public void testCopyNonNullFieldsFrom() {
        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(getContext())
                .setId("id")
                .setActivityComponent(new ComponentName("a", "b"))
                .setIcon(Icon.createWithContentUri("content://a.b.c/"))
                .setTitle("title")
                .setText("text")
                .setIntent(makeIntent("action", "key", "val"))
                .setWeight(123)
                .setExtras(pb)
                .build();
        sorig.addFlags(ShortcutInfo.FLAG_PINNED);
        sorig.setBitmapPath("abc");
        sorig.setIconResourceId(456);

        ShortcutInfo si;

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getContext()).setId("id")
                .setActivityComponent(new ComponentName("x", "y")).build());
        assertEquals(new ComponentName("x", "y"), si.getActivityComponent());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getContext()).setId("id")
                .setIcon(Icon.createWithContentUri("content://x.y.z/")).build());
        assertEquals("content://x.y.z/", si.getIcon().getUriString());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getContext()).setId("id")
                .setTitle("xyz").build());
        assertEquals("xyz", si.getTitle());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getContext()).setId("id")
                .setText("xxx").build());
        assertEquals("xxx", si.getText());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getContext()).setId("id")
                .setIntent(makeIntent("action2")).build());
        assertEquals("action2", si.getIntent().getAction());
        assertEquals(null, si.getIntent().getStringExtra("key"));

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getContext()).setId("id")
                .setIntent(makeIntent("action3", "key", "x")).build());
        assertEquals("action3", si.getIntent().getAction());
        assertEquals("x", si.getIntent().getStringExtra("key"));

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getContext()).setId("id")
                .setWeight(999).build());
        assertEquals(999, si.getWeight());


        PersistableBundle pb2 = new PersistableBundle();
        pb2.putInt("x", 99);

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getContext()).setId("id")
                .setExtras(pb2).build());
        assertEquals(99, si.getExtras().getInt("x"));
    }
}
