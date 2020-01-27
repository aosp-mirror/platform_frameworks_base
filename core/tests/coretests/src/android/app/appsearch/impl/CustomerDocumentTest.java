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

package android.app.appsearch.impl;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchDocument;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Tests that {@link AppSearchDocument} and {@link AppSearchDocument.Builder} are extendable by
 * developers.
 *
 * <p>This class is intentionally in a different package than {@link AppSearchDocument} to make sure
 * there are no package-private methods required for external developers to add custom types.
 */
@SmallTest
public class CustomerDocumentTest {

    private static byte[] sByteArray1 = new byte[]{(byte) 1, (byte) 2, (byte) 3};
    private static byte[] sByteArray2 = new byte[]{(byte) 4, (byte) 5, (byte) 6};
    private static AppSearchDocument sDocumentProperties1 = new AppSearchDocument
            .Builder("sDocumentProperties1", "sDocumentPropertiesSchemaType1")
            .build();
    private static AppSearchDocument sDocumentProperties2 = new AppSearchDocument
            .Builder("sDocumentProperties2", "sDocumentPropertiesSchemaType2")
            .build();

    @Test
    public void testBuildCustomerDocument() {
        CustomerDocument customerDocument = new CustomerDocument.Builder("uri1")
                .setScore(1)
                .setCreationTimestampMillis(0)
                .setProperty("longKey1", 1L, 2L, 3L)
                .setProperty("doubleKey1", 1.0, 2.0, 3.0)
                .setProperty("booleanKey1", true, false, true)
                .setProperty("stringKey1", "test-value1", "test-value2", "test-value3")
                .setProperty("byteKey1", sByteArray1, sByteArray2)
                .setProperty("documentKey1", sDocumentProperties1, sDocumentProperties2)
                .build();

        assertThat(customerDocument.getUri()).isEqualTo("uri1");
        assertThat(customerDocument.getSchemaType()).isEqualTo("customerDocument");
        assertThat(customerDocument.getScore()).isEqualTo(1);
        assertThat(customerDocument.getCreationTimestampMillis()).isEqualTo(0L);
        assertThat(customerDocument.getPropertyLongArray("longKey1")).asList()
                .containsExactly(1L, 2L, 3L);
        assertThat(customerDocument.getPropertyDoubleArray("doubleKey1")).usingExactEquality()
                .containsExactly(1.0, 2.0, 3.0);
        assertThat(customerDocument.getPropertyBooleanArray("booleanKey1")).asList()
                .containsExactly(true, false, true);
        assertThat(customerDocument.getPropertyStringArray("stringKey1")).asList()
                .containsExactly("test-value1", "test-value2", "test-value3");
        assertThat(customerDocument.getPropertyBytesArray("byteKey1")).asList()
                .containsExactly(sByteArray1, sByteArray2);
        assertThat(customerDocument.getPropertyDocumentArray("documentKey1")).asList()
                .containsExactly(sDocumentProperties1, sDocumentProperties2);
    }

    /**
     * An example document type for test purposes, defined outside of
     * {@link android.app.appsearch.AppSearch} (the way an external developer would define it).
     */
    private static class CustomerDocument extends AppSearchDocument {
        private CustomerDocument(AppSearchDocument document) {
            super(document);
        }

        public static class Builder extends AppSearchDocument.Builder<CustomerDocument.Builder> {
            private Builder(@NonNull String uri) {
                super(uri, "customerDocument");
            }

            @Override
            public CustomerDocument build() {
                return new CustomerDocument(super.build());
            }
        }
    }
}
