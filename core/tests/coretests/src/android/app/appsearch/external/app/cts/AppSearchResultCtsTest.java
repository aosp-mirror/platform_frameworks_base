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

package android.app.appsearch.cts;

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.AppSearchResult;

import org.junit.Test;

public class AppSearchResultCtsTest {

    @Test
    public void testResultEquals_identical() {
        AppSearchResult<String> result1 = AppSearchResult.newSuccessfulResult("String");
        AppSearchResult<String> result2 = AppSearchResult.newSuccessfulResult("String");

        assertThat(result1).isEqualTo(result2);
        assertThat(result1.hashCode()).isEqualTo(result2.hashCode());

        AppSearchResult<String> result3 =
                AppSearchResult.newFailedResult(
                        AppSearchResult.RESULT_INTERNAL_ERROR, "errorMessage");
        AppSearchResult<String> result4 =
                AppSearchResult.newFailedResult(
                        AppSearchResult.RESULT_INTERNAL_ERROR, "errorMessage");

        assertThat(result3).isEqualTo(result4);
        assertThat(result3.hashCode()).isEqualTo(result4.hashCode());
    }

    @Test
    public void testResultEquals_failure() {
        AppSearchResult<String> result1 = AppSearchResult.newSuccessfulResult("String");
        AppSearchResult<String> result2 = AppSearchResult.newSuccessfulResult("Wrong");
        AppSearchResult<String> resultNull = AppSearchResult.newSuccessfulResult(/*value=*/ null);

        assertThat(result1).isNotEqualTo(result2);
        assertThat(result1.hashCode()).isNotEqualTo(result2.hashCode());
        assertThat(result1).isNotEqualTo(resultNull);
        assertThat(result1.hashCode()).isNotEqualTo(resultNull.hashCode());

        AppSearchResult<String> result3 =
                AppSearchResult.newFailedResult(
                        AppSearchResult.RESULT_INTERNAL_ERROR, "errorMessage");
        AppSearchResult<String> result4 =
                AppSearchResult.newFailedResult(AppSearchResult.RESULT_IO_ERROR, "errorMessage");

        assertThat(result3).isNotEqualTo(result4);
        assertThat(result3.hashCode()).isNotEqualTo(result4.hashCode());

        AppSearchResult<String> result5 =
                AppSearchResult.newFailedResult(AppSearchResult.RESULT_INTERNAL_ERROR, "Wrong");

        assertThat(result3).isNotEqualTo(result5);
        assertThat(result3.hashCode()).isNotEqualTo(result5.hashCode());

        AppSearchResult<String> result6 =
                AppSearchResult.newFailedResult(
                        AppSearchResult.RESULT_INTERNAL_ERROR, /*errorMessage=*/ null);

        assertThat(result3).isNotEqualTo(result6);
        assertThat(result3.hashCode()).isNotEqualTo(result6.hashCode());
    }
}
