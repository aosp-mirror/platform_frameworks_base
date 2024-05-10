/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.nfc;

import static com.google.common.truth.Truth.assertThat;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class TechListParcelTest {

    private static final String[] TECH_LIST_1 = new String[] { "tech1.1", "tech1.2" };
    private static final String[] TECH_LIST_2 = new String[] { "tech2.1" };
    private static final String[] TECH_LIST_EMPTY = new String[] {};

    @Test
    public void testWriteParcel() {
        TechListParcel techListParcel = new TechListParcel(TECH_LIST_1, TECH_LIST_2);

        Parcel parcel = Parcel.obtain();
        techListParcel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        TechListParcel actualTechList =
                TechListParcel.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        assertThat(actualTechList.getTechLists().length).isEqualTo(2);
        assertThat(Arrays.equals(actualTechList.getTechLists()[0], TECH_LIST_1)).isTrue();
        assertThat(Arrays.equals(actualTechList.getTechLists()[1], TECH_LIST_2)).isTrue();
    }

    @Test
    public void testWriteParcelArrayEmpty() {
        TechListParcel techListParcel = new TechListParcel();

        Parcel parcel = Parcel.obtain();
        techListParcel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        TechListParcel actualTechList =
                TechListParcel.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        assertThat(actualTechList.getTechLists().length).isEqualTo(0);
    }

    @Test
    public void testWriteParcelElementEmpty() {
        TechListParcel techListParcel = new TechListParcel(TECH_LIST_EMPTY);

        Parcel parcel = Parcel.obtain();
        techListParcel.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        TechListParcel actualTechList =
                TechListParcel.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        assertThat(actualTechList.getTechLists().length).isEqualTo(1);
        assertThat(Arrays.equals(actualTechList.getTechLists()[0], TECH_LIST_EMPTY)).isTrue();
    }

}
