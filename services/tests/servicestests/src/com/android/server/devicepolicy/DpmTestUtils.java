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

import android.os.FileUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.Printer;

import org.junit.Assert;

import java.io.File;
import java.util.List;

public class DpmTestUtils {
    private DpmTestUtils() {
    }

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
}
