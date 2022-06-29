/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.autofill;

import static com.android.server.autofill.AutofillManagerService.getAllowedCompatModePackages;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Map;

@RunWith(JUnit4.class)
public class AutofillManagerServiceTest {

    @Test
    public void testGetAllowedCompatModePackages_null() {
        assertThat(getAllowedCompatModePackages(null)).isNull();
    }

    @Test
    public void testGetAllowedCompatModePackages_empty() {
        assertThat(getAllowedCompatModePackages("")).isNull();
    }

    @Test
    public void testGetAllowedCompatModePackages_onePackageNoUrls() {
        assertThat(getAllowedCompatModePackages("one_is_the_loniest_package"))
                .containsExactly("one_is_the_loniest_package", null);
    }

    @Test
    public void testGetAllowedCompatModePackages_onePackageMissingEndDelimiter() {
        assertThat(getAllowedCompatModePackages("one_is_the_loniest_package[")).isEmpty();
    }

    @Test
    public void testGetAllowedCompatModePackages_onePackageOneUrl() {
        final Map<String, String[]> result =
                getAllowedCompatModePackages("one_is_the_loniest_package[url]");
        assertThat(result).hasSize(1);
        assertThat(result.get("one_is_the_loniest_package")).asList().containsExactly("url");
    }

    @Test
    public void testGetAllowedCompatModePackages_onePackageMultipleUrls() {
        final Map<String, String[]> result =
                getAllowedCompatModePackages("one_is_the_loniest_package[4,5,8,15,16,23,42]");
        assertThat(result).hasSize(1);
        assertThat(result.get("one_is_the_loniest_package")).asList()
            .containsExactly("4", "5", "8", "15", "16", "23", "42");
    }

    @Test
    public void testGetAllowedCompatModePackages_multiplePackagesOneInvalid() {
        final Map<String, String[]> result = getAllowedCompatModePackages("one:two[");
        assertThat(result).hasSize(1);
        assertThat(result.get("one")).isNull();
    }

    @Test
    public void testGetAllowedCompatModePackages_multiplePackagesMultipleUrls() {
        final Map<String, String[]> result =
                getAllowedCompatModePackages("p1[p1u1]:p2:p3[p3u1,p3u2]");
        assertThat(result).hasSize(3);
        assertThat(result.get("p1")).asList().containsExactly("p1u1");
        assertThat(result.get("p2")).isNull();
        assertThat(result.get("p3")).asList().containsExactly("p3u1", "p3u2");
    }

    @Test
    public void testGetAllowedCompatModePackages_threePackagesOneInvalid() {
        final Map<String, String[]> result =
                getAllowedCompatModePackages("p1[p1u1]:p2[:p3[p3u1,p3u2]");
        assertThat(result).hasSize(2);
        assertThat(result.get("p1")).asList().containsExactly("p1u1");
        assertThat(result.get("p3")).asList().containsExactly("p3u1", "p3u2");
    }
}
