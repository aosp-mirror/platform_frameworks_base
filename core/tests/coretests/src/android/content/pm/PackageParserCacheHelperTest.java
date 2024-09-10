/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.content.pm;

import static org.junit.Assert.assertEquals;

import android.content.pm.PackageParserCacheHelper.ReadHelper;
import android.content.pm.PackageParserCacheHelper.WriteHelper;
import android.os.Bundle;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class PackageParserCacheHelperTest {
    @Test
    public void testParcelUnparcel() throws Exception {
        final Bundle source = new Bundle();
        source.putInt("i1", 123);
        source.putString("s1", "abcdef");
        source.putString("s2", "xyz");
        source.putString("s3", null);

        final Bundle nest = new Bundle();
        nest.putString("s1", "xyz");
        source.putBundle("b1", nest);

        final Parcel p = Parcel.obtain();
        final WriteHelper writeHelper = new WriteHelper(p);

        source.writeToParcel(p, 0);
        writeHelper.finishAndUninstall();

        p.setDataPosition(0);

        final ReadHelper readHelper = new ReadHelper(p);
        readHelper.startAndInstall();

        final Bundle dest = new Bundle();
        dest.readFromParcel(p);

        dest.size(); // Unparcel so that toString() returns the content.

        assertEquals(source.get("i1"), dest.get("i1"));
        assertEquals(source.get("s1"), dest.get("s1"));
        assertEquals(source.get("s2"), dest.get("s2"));
        assertEquals(source.get("s3"), dest.get("s3"));
        assertEquals(source.getBundle("b1").get("s1"), dest.getBundle("b1").get("s1"));
        assertEquals(source.keySet().size(), dest.keySet().size());
    }
}
