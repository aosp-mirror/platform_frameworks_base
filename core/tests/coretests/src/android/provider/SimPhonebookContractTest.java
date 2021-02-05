/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.provider;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.content.ContentValues;
import android.os.Parcel;
import android.provider.SimPhonebookContract.SimRecords.NameValidationResult;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SimPhonebookContractTest {

    @Test
    public void getContentUri_invalidEfType_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                SimPhonebookContract.SimRecords.getContentUri(1, 100)
        );
        assertThrows(IllegalArgumentException.class, () ->
                SimPhonebookContract.SimRecords.getContentUri(1, -1)
        );
        assertThrows(IllegalArgumentException.class, () ->
                SimPhonebookContract.SimRecords.getContentUri(1,
                        SimPhonebookContract.ElementaryFiles.EF_UNKNOWN)
        );
    }

    @Test
    public void getItemUri_invalidEfType_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                SimPhonebookContract.SimRecords.getItemUri(1, 100, 1)
        );
        assertThrows(IllegalArgumentException.class, () ->
                SimPhonebookContract.SimRecords.getItemUri(1, -1, 1)
        );
        assertThrows(IllegalArgumentException.class, () ->
                SimPhonebookContract.SimRecords.getItemUri(1,
                        SimPhonebookContract.ElementaryFiles.EF_UNKNOWN, 1)
        );
    }

    @Test
    public void getItemUri_invalidRecordIndex_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                SimPhonebookContract.SimRecords.getItemUri(1,
                        SimPhonebookContract.ElementaryFiles.EF_ADN, 0)
        );
        assertThrows(IllegalArgumentException.class, () ->
                SimPhonebookContract.SimRecords.getItemUri(1,
                        SimPhonebookContract.ElementaryFiles.EF_ADN, -1)
        );
    }

    @Test
    public void nameValidationResult_isValid_validNames() {
        assertThat(new NameValidationResult("", "", 0, 1).isValid()).isTrue();
        assertThat(new NameValidationResult("a", "a", 1, 1).isValid()).isTrue();
        assertThat(new NameValidationResult("First Last", "First Last", 10, 10).isValid()).isTrue();
        assertThat(
                new NameValidationResult("First Last", "First Last", 10, 100).isValid()).isTrue();
    }

    @Test
    public void nameValidationResult_isValid_invalidNames() {
        assertThat(new NameValidationResult("", "", 0, 0).isValid()).isFalse();
        assertThat(new NameValidationResult("ab", "ab", 2, 1).isValid()).isFalse();
        NameValidationResult unsupportedCharactersResult = new NameValidationResult("A_b_c",
                "A b c", 5, 5);
        assertThat(unsupportedCharactersResult.isValid()).isFalse();
        assertThat(unsupportedCharactersResult.isSupportedCharacter(0)).isTrue();
        assertThat(unsupportedCharactersResult.isSupportedCharacter(1)).isFalse();
        assertThat(unsupportedCharactersResult.isSupportedCharacter(2)).isTrue();
        assertThat(unsupportedCharactersResult.isSupportedCharacter(3)).isFalse();
        assertThat(unsupportedCharactersResult.isSupportedCharacter(4)).isTrue();
    }

    @Test
    public void nameValidationResult_parcel() {
        ContentValues values = new ContentValues();
        values.put("name", "Name");
        values.put("phone_number", "123");

        NameValidationResult result;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable(new NameValidationResult("name", "sanitized name", 1, 2), 0);
            parcel.setDataPosition(0);
            result = parcel.readParcelable(NameValidationResult.class.getClassLoader());
        } finally {
            parcel.recycle();
        }

        assertThat(result.getName()).isEqualTo("name");
        assertThat(result.getSanitizedName()).isEqualTo("sanitized name");
        assertThat(result.getEncodedLength()).isEqualTo(1);
        assertThat(result.getMaxEncodedLength()).isEqualTo(2);
    }
}

