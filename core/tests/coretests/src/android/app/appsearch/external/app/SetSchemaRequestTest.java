/*
 * Copyright 2020 The Android Open Source Project
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

import static android.app.appsearch.AppSearchSchema.PropertyConfig.INDEXING_TYPE_PREFIXES;
import static android.app.appsearch.AppSearchSchema.PropertyConfig.TOKENIZER_TYPE_PLAIN;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.util.ArrayMap;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class SetSchemaRequestTest {

    @Test
    public void testInvalidSchemaReferences_fromSystemUiVisibility() {
        IllegalArgumentException expected =
                expectThrows(
                        IllegalArgumentException.class,
                        () ->
                                new SetSchemaRequest.Builder()
                                        .setSchemaTypeVisibilityForSystemUi("InvalidSchema", false)
                                        .build());
        assertThat(expected).hasMessageThat().contains("referenced, but were not added");
    }

    @Test
    public void testInvalidSchemaReferences_fromPackageVisibility() {
        IllegalArgumentException expected =
                expectThrows(
                        IllegalArgumentException.class,
                        () ->
                                new SetSchemaRequest.Builder()
                                        .setSchemaTypeVisibilityForPackage(
                                                "InvalidSchema",
                                                /*visible=*/ true,
                                                new PackageIdentifier(
                                                        "com.foo.package",
                                                        /*certificate=*/ new byte[] {}))
                                        .build());
        assertThat(expected).hasMessageThat().contains("referenced, but were not added");
    }

    @Test
    public void testSchemaTypeVisibilityForSystemUi_visible() {
        AppSearchSchema schema = new AppSearchSchema.Builder("Schema").build();

        // By default, the schema is visible.
        SetSchemaRequest request = new SetSchemaRequest.Builder().addSchema(schema).build();
        assertThat(request.getSchemasNotPlatformSurfaceable()).isEmpty();

        request =
                new SetSchemaRequest.Builder()
                        .addSchema(schema)
                        .setSchemaTypeVisibilityForSystemUi("Schema", true)
                        .build();
        assertThat(request.getSchemasNotPlatformSurfaceable()).isEmpty();
    }

    @Test
    public void testSchemaTypeVisibilityForSystemUi_notVisible() {
        AppSearchSchema schema = new AppSearchSchema.Builder("Schema").build();
        SetSchemaRequest request =
                new SetSchemaRequest.Builder()
                        .addSchema(schema)
                        .setSchemaTypeVisibilityForSystemUi("Schema", false)
                        .build();
        assertThat(request.getSchemasNotPlatformSurfaceable()).containsExactly("Schema");
    }

    @Test
    public void testSchemaTypeVisibilityForPackage_visible() {
        AppSearchSchema schema = new AppSearchSchema.Builder("Schema").build();

        // By default, the schema is not visible.
        SetSchemaRequest request = new SetSchemaRequest.Builder().addSchema(schema).build();
        assertThat(request.getSchemasPackageAccessible()).isEmpty();

        PackageIdentifier packageIdentifier =
                new PackageIdentifier("com.package.foo", new byte[] {100});
        Map<String, Set<PackageIdentifier>> expectedPackageVisibleMap = new ArrayMap<>();
        expectedPackageVisibleMap.put("Schema", Collections.singleton(packageIdentifier));

        request =
                new SetSchemaRequest.Builder()
                        .addSchema(schema)
                        .setSchemaTypeVisibilityForPackage(
                                "Schema", /*visible=*/ true, packageIdentifier)
                        .build();
        assertThat(request.getSchemasPackageAccessible())
                .containsExactlyEntriesIn(expectedPackageVisibleMap);
    }

    @Test
    public void testSchemaTypeVisibilityForPackage_notVisible() {
        AppSearchSchema schema = new AppSearchSchema.Builder("Schema").build();

        SetSchemaRequest request =
                new SetSchemaRequest.Builder()
                        .addSchema(schema)
                        .setSchemaTypeVisibilityForPackage(
                                "Schema",
                                /*visible=*/ false,
                                new PackageIdentifier(
                                        "com.package.foo", /*certificate=*/ new byte[] {}))
                        .build();
        assertThat(request.getSchemasPackageAccessible()).isEmpty();
    }

    @Test
    public void testSchemaTypeVisibilityForPackage_deduped() throws Exception {
        AppSearchSchema schema = new AppSearchSchema.Builder("Schema").build();

        PackageIdentifier packageIdentifier =
                new PackageIdentifier("com.package.foo", new byte[] {100});
        Map<String, Set<PackageIdentifier>> expectedPackageVisibleMap = new ArrayMap<>();
        expectedPackageVisibleMap.put("Schema", Collections.singleton(packageIdentifier));

        SetSchemaRequest request =
                new SetSchemaRequest.Builder()
                        .addSchema(schema)
                        // Set it visible for "Schema"
                        .setSchemaTypeVisibilityForPackage(
                                "Schema", /*visible=*/ true, packageIdentifier)
                        // Set it visible for "Schema" again, which should be a no-op
                        .setSchemaTypeVisibilityForPackage(
                                "Schema", /*visible=*/ true, packageIdentifier)
                        .build();
        assertThat(request.getSchemasPackageAccessible())
                .containsExactlyEntriesIn(expectedPackageVisibleMap);
    }

    @Test
    public void testSchemaTypeVisibilityForPackage_removed() throws Exception {
        AppSearchSchema schema = new AppSearchSchema.Builder("Schema").build();

        SetSchemaRequest request =
                new SetSchemaRequest.Builder()
                        .addSchema(schema)
                        // First set it as visible
                        .setSchemaTypeVisibilityForPackage(
                                "Schema",
                                /*visible=*/ true,
                                new PackageIdentifier(
                                        "com.package.foo", /*certificate=*/ new byte[] {100}))
                        // Then make it not visible
                        .setSchemaTypeVisibilityForPackage(
                                "Schema",
                                /*visible=*/ false,
                                new PackageIdentifier(
                                        "com.package.foo", /*certificate=*/ new byte[] {100}))
                        .build();

        // Nothing should be visible.
        assertThat(request.getSchemasPackageAccessible()).isEmpty();
    }
}
