/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.sharedlibloadingtest.client;

import static org.testng.Assert.assertEquals;

import android.content.Context;
import android.content.res.Resources;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.util.Preconditions;
import com.android.sharedlibloadingtest.ClientClass;
import com.android.sharedlibloadingtest.DuplicateClassA;
import com.android.sharedlibloadingtest.DuplicateClassB;
import com.android.sharedlibloadingtest.SharedClassAfter;
import com.android.sharedlibloadingtest.StdSharedClass;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.HashSet;

@RunWith(AndroidJUnit4.class)
public class SharedLibraryLoadingOrderTest {

    @Test
    public void testLoadingOfStdShareLibsShouldBeFirst() {
        Preconditions.checkArgument(!getLibsLoadedAfter()
                .contains("com.android.sharedlibloadingtest.shared_library"));
        DuplicateClassA clazz = new DuplicateClassA();
        assertEquals(clazz.toString(), "Standard Shared Lib's Version");

        StdSharedClass stdSharedClass = new StdSharedClass();
        assertEquals(stdSharedClass.toString(), "Nothing Special Lib");

        ClientClass clientCode = new ClientClass();
        assertEquals(clientCode.toString(), "Client Code");
    }

    @Test
    public void testLoadingOfShareLibsIsAfter() {
        Preconditions.checkArgument(getLibsLoadedAfter()
                .contains("com.android.sharedlibloadingtest.shared_library_after"));
        DuplicateClassB clazz = new DuplicateClassB();
        assertEquals(clazz.toString(), "Client's Version B");

        SharedClassAfter stdSharedClass = new SharedClassAfter();
        assertEquals(stdSharedClass.toString(), "Also Nothing Special");

        ClientClass clientCode = new ClientClass();
        assertEquals(clientCode.toString(), "Client Code");
    }

    @Test
    public void testLoadingOfResource() {
        // aapt compiler gives each lib their own namespace so this test just confirming
        // the resources can be loaded from the same context object
        Context context = ApplicationProvider.getApplicationContext();
        String clientString = context.getResources().getString(R.string.identical_resource_key);
        assertEquals(clientString, "client value");
        assertEquals(StdSharedClass.getResString(context), "std lib value");
        assertEquals(SharedClassAfter.getResString(context), "loaded after value");

    }

    private HashSet<String> getLibsLoadedAfter() {
        Resources systemR = Resources.getSystem();
        HashSet<String> libsToLoadAfter = new HashSet<>();
        Collections.addAll(libsToLoadAfter, systemR.getStringArray(
                com.android.internal.R.array.config_sharedLibrariesLoadedAfterApp));
        return libsToLoadAfter;
    }
}
