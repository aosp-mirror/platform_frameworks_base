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

package android.app;

import static android.app.BackgroundStartPrivileges.ALLOW_BAL;
import static android.app.BackgroundStartPrivileges.ALLOW_FGS;
import static android.app.BackgroundStartPrivileges.NONE;
import static android.app.BackgroundStartPrivileges.allowBackgroundActivityStarts;

import static com.google.common.truth.Truth.assertThat;

import android.os.Binder;
import android.os.IBinder;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;


@RunWith(AndroidJUnit4.class)
@SmallTest
public class BackgroundStartPrivilegesTest {

    private static final IBinder BINDER_A = new Binder();
    private static final IBinder BINDER_B = new Binder();
    private static final BackgroundStartPrivileges BSP_ALLOW_A =
            allowBackgroundActivityStarts(BINDER_A);
    private static final BackgroundStartPrivileges BSP_ALLOW_B =
            allowBackgroundActivityStarts(BINDER_B);

    @Test
    public void backgroundStartPrivilege_getters_work() {
        assertThat(ALLOW_BAL.getOriginatingToken()).isNull();
        assertThat(ALLOW_BAL.allowsBackgroundActivityStarts()).isEqualTo(true);
        assertThat(ALLOW_BAL.allowsBackgroundFgsStarts()).isEqualTo(true);
        assertThat(ALLOW_BAL.allowsAny()).isEqualTo(true);
        assertThat(ALLOW_BAL.allowsNothing()).isEqualTo(false);

        assertThat(ALLOW_FGS.getOriginatingToken()).isNull();
        assertThat(ALLOW_FGS.allowsBackgroundActivityStarts()).isEqualTo(false);
        assertThat(ALLOW_FGS.allowsBackgroundFgsStarts()).isEqualTo(true);
        assertThat(ALLOW_FGS.allowsAny()).isEqualTo(true);
        assertThat(ALLOW_FGS.allowsNothing()).isEqualTo(false);

        assertThat(NONE.getOriginatingToken()).isNull();
        assertThat(NONE.allowsBackgroundActivityStarts()).isEqualTo(false);
        assertThat(NONE.allowsBackgroundFgsStarts()).isEqualTo(false);
        assertThat(NONE.allowsAny()).isEqualTo(false);
        assertThat(NONE.allowsNothing()).isEqualTo(true);

        assertThat(BSP_ALLOW_A.getOriginatingToken()).isEqualTo(BINDER_A);
        assertThat(BSP_ALLOW_A.allowsBackgroundActivityStarts()).isEqualTo(true);
        assertThat(BSP_ALLOW_A.allowsBackgroundFgsStarts()).isEqualTo(true);
        assertThat(BSP_ALLOW_A.allowsAny()).isEqualTo(true);
        assertThat(BSP_ALLOW_A.allowsNothing()).isEqualTo(false);
    }

    @Test
    public void backgroundStartPrivilege_toString_returnsSomething() {
        assertThat(ALLOW_BAL.toString()).isNotEmpty();
        assertThat(ALLOW_FGS.toString()).isNotEmpty();
        assertThat(NONE.toString()).isNotEmpty();
        assertThat(BSP_ALLOW_A.toString()).isNotEmpty();
    }

    @Test
    public void backgroundStartPrivilege_mergeAA_resultsInA() {
        assertThat(BSP_ALLOW_A.merge(BSP_ALLOW_A)).isEqualTo(BSP_ALLOW_A);
    }

    @Test
    public void backgroundStartPrivilege_mergeAB_resultsInAllowBal() {
        assertThat(BSP_ALLOW_A.merge(BSP_ALLOW_B)).isEqualTo(ALLOW_BAL);
    }

    @Test
    public void backgroundStartPrivilege_mergeAwithAllowBal_resultsInAllowBal() {
        assertThat(BSP_ALLOW_A.merge(ALLOW_BAL)).isEqualTo(ALLOW_BAL);
    }

    @Test
    public void backgroundStartPrivilege_mergeAwithAllowFgs_resultsInAllowBal() {
        assertThat(BSP_ALLOW_A.merge(ALLOW_FGS)).isEqualTo(ALLOW_BAL);
    }

    @Test
    public void backgroundStartPrivilege_mergeAwithNone_resultsInA() {
        assertThat(BSP_ALLOW_A.merge(NONE)).isEqualTo(BSP_ALLOW_A);
    }

    @Test
    public void backgroundStartPrivilege_mergeManyWithDifferentToken_resultsInAllowBal() {
        assertThat(BackgroundStartPrivileges.merge(
                Arrays.asList(BSP_ALLOW_A, BSP_ALLOW_B, NONE, BSP_ALLOW_A, ALLOW_FGS)))
                .isEqualTo(ALLOW_BAL);
    }

    @Test
    public void backgroundStartPrivilege_mergeManyWithSameToken_resultsInAllowBal() {
        assertThat(BackgroundStartPrivileges.merge(
                Arrays.asList(BSP_ALLOW_A, BSP_ALLOW_A, BSP_ALLOW_A, BSP_ALLOW_A)))
                .isEqualTo(BSP_ALLOW_A);
    }
}
