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
 * limitations under the License
 */

package android.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Parcel;
import android.service.wallpaper.WallpaperService;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Tests for hidden WallpaperInfo methods.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class WallpaperInfoTest {

    @Test
    public void testSupportsAmbientMode() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();

        Intent intent = new Intent(WallpaperService.SERVICE_INTERFACE);
        intent.setPackage("com.android.internal.tests");
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> result = pm.queryIntentServices(intent, PackageManager.GET_META_DATA);
        assertEquals(1, result.size());
        ResolveInfo info = result.get(0);
        WallpaperInfo wallpaperInfo = new WallpaperInfo(context, info);

        // Defined as true in the XML
        assertTrue("supportsAmbientMode should be true, as defined in the XML.",
                wallpaperInfo.getSupportsAmbientMode());
        Parcel parcel = Parcel.obtain();
        wallpaperInfo.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);
        WallpaperInfo fromParcel = WallpaperInfo.CREATOR.createFromParcel(parcel);
        assertTrue("supportsAmbientMode should have been restored from parcelable",
                fromParcel.getSupportsAmbientMode());
        parcel.recycle();
    }
}

