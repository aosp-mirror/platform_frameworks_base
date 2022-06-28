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
import android.content.ComponentName;
import android.content.Intent;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import org.junit.Test;

import java.util.Set;

@Presubmit
public class AppSearchShortcutInfoTest {

    @Test
    public void testBuildShortcutAndGetValue() {
        final String category =
                "android.app.stubs.SHARE_SHORTCUT_CATEGORY";
        final String id = "shareShortcut";
        final String shortcutIconResName = "shortcut";
        final ComponentName activity = new ComponentName("xxx", "s");
        final Person person = new Person.Builder()
                .setBot(false)
                .setName("BubbleBot")
                .setImportant(true)
                .build();

        final Set<String> categorySet = new ArraySet<>();
        categorySet.add(category);
        final Intent shortcutIntent = new Intent(Intent.ACTION_VIEW);
        final ShortcutInfo shortcut = new AppSearchShortcutInfo.Builder(/*packageName=*/"", id)
                .setActivity(activity)
                .setShortLabel(id)
                .setIconResName(shortcutIconResName)
                .setIntent(shortcutIntent)
                .setPerson(person)
                .setCategories(categorySet)
                .setFlags(ShortcutInfo.FLAG_LONG_LIVED)
                .build()
                .toShortcutInfo(0);

        assertThat(shortcut.getUserId()).isEqualTo(0);
        assertThat(shortcut.getId()).isEqualTo(id);
        assertThat(shortcut.getShortLabel()).isEqualTo(id);
        assertThat(shortcut.getIconResName()).isEqualTo(shortcutIconResName);
        assertThat(shortcut.getIntent().toString()).isEqualTo(shortcutIntent.toString());
        assertThat(shortcut.getPersons().length).isEqualTo(1);
        final Person target = shortcut.getPersons()[0];
        assertThat(target.getName()).isEqualTo(person.getName());
        assertThat(target.isBot()).isEqualTo(person.isBot());
        assertThat(target.isImportant()).isEqualTo(person.isImportant());
        assertThat(shortcut.getCategories()).isEqualTo(categorySet);
        assertThat(shortcut.getActivity()).isEqualTo(activity);
    }
}
