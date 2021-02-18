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

import static org.testng.Assert.assertThrows;

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
}

