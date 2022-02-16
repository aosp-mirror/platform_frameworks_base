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

import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class AppSearchResultTest {
    @Test
    public void testMapNullPointerException() {
        NullPointerException e =
                assertThrows(
                        NullPointerException.class,
                        () -> {
                            Object o = null;
                            o.toString();
                        });
        AppSearchResult<?> result = AppSearchResult.throwableToFailedResult(e);
        assertThat(result.getResultCode()).isEqualTo(AppSearchResult.RESULT_INTERNAL_ERROR);
        // Makes sure the exception name is included in the string. Some exceptions have terse or
        // missing strings so it's confusing to read the output without the exception name.
        assertThat(result.getErrorMessage()).startsWith("NullPointerException");
    }
}
