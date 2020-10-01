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

package com.android.server.backup.restore;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.platform.test.annotations.Presubmit;

import com.android.server.backup.UserBackupManagerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class PerformUnifiedRestoreTaskTest {
    private static final String PACKAGE_NAME = "package";
    private static final String INCLUDED_KEY = "included_key";
    private static final String EXCLUDED_KEY_1 = "excluded_key_1";
    private static final String EXCLUDED_KEY_2 = "excluded_key_2";
    private static final String SYSTEM_PACKAGE_NAME = "android";
    private static final String NON_SYSTEM_PACKAGE_NAME = "package";

    @Mock private BackupDataInput mBackupDataInput;
    @Mock private BackupDataOutput mBackupDataOutput;
    @Mock private UserBackupManagerService mBackupManagerService;

    private Set<String> mExcludedkeys = new HashSet<>();
    private Map<String, String> mBackupData = new HashMap<>();
    // Mock BackupDataInput reads backup data from here.
    private Queue<String> mBackupDataSource;
    // Mock BackupDataOutput will write backup data here.
    private Set<String> mBackupDataDump;
    private PerformUnifiedRestoreTask mRestoreTask;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        populateTestData();

        mBackupDataSource = new ArrayDeque<>(mBackupData.keySet());
        when(mBackupDataInput.readNextHeader()).then(new Answer<Boolean>() {
            @Override
            public Boolean answer(InvocationOnMock invocation) throws Throwable {
                return !mBackupDataSource.isEmpty();
            }
        });
        when(mBackupDataInput.getKey()).then(new Answer<String>() {
            @Override
            public String answer(InvocationOnMock invocation) throws Throwable {
                return mBackupDataSource.poll();
            }
        });
        when(mBackupDataInput.getDataSize()).thenReturn(0);

        mBackupDataDump = new HashSet<>();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        when(mBackupDataOutput.writeEntityHeader(keyCaptor.capture(), anyInt())).then(
                new Answer<Void>() {
                    @Override
                    public Void answer(InvocationOnMock invocation) throws Throwable {
                        mBackupDataDump.add(keyCaptor.getValue());
                        return null;
                    }
                });

        mRestoreTask = new PerformUnifiedRestoreTask(mBackupManagerService);
    }

    private void populateTestData() {
        mBackupData = new HashMap<>();
        mBackupData.put(INCLUDED_KEY, "1");
        mBackupData.put(EXCLUDED_KEY_1, "2");
        mBackupData.put(EXCLUDED_KEY_2, "3");

        mExcludedkeys = new HashSet<>();
        mExcludedkeys.add(EXCLUDED_KEY_1);
        mExcludedkeys.add(EXCLUDED_KEY_2);
    }

    @Test
    public void testFilterExcludedKeys() throws Exception {
        when(mBackupManagerService.getExcludedRestoreKeys(eq(PACKAGE_NAME))).thenReturn(
                mExcludedkeys);

        mRestoreTask.filterExcludedKeys(PACKAGE_NAME, mBackupDataInput, mBackupDataOutput);

        // Verify only the correct were written into BackupDataOutput object.
        Set<String> allowedBackupKeys = new HashSet<>(mBackupData.keySet());
        allowedBackupKeys.removeAll(mExcludedkeys);
        assertEquals(allowedBackupKeys, mBackupDataDump);
    }

    @Test
    public void testGetExcludedKeysForPackage_alwaysReturnsLatestKeys() {
        Set<String> firstExcludedKeys = new HashSet<>(Collections.singletonList(EXCLUDED_KEY_1));
        when(mBackupManagerService.getExcludedRestoreKeys(eq(PACKAGE_NAME))).thenReturn(
                firstExcludedKeys);
        assertEquals(firstExcludedKeys, mRestoreTask.getExcludedKeysForPackage(PACKAGE_NAME));


        Set<String> secondExcludedKeys = new HashSet<>(Arrays.asList(EXCLUDED_KEY_1,
                EXCLUDED_KEY_2));
        when(mBackupManagerService.getExcludedRestoreKeys(eq(PACKAGE_NAME))).thenReturn(
                secondExcludedKeys);
        assertEquals(secondExcludedKeys, mRestoreTask.getExcludedKeysForPackage(PACKAGE_NAME));
    }

    @Test
    public void testStageBackupData_stageForNonSystemPackageWithKeysToExclude() {
        when(mBackupManagerService.getExcludedRestoreKeys(eq(NON_SYSTEM_PACKAGE_NAME))).thenReturn(
                mExcludedkeys);

        assertTrue(mRestoreTask.shouldStageBackupData(NON_SYSTEM_PACKAGE_NAME));
    }

    @Test
    public void testStageBackupData_stageForNonSystemPackageWithNoKeysToExclude() {
        when(mBackupManagerService.getExcludedRestoreKeys(any())).thenReturn(
                Collections.emptySet());

        assertTrue(mRestoreTask.shouldStageBackupData(NON_SYSTEM_PACKAGE_NAME));
    }

    @Test
    public void testStageBackupData_doNotStageForSystemPackageWithNoKeysToExclude() {
        when(mBackupManagerService.getExcludedRestoreKeys(any())).thenReturn(
                Collections.emptySet());

        assertFalse(mRestoreTask.shouldStageBackupData(SYSTEM_PACKAGE_NAME));
    }

    @Test
    public void testStageBackupData_stageForSystemPackageWithKeysToExclude() {
        when(mBackupManagerService.getExcludedRestoreKeys(eq(SYSTEM_PACKAGE_NAME))).thenReturn(
                mExcludedkeys);

        assertTrue(mRestoreTask.shouldStageBackupData(SYSTEM_PACKAGE_NAME));
    }
}
