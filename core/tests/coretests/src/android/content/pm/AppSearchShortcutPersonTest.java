/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.content.pm;

import static com.google.common.truth.Truth.assertThat;

import android.app.Person;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;

@Presubmit
public class AppSearchShortcutPersonTest {

    @Test
    public void testBuildPersonAndGetValue() {
        final String name = "name";
        final String key = "key";
        final String uri = "name:name";

        final Person person = new AppSearchShortcutPerson.Builder(uri)
                .setName(name)
                .setKey(key)
                .setIsBot(true)
                .setIsImportant(false)
                .build()
                .toPerson();

        assertThat(person.getName()).isEqualTo(name);
        assertThat(person.getKey()).isEqualTo(key);
        assertThat(person.getUri()).isEqualTo(uri);
        assertThat(person.isBot()).isTrue();
        assertThat(person.isImportant()).isFalse();
    }
}
