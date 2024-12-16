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

package android.nfc.cardemulation;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AidGroupTest {
    private AidGroup mAidGroup;

    @Before
    public void setUp() {
        List<String> aids = new ArrayList<>();
        aids.add("A0000000031010");
        aids.add("A0000000041010");
        aids.add("A0000000034710");
        aids.add("A000000300");
        mAidGroup = new AidGroup(aids, "payment");
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testGetCategory() {
        String category = mAidGroup.getCategory();
        assertThat(category).isNotNull();
        assertThat(category).isEqualTo("payment");
    }

    @Test
    public void testGetAids() {
        List<String> aids = mAidGroup.getAids();
        assertThat(aids).isNotNull();
        assertThat(aids.size()).isGreaterThan(0);
        assertThat(aids.get(0)).isEqualTo("A0000000031010");
    }

    @Test
    public void testWriteAsXml() throws IOException {
        XmlSerializer out = mock(XmlSerializer.class);
        mAidGroup.writeAsXml(out);
        verify(out, atLeastOnce()).startTag(isNull(), anyString());
        verify(out, atLeastOnce()).attribute(isNull(), anyString(), anyString());
        verify(out, atLeastOnce()).endTag(isNull(), anyString());
    }

    @Test
    public void testRightToParcel() {
        Parcel parcel = mock(Parcel.class);
        mAidGroup.writeToParcel(parcel, 0);
        verify(parcel).writeString8(anyString());
        verify(parcel).writeInt(anyInt());
        verify(parcel).writeStringList(any());
    }
}
