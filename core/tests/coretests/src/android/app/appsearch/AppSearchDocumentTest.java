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

package android.app.appsearch;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.app.appsearch.AppSearch.Document;

import androidx.test.filters.SmallTest;

import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.PropertyProto;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

@SmallTest
public class AppSearchDocumentTest {

    @Test
    public void testDocumentEquals_Identical() {
        Document document1 = Document.newBuilder("uri1", "schemaType1")
                .setCreationTimestampMillis(5L)
                .setProperty("longKey1", 1L, 2L, 3L)
                .setProperty("doubleKey1", 1.0, 2.0, 3.0)
                .setProperty("booleanKey1", true, false, true)
                .setProperty("stringKey1", "test-value1", "test-value2", "test-value3")
                .build();
        Document document2 = Document.newBuilder("uri1", "schemaType1")
                .setCreationTimestampMillis(5L)
                .setProperty("longKey1", 1L, 2L, 3L)
                .setProperty("doubleKey1", 1.0, 2.0, 3.0)
                .setProperty("booleanKey1", true, false, true)
                .setProperty("stringKey1", "test-value1", "test-value2", "test-value3")
                .build();
        assertThat(document1).isEqualTo(document2);
        assertThat(document1.hashCode()).isEqualTo(document2.hashCode());
    }

    @Test
    public void testDocumentEquals_DifferentOrder() {
        Document document1 = Document.newBuilder("uri1", "schemaType1")
                .setCreationTimestampMillis(5L)
                .setProperty("longKey1", 1L, 2L, 3L)
                .setProperty("doubleKey1", 1.0, 2.0, 3.0)
                .setProperty("booleanKey1", true, false, true)
                .setProperty("stringKey1", "test-value1", "test-value2", "test-value3")
                .build();

        // Create second document with same parameter but different order.
        Document document2 = Document.newBuilder("uri1", "schemaType1")
                .setCreationTimestampMillis(5L)
                .setProperty("booleanKey1", true, false, true)
                .setProperty("stringKey1", "test-value1", "test-value2", "test-value3")
                .setProperty("doubleKey1", 1.0, 2.0, 3.0)
                .setProperty("longKey1", 1L, 2L, 3L)
                .build();
        assertThat(document1).isEqualTo(document2);
        assertThat(document1.hashCode()).isEqualTo(document2.hashCode());
    }

    @Test
    public void testDocumentEquals_Failure() {
        Document document1 = Document.newBuilder("uri1", "schemaType1")
                .setCreationTimestampMillis(5L)
                .setProperty("longKey1", 1L, 2L, 3L)
                .build();

        // Create second document with same order but different value.
        Document document2 = Document.newBuilder("uri1", "schemaType1")
                .setCreationTimestampMillis(5L)
                .setProperty("longKey1", 1L, 2L, 4L) // Different
                .build();
        assertThat(document1).isNotEqualTo(document2);
        assertThat(document1.hashCode()).isNotEqualTo(document2.hashCode());
    }

    @Test
    public void testDocumentEquals_Failure_RepeatedFieldOrder() {
        Document document1 = Document.newBuilder("uri1", "schemaType1")
                .setCreationTimestampMillis(5L)
                .setProperty("booleanKey1", true, false, true)
                .build();

        // Create second document with same order but different value.
        Document document2 = Document.newBuilder("uri1", "schemaType1")
                .setCreationTimestampMillis(5L)
                .setProperty("booleanKey1", true, true, false) // Different
                .build();
        assertThat(document1).isNotEqualTo(document2);
        assertThat(document1.hashCode()).isNotEqualTo(document2.hashCode());
    }

    @Test
    public void testDocumentGetSingleValue() {
        Document document = Document.newBuilder("uri1", "schemaType1")
                .setCreationTimestampMillis(5L)
                .setScore(1)
                .setProperty("longKey1", 1L)
                .setProperty("doubleKey1", 1.0)
                .setProperty("booleanKey1", true)
                .setProperty("stringKey1", "test-value1").build();
        assertThat(document.getUri()).isEqualTo("uri1");
        assertThat(document.getSchemaType()).isEqualTo("schemaType1");
        assertThat(document.getCreationTimestampMillis()).isEqualTo(5);
        assertThat(document.getScore()).isEqualTo(1);
        assertThat(document.getPropertyLong("longKey1")).isEqualTo(1L);
        assertThat(document.getPropertyDouble("doubleKey1")).isEqualTo(1.0);
        assertThat(document.getPropertyBoolean("booleanKey1")).isTrue();
        assertThat(document.getPropertyString("stringKey1")).isEqualTo("test-value1");
    }

