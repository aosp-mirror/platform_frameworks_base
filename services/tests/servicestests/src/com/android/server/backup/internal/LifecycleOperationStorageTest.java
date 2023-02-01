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

package com.android.server.backup.internal;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.BackupRestoreTask;
import com.android.server.backup.OperationStorage.OpState;
import com.android.server.backup.OperationStorage.OpType;

import com.google.android.collect.Sets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Set;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class LifecycleOperationStorageTest {
    private static final int USER_ID = 0;
    private static final int TOKEN_1 = 1;
    private static final int TOKEN_2 = 2;
    private static final int TOKEN_3 = 3;
    private static final long RESULT = 123L;

    private static final String PKG_FOO = "com.android.foo";
    private static final String PKG_BAR = "com.android.bar";
    private static final String PKG_BAZ = "com.android.baz";
    private static final Set<String> MULTIPLE_PKG    = Sets.newHashSet(PKG_FOO);
    private static final Set<String> MULTIPLE_PKGS_1 = Sets.newHashSet(PKG_FOO, PKG_BAR);
    private static final Set<String> MULTIPLE_PKGS_2 = Sets.newHashSet(PKG_BAR, PKG_BAZ);

    @Mock private BackupRestoreTask mCallback;
    private LifecycleOperationStorage mOpStorage;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(/* testClass */ this);
        mOpStorage = new LifecycleOperationStorage(USER_ID);
    }

    @After
    public void tearDown() {}

    @Test
    public void testRegisterOperation_singleOperation() throws Exception {
        mOpStorage.registerOperation(TOKEN_1, OpState.PENDING, mCallback, OpType.BACKUP_WAIT);

        Set<Integer> tokens = mOpStorage.operationTokensForOpType(OpType.BACKUP_WAIT);

        assertThat(mOpStorage.numOperations()).isEqualTo(1);
        assertThat(tokens).isEqualTo(only(TOKEN_1));
    }

    @Test
    public void testRegisterOperation_multipleOperations() throws Exception {
        mOpStorage.registerOperation(TOKEN_1, OpState.PENDING, mCallback, OpType.BACKUP_WAIT);
        mOpStorage.registerOperation(TOKEN_2, OpState.ACKNOWLEDGED, mCallback, OpType.BACKUP_WAIT);

        Set<Integer> typeWaitTokens = mOpStorage.operationTokensForOpType(OpType.BACKUP_WAIT);
        Set<Integer> statePendingTokens = mOpStorage.operationTokensForOpState(OpState.PENDING);
        Set<Integer> stateAcknowledgedTokens =
                mOpStorage.operationTokensForOpState(OpState.ACKNOWLEDGED);

        assertThat(mOpStorage.numOperations()).isEqualTo(2);
        assertThat(typeWaitTokens).isEqualTo(Sets.newHashSet(TOKEN_1, TOKEN_2));
        assertThat(statePendingTokens).isEqualTo(only(TOKEN_1));
        assertThat(stateAcknowledgedTokens).isEqualTo(only(TOKEN_2));
    }

    @Test
    public void testRegisterOperationForPackages_singlePackage() throws Exception {
        mOpStorage.registerOperationForPackages(TOKEN_1, OpState.PENDING,
                MULTIPLE_PKG, mCallback, OpType.BACKUP_WAIT);

        Set<Integer> tokens = mOpStorage.operationTokensForPackage(PKG_FOO);

        assertThat(mOpStorage.numOperations()).isEqualTo(1);
        assertThat(tokens).isEqualTo(only(TOKEN_1));
    }

    @Test
    public void testRegisterOperationForPackages_multiplePackage() throws Exception {
        mOpStorage.registerOperationForPackages(TOKEN_1, OpState.PENDING,
                MULTIPLE_PKGS_1, mCallback, OpType.BACKUP);
        mOpStorage.registerOperationForPackages(TOKEN_2, OpState.PENDING,
                MULTIPLE_PKGS_2, mCallback, OpType.BACKUP);

        Set<Integer> tokensFoo = mOpStorage.operationTokensForPackage(PKG_FOO);
        Set<Integer> tokensBar = mOpStorage.operationTokensForPackage(PKG_BAR);
        Set<Integer> tokensBaz = mOpStorage.operationTokensForPackage(PKG_BAZ);

        assertThat(mOpStorage.numOperations()).isEqualTo(2);
        assertThat(tokensFoo).isEqualTo(only(TOKEN_1));
        assertThat(tokensBar).isEqualTo(Sets.newHashSet(TOKEN_1, TOKEN_2));
        assertThat(tokensBaz).isEqualTo(only(TOKEN_2));
    }

    @Test
    public void testRemoveOperation() throws Exception {
        mOpStorage.registerOperation(TOKEN_2, OpState.PENDING, mCallback, OpType.BACKUP_WAIT);

        Set<Integer> typeWaitTokens = mOpStorage.operationTokensForOpType(OpType.BACKUP_WAIT);
        Set<Integer> statePendingTokens = mOpStorage.operationTokensForOpState(OpState.PENDING);

        assertThat(mOpStorage.numOperations()).isEqualTo(1);
        assertThat(typeWaitTokens).isEqualTo(only(TOKEN_2));
        assertThat(statePendingTokens).isEqualTo(only(TOKEN_2));

        mOpStorage.removeOperation(TOKEN_2);

        typeWaitTokens = mOpStorage.operationTokensForOpType(OpType.BACKUP_WAIT);
        statePendingTokens = mOpStorage.operationTokensForOpState(OpState.PENDING);

        assertThat(mOpStorage.numOperations()).isEqualTo(0);
        assertThat(typeWaitTokens).isEmpty();
        assertThat(statePendingTokens).isEmpty();
    }

    @Test
    public void testRemoveOperation_removesPackageMappings() throws Exception {
        mOpStorage.registerOperationForPackages(TOKEN_1, OpState.PENDING, MULTIPLE_PKGS_1,
                mCallback, OpType.BACKUP);
        mOpStorage.registerOperationForPackages(TOKEN_2, OpState.PENDING, MULTIPLE_PKGS_2,
                mCallback, OpType.BACKUP);

        mOpStorage.removeOperation(TOKEN_2);

        Set<Integer> tokensFoo = mOpStorage.operationTokensForPackage(PKG_FOO);
        Set<Integer> tokensBar = mOpStorage.operationTokensForPackage(PKG_BAR);
        Set<Integer> tokensBaz = mOpStorage.operationTokensForPackage(PKG_BAZ);

        assertThat(mOpStorage.numOperations()).isEqualTo(1);
        assertThat(tokensFoo).isEqualTo(only(TOKEN_1));
        assertThat(tokensBar).isEqualTo(only(TOKEN_1));
        assertThat(tokensBaz).isEmpty();
    }

    @Test
    public void testIsBackupOperationInProgress() throws Exception {
        mOpStorage.registerOperation(TOKEN_1, OpState.ACKNOWLEDGED, mCallback, OpType.RESTORE_WAIT);
        assertThat(mOpStorage.isBackupOperationInProgress()).isFalse();

        mOpStorage.registerOperation(TOKEN_2, OpState.TIMEOUT, mCallback, OpType.BACKUP_WAIT);
        assertThat(mOpStorage.isBackupOperationInProgress()).isFalse();

        mOpStorage.registerOperation(TOKEN_3, OpState.PENDING, mCallback, OpType.BACKUP);
        assertThat(mOpStorage.isBackupOperationInProgress()).isTrue();
    }

    @Test
    public void testOnOperationComplete_pendingAdvancesState_invokesCallback() throws Exception {
        mOpStorage.registerOperation(TOKEN_1, OpState.PENDING, mCallback, OpType.BACKUP_WAIT);

        mOpStorage.onOperationComplete(TOKEN_1, RESULT, callback -> {
            mCallback.operationComplete(RESULT);
        });

        assertThat(mOpStorage.operationTokensForOpType(OpType.BACKUP_WAIT))
                .isEqualTo(only(TOKEN_1));
        assertThat(mOpStorage.operationTokensForOpState(OpState.PENDING)).isEmpty();
        assertThat(mOpStorage.operationTokensForOpState(OpState.ACKNOWLEDGED)).isNotEmpty();
        verify(mCallback).operationComplete(RESULT);
    }

    private Set<Integer> only(Integer val) {
        return Sets.newHashSet(val);
    }
}
