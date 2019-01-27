/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.view.contentcapture;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit test for {@link UserDataRemovalRequest}.
 *
 * <p>To run it:
 * {@code atest FrameworksCoreTests:android.view.contentcapture.UserDataRemovalRequestTest}
 */
@RunWith(MockitoJUnitRunner.class)
public class UserDataRemovalRequestTest {

    @Mock
    private final Uri mUri = Uri.parse("content://com.example/");

    private UserDataRemovalRequest.Builder mBuilder = new UserDataRemovalRequest.Builder();

    @Test
    public void testBuilder_addUri_invalid() {
        assertThrows(NullPointerException.class, () -> mBuilder.addUri(null, false));
    }

    @Test
    public void testBuilder_addUri_valid() {
        assertThat(mBuilder.addUri(mUri, false)).isNotNull();
        assertThat(mBuilder.addUri(Uri.parse("content://com.example2"), true)).isNotNull();
    }

    @Test
    public void testBuilder_addUriAfterForEverything() {
        assertThat(mBuilder.forEverything()).isNotNull();
        assertThrows(IllegalStateException.class, () -> mBuilder.addUri(mUri, false));
    }

    @Test
    public void testBuilder_forEverythingAfterAddingUri() {
        assertThat(mBuilder.addUri(mUri, false)).isNotNull();
        assertThrows(IllegalStateException.class, () -> mBuilder.forEverything());
    }

    @Test
    public void testBuild_invalid() {
        assertThrows(IllegalStateException.class, () -> mBuilder.build());
    }

    @Test
    public void testBuild_valid() {
        assertThat(new UserDataRemovalRequest.Builder().forEverything().build())
                .isNotNull();
        assertThat(new UserDataRemovalRequest.Builder().addUri(mUri, false).build())
                .isNotNull();
    }

    @Test
    public void testNoMoreInteractionsAfterBuild() {
        assertThat(mBuilder.forEverything().build()).isNotNull();

        assertThrows(IllegalStateException.class, () -> mBuilder.addUri(mUri, false));
        assertThrows(IllegalStateException.class, () -> mBuilder.forEverything());
        assertThrows(IllegalStateException.class, () -> mBuilder.build());

    }
}
