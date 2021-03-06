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


import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.util.ArrayMap;

import org.junit.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SetSchemaRequestTest {

    private static Collection<String> getSchemaTypesFromSetSchemaRequest(SetSchemaRequest request) {
        HashSet<String> schemaTypes = new HashSet<>();
        for (AppSearchSchema schema : request.getSchemas()) {
            schemaTypes.add(schema.getSchemaType());
        }
        return schemaTypes;
    }

    @Test
    public void testInvalidSchemaReferences_fromDisplayedBySystem() {
        IllegalArgumentException expected =
                expectThrows(
                        IllegalArgumentException.class,
                        () ->
                                new SetSchemaRequest.Builder()
                                        .setSchemaTypeDisplayedBySystem("InvalidSchema", false)
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
                                                        /*sha256Certificate=*/ new byte[] {}))
                                        .build());
        assertThat(expected).hasMessageThat().contains("referenced, but were not added");
    }

    @Test
    public void testSetSchemaTypeDisplayedBySystem_displayed() {
        AppSearchSchema schema = new AppSearchSchema.Builder("Schema").build();

        // By default, the schema is displayed.
        SetSchemaRequest request = new SetSchemaRequest.Builder().addSchemas(schema).build();
        assertThat(request.getSchemasNotDisplayedBySystem()).isEmpty();

        request =
                new SetSchemaRequest.Builder()
                        .addSchemas(schema)
                        .setSchemaTypeDisplayedBySystem("Schema", true)
                        .build();
        assertThat(request.getSchemasNotDisplayedBySystem()).isEmpty();
    }

    @Test
    public void testSetSchemaTypeDisplayedBySystem_notDisplayed() {
        AppSearchSchema schema = new AppSearchSchema.Builder("Schema").build();
        SetSchemaRequest request =
                new SetSchemaRequest.Builder()
                        .addSchemas(schema)
                        .setSchemaTypeDisplayedBySystem("Schema", false)
                        .build();
        assertThat(request.getSchemasNotDisplayedBySystem()).containsExactly("Schema");
    }

    @Test
    public void testSchemaTypeVisibilityForPackage_visible() {
        AppSearchSchema schema = new AppSearchSchema.Builder("Schema").build();

        // By default, the schema is not visible.
        SetSchemaRequest request = new SetSchemaRequest.Builder().addSchemas(schema).build();
        assertThat(request.getSchemasVisibleToPackages()).isEmpty();

        PackageIdentifier packageIdentifier =
                new PackageIdentifier("com.package.foo", new byte[] {100});
        Map<String, Set<PackageIdentifier>> expectedVisibleToPackagesMap = new ArrayMap<>();
        expectedVisibleToPackagesMap.put("Schema", Collections.singleton(packageIdentifier));

        request =
                new SetSchemaRequest.Builder()
                        .addSchemas(schema)
                        .setSchemaTypeVisibilityForPackage(
                                "Schema", /*visible=*/ true, packageIdentifier)
                        .build();
        assertThat(request.getSchemasVisibleToPackages())
                .containsExactlyEntriesIn(expectedVisibleToPackagesMap);
    }

    @Test
    public void testSchemaTypeVisibilityForPackage_notVisible() {
        AppSearchSchema schema = new AppSearchSchema.Builder("Schema").build();

        SetSchemaRequest request =
                new SetSchemaRequest.Builder()
                        .addSchemas(schema)
                        .setSchemaTypeVisibilityForPackage(
                                "Schema",
                                /*visible=*/ false,
                                new PackageIdentifier(
                                        "com.package.foo", /*sha256Certificate=*/ new byte[] {}))
                        .build();
        assertThat(request.getSchemasVisibleToPackages()).isEmpty();
    }

    @Test
    public void testSchemaTypeVisibilityForPackage_deduped() throws Exception {
        AppSearchSchema schema = new AppSearchSchema.Builder("Schema").build();

        PackageIdentifier packageIdentifier =
                new PackageIdentifier("com.package.foo", new byte[] {100});
        Map<String, Set<PackageIdentifier>> expectedVisibleToPackagesMap = new ArrayMap<>();
        expectedVisibleToPackagesMap.put("Schema", Collections.singleton(packageIdentifier));

        SetSchemaRequest request =
                new SetSchemaRequest.Builder()
                        .addSchemas(schema)
                        // Set it visible for "Schema"
                        .setSchemaTypeVisibilityForPackage(
                                "Schema", /*visible=*/ true, packageIdentifier)
                        // Set it visible for "Schema" again, which should be a no-op
                        .setSchemaTypeVisibilityForPackage(
                                "Schema", /*visible=*/ true, packageIdentifier)
                        .build();
        assertThat(request.getSchemasVisibleToPackages())
                .containsExactlyEntriesIn(expectedVisibleToPackagesMap);
    }

    @Test
    public void testSchemaTypeVisibilityForPackage_removed() throws Exception {
        AppSearchSchema schema = new AppSearchSchema.Builder("Schema").build();

        SetSchemaRequest request =
                new SetSchemaRequest.Builder()
                        .addSchemas(schema)
                        // First set it as visible
                        .setSchemaTypeVisibilityForPackage(
                                "Schema",
                                /*visible=*/ true,
                                new PackageIdentifier(
                                        "com.package.foo", /*sha256Certificate=*/ new byte[] {100}))
                        // Then make it not visible
                        .setSchemaTypeVisibilityForPackage(
                                "Schema",
                                /*visible=*/ false,
                                new PackageIdentifier(
                                        "com.package.foo", /*sha256Certificate=*/ new byte[] {100}))
                        .build();

        // Nothing should be visible.
        assertThat(request.getSchemasVisibleToPackages()).isEmpty();
    }
}
