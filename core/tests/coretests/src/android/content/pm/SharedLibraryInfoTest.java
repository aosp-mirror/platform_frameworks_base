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

package android.content.pm;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

@Presubmit
@RunWith(JUnit4.class)
public final class SharedLibraryInfoTest {

    @Rule
    public final CheckFlagsRule checkFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final String LIBRARY_NAME = "name";
    private static final long VERSION_MAJOR = 1L;
    private static final List<String> CERT_DIGESTS = ImmutableList.of("digest1", "digest2");

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_SDK_DEPENDENCY_INSTALLER)
    public void sharedLibraryInfo_serializedAndDeserialized_retainsCertDigestInfo() {
        SharedLibraryInfo toParcel = new SharedLibraryInfo(LIBRARY_NAME, VERSION_MAJOR,
                SharedLibraryInfo.TYPE_SDK_PACKAGE, CERT_DIGESTS);

        SharedLibraryInfo fromParcel = parcelAndUnparcel(toParcel);

        assertThat(fromParcel.getCertDigests().size()).isEqualTo(toParcel.getCertDigests().size());
        assertThat(fromParcel.getCertDigests().get(0)).isEqualTo(toParcel.getCertDigests().get(0));
        assertThat(fromParcel.getCertDigests().get(1)).isEqualTo(toParcel.getCertDigests().get(1));
    }

    private SharedLibraryInfo parcelAndUnparcel(SharedLibraryInfo sharedLibraryInfo) {
        Parcel parcel = Parcel.obtain();
        parcel.setDataPosition(0);
        sharedLibraryInfo.writeToParcel(parcel, /* flags= */0);

        parcel.setDataPosition(0);
        return SharedLibraryInfo.CREATOR.createFromParcel(parcel);
    }
}
