/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.devicepolicy;

import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import android.content.Context;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.test.AndroidTestCase;
import android.util.Log;
import android.util.Printer;

import org.junit.Assert;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import junit.framework.AssertionFailedError;

public class DpmTestUtils extends AndroidTestCase {
    public static void clearDir(File dir) {
        if (dir.exists()) {
            Assert.assertTrue("failed to delete dir", FileUtils.deleteContents(dir));
        }
        dir.mkdirs();
        Log.i(DpmTestBase.TAG, "Created " + dir);
    }

    public static int getListSizeAllowingNull(List<?> list) {
        return list == null ? 0 : list.size();
    }

    public static Bundle newRestrictions(String... restrictions) {
        final Bundle ret = new Bundle();
        for (String restriction : restrictions) {
            ret.putBoolean(restriction, true);
        }
        return ret;
    }

    public static void assertRestrictions(Bundle expected, Bundle actual) {
        final ArrayList<String> elist;
        if (expected == null) {
            elist = null;
        } else {
            elist = Lists.newArrayList();
            for (String key : expected.keySet()) {
                if (expected.getBoolean(key)) {
                    elist.add(key);
                }
            }
            Collections.sort(elist);
        }

        final ArrayList<String> alist;
        if (actual == null) {
            alist = null;
        } else {
            alist = Lists.newArrayList();
            for (String key : actual.keySet()) {
                if (actual.getBoolean(key)) {
                    alist.add(key);
                }
            }
            Collections.sort(alist);
        }

        assertEquals(elist, alist);
    }

    public static <T extends Parcelable> T cloneParcelable(T source) {
        Parcel p = Parcel.obtain();
        p.writeParcelable(source, 0);
        p.setDataPosition(0);
        final T clone = p.readParcelable(DpmTestUtils.class.getClassLoader());
        p.recycle();
        return clone;
    }

    public static Printer LOG_PRINTER = new Printer() {
        @Override
        public void println(String x) {
            Log.i(DpmTestBase.TAG, x);
        }
    };

    public static String readAsset(Context context, String assetPath) throws IOException {
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        context.getResources().getAssets().open(assetPath)))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
                sb.append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    public static void writeToFile(File path, String content)
            throws IOException {
        path.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(path)) {
            Log.i(DpmTestBase.TAG, "Writing to " + path);
            Log.i(DpmTestBase.TAG, content);
            writer.write(content);
        }
    }

    private static boolean checkAssertRestrictions(Bundle a, Bundle b) {
        try {
            assertRestrictions(a, b);
            return true;
        } catch (AssertionFailedError e) {
            return false;
        }
    }

    public void testAssertRestrictions() {
        final Bundle a = newRestrictions();
        final Bundle b = newRestrictions("a");
        final Bundle c = newRestrictions("a");
        final Bundle d = newRestrictions("b", "c");
        final Bundle e = newRestrictions("b", "c");

        assertTrue(checkAssertRestrictions(null, null));
        assertFalse(checkAssertRestrictions(null, a));
        assertFalse(checkAssertRestrictions(a, null));
        assertTrue(checkAssertRestrictions(a, a));

        assertFalse(checkAssertRestrictions(a, b));
        assertTrue(checkAssertRestrictions(b, c));

        assertFalse(checkAssertRestrictions(c, d));
        assertTrue(checkAssertRestrictions(d, e));
    }
}
