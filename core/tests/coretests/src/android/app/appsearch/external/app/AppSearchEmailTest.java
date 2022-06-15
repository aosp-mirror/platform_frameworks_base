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

import com.android.server.appsearch.testing.AppSearchEmail;

import org.junit.Test;

public class AppSearchEmailTest {

    @Test
    public void testBuildEmailAndGetValue() {
        AppSearchEmail email =
                new AppSearchEmail.Builder("namespace", "id")
                        .setFrom("FakeFromAddress")
                        .setCc("CC1", "CC2")
                        // Score and Property are mixed into the middle to make sure
                        // DocumentBuilder's
                        // methods can be interleaved with EmailBuilder's methods.
                        .setScore(1)
                        .setPropertyString("propertyKey", "propertyValue1", "propertyValue2")
                        .setSubject("subject")
                        .setBody("EmailBody")
                        .build();

        assertThat(email.getNamespace()).isEqualTo("namespace");
        assertThat(email.getId()).isEqualTo("id");
        assertThat(email.getFrom()).isEqualTo("FakeFromAddress");
        assertThat(email.getTo()).isNull();
        assertThat(email.getCc()).asList().containsExactly("CC1", "CC2");
        assertThat(email.getBcc()).isNull();
        assertThat(email.getScore()).isEqualTo(1);
        assertThat(email.getPropertyString("propertyKey")).isEqualTo("propertyValue1");
        assertThat(email.getPropertyStringArray("propertyKey"))
                .asList()
                .containsExactly("propertyValue1", "propertyValue2");
        assertThat(email.getSubject()).isEqualTo("subject");
        assertThat(email.getBody()).isEqualTo("EmailBody");
    }
}
