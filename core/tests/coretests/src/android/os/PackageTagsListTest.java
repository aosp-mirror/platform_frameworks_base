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

package android.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class PackageTagsListTest {

    @Test
    public void testPackageTagsList() {
        PackageTagsList.Builder builder = new PackageTagsList.Builder()
                .add("package1", "attr1")
                .add("package1", "attr2")
                .add("package2");
        PackageTagsList list = builder.build();

        assertTrue(list.contains(builder.build()));
        assertTrue(list.contains("package1", "attr1"));
        assertTrue(list.contains("package1", "attr2"));
        assertTrue(list.contains("package2", "attr1"));
        assertTrue(list.contains("package2", "attr2"));
        assertTrue(list.contains("package2", "attr3"));
        assertTrue(list.containsAll("package2"));
        assertTrue(list.includes("package1"));
        assertTrue(list.includes("package2"));
        assertFalse(list.contains("package1", "attr3"));
        assertFalse(list.containsAll("package1"));
        assertFalse(list.includes("package3"));

        PackageTagsList bigList = builder.add("package3").build();
        assertTrue(bigList.contains(builder.build()));
        assertTrue(bigList.contains(list));
        assertFalse(list.contains(bigList));
    }

    @Test
    public void testPackageTagsList_BuildFromMap() {
        ArrayMap<String, ArraySet<String>> map = new ArrayMap<>();
        map.put("package1", new ArraySet<>(Arrays.asList("attr1", "attr2")));
        map.put("package2", new ArraySet<>());

        PackageTagsList.Builder builder = new PackageTagsList.Builder().add(map);
        PackageTagsList list = builder.build();

        assertTrue(list.contains(builder.build()));
        assertTrue(list.contains("package1", "attr1"));
        assertTrue(list.contains("package1", "attr2"));
        assertTrue(list.contains("package2", "attr1"));
        assertTrue(list.contains("package2", "attr2"));
        assertTrue(list.contains("package2", "attr3"));
        assertTrue(list.containsAll("package2"));
        assertTrue(list.includes("package1"));
        assertTrue(list.includes("package2"));
        assertFalse(list.contains("package1", "attr3"));
        assertFalse(list.containsAll("package1"));
        assertFalse(list.includes("package3"));

        map.put("package3", new ArraySet<>());
        PackageTagsList bigList = builder.add(map).build();
        assertTrue(bigList.contains(builder.build()));
        assertTrue(bigList.contains(list));
        assertFalse(list.contains(bigList));
    }

    @Test
    public void testWriteToParcel() {
        PackageTagsList list = new PackageTagsList.Builder()
                .add("package1", "attr1")
                .add("package1", "attr2")
                .add("package2")
                .build();
        Parcel parcel = Parcel.obtain();
        list.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        PackageTagsList newList = PackageTagsList.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        assertEquals(list, newList);
    }
}
