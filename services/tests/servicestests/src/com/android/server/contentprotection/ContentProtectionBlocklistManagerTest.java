/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.contentprotection;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.pm.PackageInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Test for {@link ContentProtectionBlocklistManager}.
 *
 * <p>Run with: {@code atest
 * FrameworksServicesTests:
 * com.android.server.contentprotection.ContentProtectionBlocklistManagerTest}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ContentProtectionBlocklistManagerTest {

    private static final String FIRST_PACKAGE_NAME = "com.test.first.package.name";

    private static final String SECOND_PACKAGE_NAME = "com.test.second.package.name";

    private static final String UNLISTED_PACKAGE_NAME = "com.test.unlisted.package.name";

    private static final String PACKAGE_NAME_BLOCKLIST_FILENAME =
            "/product/etc/res/raw/content_protection/package_name_blocklist.txt";

    private static final PackageInfo PACKAGE_INFO = new PackageInfo();

    private static final List<String> LINES =
            ImmutableList.of(FIRST_PACKAGE_NAME, SECOND_PACKAGE_NAME);

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private ContentProtectionPackageManager mMockContentProtectionPackageManager;

    private final List<String> mReadRawFiles = new ArrayList<>();

    private ContentProtectionBlocklistManager mContentProtectionBlocklistManager;

    @Before
    public void setup() {
        mContentProtectionBlocklistManager = new TestContentProtectionBlocklistManager();
    }

    @Test
    public void isAllowed_blocklistNotLoaded() {
        boolean actual = mContentProtectionBlocklistManager.isAllowed(FIRST_PACKAGE_NAME);

        assertThat(actual).isFalse();
        assertThat(mReadRawFiles).isEmpty();
        verifyZeroInteractions(mMockContentProtectionPackageManager);
    }

    @Test
    public void isAllowed_inBlocklist() {
        mContentProtectionBlocklistManager.updateBlocklist(LINES.size());

        boolean actual = mContentProtectionBlocklistManager.isAllowed(FIRST_PACKAGE_NAME);

        assertThat(actual).isFalse();
        verifyZeroInteractions(mMockContentProtectionPackageManager);
    }

    @Test
    public void isAllowed_packageInfoNotFound() {
        mContentProtectionBlocklistManager.updateBlocklist(LINES.size());
        when(mMockContentProtectionPackageManager.getPackageInfo(UNLISTED_PACKAGE_NAME))
                .thenReturn(null);

        boolean actual = mContentProtectionBlocklistManager.isAllowed(UNLISTED_PACKAGE_NAME);

        assertThat(actual).isFalse();
        verify(mMockContentProtectionPackageManager, never())
                .hasRequestedInternetPermissions(any());
        verify(mMockContentProtectionPackageManager, never()).isSystemApp(any());
        verify(mMockContentProtectionPackageManager, never()).isUpdatedSystemApp(any());
    }

    @Test
    public void isAllowed_notRequestedInternet() {
        mContentProtectionBlocklistManager.updateBlocklist(LINES.size());
        when(mMockContentProtectionPackageManager.getPackageInfo(UNLISTED_PACKAGE_NAME))
                .thenReturn(PACKAGE_INFO);
        when(mMockContentProtectionPackageManager.hasRequestedInternetPermissions(PACKAGE_INFO))
                .thenReturn(false);

        boolean actual = mContentProtectionBlocklistManager.isAllowed(UNLISTED_PACKAGE_NAME);

        assertThat(actual).isFalse();
        verify(mMockContentProtectionPackageManager, never()).isSystemApp(any());
        verify(mMockContentProtectionPackageManager, never()).isUpdatedSystemApp(any());
    }

    @Test
    public void isAllowed_systemApp() {
        mContentProtectionBlocklistManager.updateBlocklist(LINES.size());
        when(mMockContentProtectionPackageManager.getPackageInfo(UNLISTED_PACKAGE_NAME))
                .thenReturn(PACKAGE_INFO);
        when(mMockContentProtectionPackageManager.hasRequestedInternetPermissions(PACKAGE_INFO))
                .thenReturn(true);
        when(mMockContentProtectionPackageManager.isSystemApp(PACKAGE_INFO)).thenReturn(true);

        boolean actual = mContentProtectionBlocklistManager.isAllowed(UNLISTED_PACKAGE_NAME);

        assertThat(actual).isFalse();
        verify(mMockContentProtectionPackageManager, never()).isUpdatedSystemApp(any());
    }

    @Test
    public void isAllowed_updatedSystemApp() {
        mContentProtectionBlocklistManager.updateBlocklist(LINES.size());
        when(mMockContentProtectionPackageManager.getPackageInfo(UNLISTED_PACKAGE_NAME))
                .thenReturn(PACKAGE_INFO);
        when(mMockContentProtectionPackageManager.hasRequestedInternetPermissions(PACKAGE_INFO))
                .thenReturn(true);
        when(mMockContentProtectionPackageManager.isSystemApp(PACKAGE_INFO)).thenReturn(true);
        when(mMockContentProtectionPackageManager.isUpdatedSystemApp(PACKAGE_INFO))
                .thenReturn(true);

        boolean actual = mContentProtectionBlocklistManager.isAllowed(UNLISTED_PACKAGE_NAME);

        assertThat(actual).isFalse();
    }

    @Test
    public void isAllowed_allowed() {
        mContentProtectionBlocklistManager.updateBlocklist(LINES.size());
        when(mMockContentProtectionPackageManager.getPackageInfo(UNLISTED_PACKAGE_NAME))
                .thenReturn(PACKAGE_INFO);
        when(mMockContentProtectionPackageManager.hasRequestedInternetPermissions(PACKAGE_INFO))
                .thenReturn(true);
        when(mMockContentProtectionPackageManager.isSystemApp(PACKAGE_INFO)).thenReturn(false);
        when(mMockContentProtectionPackageManager.isUpdatedSystemApp(PACKAGE_INFO))
                .thenReturn(false);

        boolean actual = mContentProtectionBlocklistManager.isAllowed(UNLISTED_PACKAGE_NAME);

        assertThat(actual).isTrue();
    }

    @Test
    public void updateBlocklist_negativeSize() {
        mContentProtectionBlocklistManager.updateBlocklist(/* blocklistSize= */ -1);
        assertThat(mReadRawFiles).isEmpty();

        mContentProtectionBlocklistManager.isAllowed(FIRST_PACKAGE_NAME);
        verify(mMockContentProtectionPackageManager).getPackageInfo(FIRST_PACKAGE_NAME);
    }

    @Test
    public void updateBlocklist_zeroSize() {
        mContentProtectionBlocklistManager.updateBlocklist(/* blocklistSize= */ 0);
        assertThat(mReadRawFiles).isEmpty();

        mContentProtectionBlocklistManager.isAllowed(FIRST_PACKAGE_NAME);
        verify(mMockContentProtectionPackageManager).getPackageInfo(FIRST_PACKAGE_NAME);
    }

    @Test
    public void updateBlocklist_positiveSize_belowTotal() {
        mContentProtectionBlocklistManager.updateBlocklist(/* blocklistSize= */ 1);
        assertThat(mReadRawFiles).containsExactly(PACKAGE_NAME_BLOCKLIST_FILENAME);

        mContentProtectionBlocklistManager.isAllowed(FIRST_PACKAGE_NAME);
        mContentProtectionBlocklistManager.isAllowed(SECOND_PACKAGE_NAME);

        verify(mMockContentProtectionPackageManager, never()).getPackageInfo(FIRST_PACKAGE_NAME);
        verify(mMockContentProtectionPackageManager).getPackageInfo(SECOND_PACKAGE_NAME);
    }

    @Test
    public void updateBlocklist_positiveSize_aboveTotal() {
        mContentProtectionBlocklistManager.updateBlocklist(LINES.size() + 1);
        assertThat(mReadRawFiles).containsExactly(PACKAGE_NAME_BLOCKLIST_FILENAME);

        mContentProtectionBlocklistManager.isAllowed(FIRST_PACKAGE_NAME);
        mContentProtectionBlocklistManager.isAllowed(SECOND_PACKAGE_NAME);

        verify(mMockContentProtectionPackageManager, never()).getPackageInfo(FIRST_PACKAGE_NAME);
        verify(mMockContentProtectionPackageManager, never()).getPackageInfo(SECOND_PACKAGE_NAME);
    }

    private final class TestContentProtectionBlocklistManager
            extends ContentProtectionBlocklistManager {

        TestContentProtectionBlocklistManager() {
            super(mMockContentProtectionPackageManager);
        }

        @Override
        protected List<String> readLinesFromRawFile(@NonNull String filename) {
            mReadRawFiles.add(filename);
            return LINES;
        }
    }
}