    @Test
    public void testDocumentGetArrayValues() {
        Document document = Document.newBuilder("uri1", "schemaType1")
                .setCreationTimestampMillis(5L)
                .setProperty("longKey1", 1L, 2L, 3L)
                .setProperty("doubleKey1", 1.0, 2.0, 3.0)
                .setProperty("booleanKey1", true, false, true)
                .setProperty("stringKey1", "test-value1", "test-value2", "test-value3")
                .build();

        assertThat(document.getUri()).isEqualTo("uri1");
        assertThat(document.getSchemaType()).isEqualTo("schemaType1");
        assertThat(document.getPropertyLongArray("longKey1")).asList().containsExactly(1L, 2L, 3L);
        assertThat(document.getPropertyDoubleArray("doubleKey1")).usingExactEquality()
                .containsExactly(1.0, 2.0, 3.0);
        assertThat(document.getPropertyBooleanArray("booleanKey1")).asList()
                .containsExactly(true, false, true);
        assertThat(document.getPropertyStringArray("stringKey1")).asList()
                .containsExactly("test-value1", "test-value2", "test-value3");
    }

    @Test
    public void testDocumentGetValues_DifferentTypes() {
        Document document = Document.newBuilder("uri1", "schemaType1")
                .setScore(1)
                .setProperty("longKey1", 1L)
                .setProperty("booleanKey1", true, false, true)
                .setProperty("stringKey1", "test-value1", "test-value2", "test-value3")
                .build();

        // Get a value for a key that doesn't exist
        assertThat(document.getPropertyDouble("doubleKey1")).isNull();
        assertThat(document.getPropertyDoubleArray("doubleKey1")).isNull();

        // Get a value with a single element as an array and as a single value
        assertThat(document.getPropertyLong("longKey1")).isEqualTo(1L);
        assertThat(document.getPropertyLongArray("longKey1")).asList().containsExactly(1L);

        // Get a value with multiple elements as an array and as a single value
        assertThat(document.getPropertyString("stringKey1")).isEqualTo("test-value1");
        assertThat(document.getPropertyStringArray("stringKey1")).asList()
                .containsExactly("test-value1", "test-value2", "test-value3");

        // Get a value of the wrong type
        assertThat(document.getPropertyDouble("longKey1")).isNull();
        assertThat(document.getPropertyDoubleArray("longKey1")).isNull();
    }

    @Test
    public void testDocumentInvalid() {
        Document.Builder builder = Document.newBuilder("uri1", "schemaType1");
        assertThrows(
                IllegalArgumentException.class, () -> builder.setProperty("test", new boolean[]{}));
    }

    @Test
    public void testDocumentProtoPopulation() {
        Document document = Document.newBuilder("uri1", "schemaType1")
                .setCreationTimestampMillis(5L)
                .setScore(1)
                .setProperty("longKey1", 1L)
                .setProperty("doubleKey1", 1.0)
                .setProperty("booleanKey1", true)
                .setProperty("stringKey1", "test-value1")
                .build();

        // Create the Document proto. Need to sort the property order by key.
        DocumentProto.Builder documentProtoBuilder = DocumentProto.newBuilder()
                .setUri("uri1").setSchema("schemaType1").setScore(1).setCreationTimestampMs(5L);
        HashMap<String, PropertyProto.Builder> propertyProtoMap = new HashMap<>();
        propertyProtoMap.put("longKey1",
                PropertyProto.newBuilder().setName("longKey1").addInt64Values(1L));
        propertyProtoMap.put("doubleKey1",
                PropertyProto.newBuilder().setName("doubleKey1").addDoubleValues(1.0));
        propertyProtoMap.put("booleanKey1",
                PropertyProto.newBuilder().setName("booleanKey1").addBooleanValues(true));
        propertyProtoMap.put("stringKey1",
                PropertyProto.newBuilder().setName("stringKey1").addStringValues("test-value1"));
        List<String> sortedKey = new ArrayList<>(propertyProtoMap.keySet());
        Collections.sort(sortedKey);
        for (String key : sortedKey) {
            documentProtoBuilder.addProperties(propertyProtoMap.get(key));
        }
        assertThat(document.getProto()).isEqualTo(documentProtoBuilder.build());
    }
}
