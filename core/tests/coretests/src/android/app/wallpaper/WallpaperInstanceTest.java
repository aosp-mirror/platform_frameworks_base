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
package android.app.wallpaper;

import static com.google.common.truth.Truth.assertThat;

import android.app.WallpaperInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Parcel;
import android.service.wallpaper.WallpaperService;

import androidx.test.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

@RunWith(JUnit4.class)
public class WallpaperInstanceTest {
    @Test
    public void equals_bothNullInfo_sameId_isTrue() {
        WallpaperDescription description = new WallpaperDescription.Builder().setId("123").build();
        WallpaperInstance instance1 = new WallpaperInstance(null, description);
        WallpaperInstance instance2 = new WallpaperInstance(null, description);

        assertThat(instance1).isEqualTo(instance2);
    }

    @Test
    public void equals_bothNullInfo_differentIds_isFalse() {
        WallpaperDescription description1 = new WallpaperDescription.Builder().setId("123").build();
        WallpaperDescription description2 = new WallpaperDescription.Builder().setId("456").build();
        WallpaperInstance instance1 = new WallpaperInstance(null, description1);
        WallpaperInstance instance2 = new WallpaperInstance(null, description2);

        assertThat(instance1).isNotEqualTo(instance2);
    }

    @Test
    public void equals_singleNullInfo_isFalse() throws Exception {
        WallpaperDescription description = new WallpaperDescription.Builder().build();
        WallpaperInstance instance1 = new WallpaperInstance(null, description);
        WallpaperInstance instance2 = new WallpaperInstance(makeWallpaperInfo(), description);

        assertThat(instance1).isNotEqualTo(instance2);
    }

    @Test
    public void equals_sameInfoAndId_isTrue() throws Exception {
        WallpaperDescription description = new WallpaperDescription.Builder().setId("123").build();
        WallpaperInstance instance1 = new WallpaperInstance(makeWallpaperInfo(), description);
        WallpaperInstance instance2 = new WallpaperInstance(makeWallpaperInfo(), description);

        assertThat(instance1).isEqualTo(instance2);
    }

    @Test
    public void equals_sameInfo_differentIds_isFalse() throws Exception {
        WallpaperDescription description1 = new WallpaperDescription.Builder().setId("123").build();
        WallpaperDescription description2 = new WallpaperDescription.Builder().setId("456").build();
        WallpaperInstance instance1 = new WallpaperInstance(makeWallpaperInfo(), description1);
        WallpaperInstance instance2 = new WallpaperInstance(makeWallpaperInfo(), description2);

        assertThat(instance1).isNotEqualTo(instance2);
    }

    @Test
    public void hash_nullInfo_works() {
        WallpaperDescription description1 = new WallpaperDescription.Builder().setId("123").build();
        WallpaperDescription description2 = new WallpaperDescription.Builder().setId("456").build();
        WallpaperInstance base = new WallpaperInstance(null, description1);
        WallpaperInstance sameId = new WallpaperInstance(null, description1);
        WallpaperInstance differentId = new WallpaperInstance(null, description2);

        assertThat(base.hashCode()).isEqualTo(sameId.hashCode());
        assertThat(base.hashCode()).isNotEqualTo(differentId.hashCode());
    }

    @Test
    public void hash_withInfo_works() throws Exception {
        WallpaperDescription description1 = new WallpaperDescription.Builder().setId("123").build();
        WallpaperDescription description2 = new WallpaperDescription.Builder().setId("456").build();
        WallpaperInstance base = new WallpaperInstance(makeWallpaperInfo(), description1);
        WallpaperInstance sameId = new WallpaperInstance(makeWallpaperInfo(), description1);
        WallpaperInstance differentId = new WallpaperInstance(makeWallpaperInfo(), description2);

        assertThat(base.hashCode()).isEqualTo(sameId.hashCode());
        assertThat(base.hashCode()).isNotEqualTo(differentId.hashCode());
    }

    @Test
    public void id_fromOverride() throws Exception {
        final String id = "override";
        WallpaperInstance instance = new WallpaperInstance(makeWallpaperInfo(),
                new WallpaperDescription.Builder().setId("abc123").build(), id);

        assertThat(instance.getId()).isEqualTo(id);
    }

    @Test
    public void id_fromDescription() throws Exception {
        final String id = "abc123";
        WallpaperInstance instance = new WallpaperInstance(makeWallpaperInfo(),
                new WallpaperDescription.Builder().setId(id).build());

        assertThat(instance.getId()).isEqualTo(id);
    }

    @Test
    public void id_fromComponent() throws Exception {
        WallpaperInfo info = makeWallpaperInfo();
        WallpaperInstance instance = new WallpaperInstance(info,
                new WallpaperDescription.Builder().build());

        assertThat(instance.getId()).isEqualTo(info.getComponent().flattenToString());
    }

    @Test
    public void id_default() {
        WallpaperInstance instance = new WallpaperInstance(null,
                new WallpaperDescription.Builder().build());

        assertThat(instance.getId()).isNotNull();
    }

    @Test
    public void parcel_roundTripSucceeds() throws Exception {
        WallpaperInstance source = new WallpaperInstance(makeWallpaperInfo(),
                new WallpaperDescription.Builder().build());

        Parcel parcel = Parcel.obtain();
        source.writeToParcel(parcel, 0);
        // Reset parcel for reading
        parcel.setDataPosition(0);

        WallpaperInstance destination = WallpaperInstance.CREATOR.createFromParcel(parcel);

        assertThat(destination.getInfo()).isNotNull();
        assertThat(destination.getInfo().getComponent()).isEqualTo(source.getInfo().getComponent());
        assertThat(destination.getId()).isEqualTo(source.getId());
        assertThat(destination.getDescription()).isEqualTo(source.getDescription());
    }

    @Test
    public void parcel_roundTripSucceeds_withNulls() {
        WallpaperInstance source = new WallpaperInstance(null,
                new WallpaperDescription.Builder().build());

        Parcel parcel = Parcel.obtain();
        source.writeToParcel(parcel, 0);
        // Reset parcel for reading
        parcel.setDataPosition(0);

        WallpaperInstance destination = WallpaperInstance.CREATOR.createFromParcel(parcel);

        assertThat(destination.getInfo()).isEqualTo(source.getInfo());
        assertThat(destination.getId()).isEqualTo(source.getId());
        assertThat(destination.getDescription()).isEqualTo(source.getDescription());
    }

    private WallpaperInfo makeWallpaperInfo() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        Intent intent = new Intent(WallpaperService.SERVICE_INTERFACE);
        intent.setPackage("com.android.frameworks.coretests");
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> result = pm.queryIntentServices(intent, PackageManager.GET_META_DATA);
        assertThat(result).hasSize(1);
        ResolveInfo info = result.getFirst();
        return new WallpaperInfo(context, info);
    }
}
