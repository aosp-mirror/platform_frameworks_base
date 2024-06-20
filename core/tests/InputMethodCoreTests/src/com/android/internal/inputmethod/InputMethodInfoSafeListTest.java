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

package com.android.internal.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.annotation.NonNull;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.view.inputmethod.InputMethodInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public final class InputMethodInfoSafeListTest {

    @NonNull
    private static InputMethodInfo createFakeInputMethodInfo(String packageName, String name) {
        final ResolveInfo ri = new ResolveInfo();
        final ServiceInfo si = new ServiceInfo();
        final ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ai.enabled = true;
        ai.flags |= ApplicationInfo.FLAG_SYSTEM;
        si.applicationInfo = ai;
        si.enabled = true;
        si.packageName = packageName;
        si.name = name;
        si.exported = true;
        si.nonLocalizedLabel = name;
        ri.serviceInfo = si;
        return new InputMethodInfo(ri, false, "", Collections.emptyList(), 1, false);
    }

    @NonNull
    private static List<InputMethodInfo> createTestInputMethodList() {
        final ArrayList<InputMethodInfo> list = new ArrayList<>();
        list.add(createFakeInputMethodInfo("com.android.test.ime1", "TestIme1"));
        list.add(createFakeInputMethodInfo("com.android.test.ime1", "TestIme2"));
        list.add(createFakeInputMethodInfo("com.android.test.ime2", "TestIme"));
        return list;
    }

    @Test
    public void testCreate() {
        assertNotNull(InputMethodInfoSafeList.create(createTestInputMethodList()));
    }

    @Test
    public void testExtract() {
        assertItemsAfterExtract(createTestInputMethodList(), InputMethodInfoSafeList::create);
    }

    @Test
    public void testExtractAfterParceling() {
        assertItemsAfterExtract(createTestInputMethodList(),
                originals -> cloneViaParcel(InputMethodInfoSafeList.create(originals)));
    }

    @Test
    public void testExtractEmptyList() {
        assertItemsAfterExtract(Collections.emptyList(), InputMethodInfoSafeList::create);
    }

    @Test
    public void testExtractAfterParcelingEmptyList() {
        assertItemsAfterExtract(Collections.emptyList(),
                originals -> cloneViaParcel(InputMethodInfoSafeList.create(originals)));
    }

    private static void assertItemsAfterExtract(@NonNull List<InputMethodInfo> originals,
            @NonNull Function<List<InputMethodInfo>, InputMethodInfoSafeList> factory) {
        final InputMethodInfoSafeList list = factory.apply(originals);
        final List<InputMethodInfo> extracted = InputMethodInfoSafeList.extractFrom(list);
        assertEquals(originals.size(), extracted.size());
        for (int i = 0; i < originals.size(); ++i) {
            assertNotSame("InputMethodInfoSafeList.extractFrom() must clone each instance",
                    originals.get(i), extracted.get(i));
            assertEquals("Verify the cloned instances have the equal value",
                    originals.get(i).getPackageName(), extracted.get(i).getPackageName());
        }

        // Subsequent calls of InputMethodInfoSafeList.extractFrom() return an empty list.
        final List<InputMethodInfo> extracted2 = InputMethodInfoSafeList.extractFrom(list);
        assertTrue(extracted2.isEmpty());
    }

    @NonNull
    private static InputMethodInfoSafeList cloneViaParcel(
            @NonNull InputMethodInfoSafeList original) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            final InputMethodInfoSafeList newInstance =
                    InputMethodInfoSafeList.CREATOR.createFromParcel(parcel);
            assertNotNull(newInstance);
            return newInstance;
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
    }
}
