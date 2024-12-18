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
package android.app.appfunctions

import android.app.appsearch.GenericDocument
import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4


@RunWith(JUnit4::class)
class GenericDocumentWrapperTest {

    @Test
    fun parcelUnparcel() {
        val doc =
            GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
                .setPropertyLong("test", 42)
                .build()
        val wrapper = GenericDocumentWrapper(doc)

        val recovered = parcelUnparcel(wrapper)

        assertThat(recovered.value.getPropertyLong("test")).isEqualTo(42)
    }

    @Test
    fun parcelUnparcel_afterGetValue() {
        val doc =
            GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
                .setPropertyLong("test", 42)
                .build()
        val wrapper = GenericDocumentWrapper(doc)
        assertThat(wrapper.value.getPropertyLong("test")).isEqualTo(42)

        val recovered = parcelUnparcel(wrapper)

        assertThat(recovered.value.getPropertyLong("test")).isEqualTo(42)
    }


    @Test
    fun getValue() {
        val doc =
            GenericDocument.Builder<GenericDocument.Builder<*>>("", "", "")
                .setPropertyLong("test", 42)
                .build()
        val wrapper = GenericDocumentWrapper(doc)

        assertThat(wrapper.value.getPropertyLong("test")).isEqualTo(42)
    }

    private fun parcelUnparcel(obj: GenericDocumentWrapper): GenericDocumentWrapper {
        val parcel = Parcel.obtain()
        try {
            obj.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return GenericDocumentWrapper.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}