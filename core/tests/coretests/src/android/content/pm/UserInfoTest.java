/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UserInfoTest {
    @Test
    public void testSimple() throws Exception {
        final UserInfo ui = new UserInfo(10, "Test", UserInfo.FLAG_GUEST);

        assertThat(ui.getUserHandle()).isEqualTo(UserHandle.of(10));
        assertThat(ui.name).isEqualTo("Test");

        // Derived based on userType field
        assertThat(ui.isManagedProfile()).isEqualTo(false);
        assertThat(ui.isGuest()).isEqualTo(true);
        assertThat(ui.isRestricted()).isEqualTo(false);
        assertThat(ui.isDemo()).isEqualTo(false);
        assertThat(ui.isCloneProfile()).isEqualTo(false);
        assertThat(ui.isCommunalProfile()).isEqualTo(false);
        assertThat(ui.isPrivateProfile()).isEqualTo(false);

        // Derived based on flags field
        assertThat(ui.isPrimary()).isEqualTo(false);
        assertThat(ui.isAdmin()).isEqualTo(false);
        assertThat(ui.isProfile()).isEqualTo(false);
        assertThat(ui.isEnabled()).isEqualTo(true);
        assertThat(ui.isQuietModeEnabled()).isEqualTo(false);
        assertThat(ui.isEphemeral()).isEqualTo(false);
        assertThat(ui.isForTesting()).isEqualTo(false);
        assertThat(ui.isInitialized()).isEqualTo(false);
        assertThat(ui.isFull()).isEqualTo(false);
        assertThat(ui.isMain()).isEqualTo(false);

        // Derived dynamically
        assertThat(ui.canHaveProfile()).isEqualTo(false);
    }

    @Test
    public void testDebug() throws Exception {
        final UserInfo ui = new UserInfo(10, "Test", UserInfo.FLAG_GUEST);

        assertThat(ui.toString()).isNotEmpty();
        assertThat(ui.toFullString()).isNotEmpty();
    }
}
