/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.wm.shell.common.transition

import android.testing.AndroidTestingRunner
import android.view.SurfaceControl
import androidx.test.filters.SmallTest
import com.android.wm.shell.common.SuppliersUtilsTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [TransactionSupplier].
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:TransactionSupplierTest
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
class TransactionSupplierTest {

    @Test
    fun `SurfaceBuilderSupplier supplies a Transaction`() {
        val supplier = TransactionSupplier()
        SuppliersUtilsTest.assertSupplierProvidesValue(supplier) {
            it is SurfaceControl.Transaction
        }
    }
}
