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

package com.android.server.wm.utils;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.wm.utils.OptPropFactory.OptProp;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.function.BooleanSupplier;

/**
 * Build/Install/Run:
 * atest WmTests:OptPropFactoryTest
 */
@SmallTest
@Presubmit
public class OptPropFactoryTest {

    private PackageManager mPackageManager;
    private OptPropFactory mOptPropFactory;

    @Before
    public void setUp() {
        mPackageManager = mock(PackageManager.class);
        mOptPropFactory = new OptPropFactory(mPackageManager, "");
    }

    @Test
    public void optProp_laziness() throws PackageManager.NameNotFoundException {
        initPropAs(/* propertyValue */ true);
        // When OptPropBuilder is created the PackageManager is not used
        verify(mPackageManager, never()).getProperty(anyString(), anyString());

        // Accessing the value multiple times only uses PackageManager once
        final OptProp optProp = createOptProp();
        optProp.isTrue();
        optProp.isFalse();

        verify(mPackageManager).getProperty(anyString(), anyString());
    }

    @Test
    public void optProp_withSetValueTrue() throws PackageManager.NameNotFoundException {
        initPropAs(/* propertyValue */ true);

        final OptProp optProp = createOptProp();

        assertTrue(optProp.isTrue());
        assertFalse(optProp.isFalse());
    }

    @Test
    public void optProp_withSetValueFalse() throws PackageManager.NameNotFoundException {
        initPropAs(/* propertyValue */ false);

        final OptProp optProp = createOptProp();

        assertFalse(optProp.isTrue());
        assertTrue(optProp.isFalse());
    }

    @Test
    public void optProp_withSetValueWithConditionFalse()
            throws PackageManager.NameNotFoundException {
        initPropAs(/* propertyValue */ true);

        final OptProp optProp = createOptProp(() -> false);

        assertFalse(optProp.isTrue());
        assertFalse(optProp.isFalse());
    }

    @Test
    public void optProp_withUnsetValue() {
        final OptProp optProp = createOptProp();

        assertFalse(optProp.isTrue());
        assertFalse(optProp.isFalse());
    }

    @Test
    public void optProp_isUnsetWhenPropertyIsNotPresent()
            throws PackageManager.NameNotFoundException {
        initPropAsWithException();
        // Property is unset
        final OptProp optUnset = createOptProp();
        assertFalse(optUnset.isTrue());
        assertFalse(optUnset.isFalse());
    }

    @Test
    public void optProp_shouldEnableWithOverrideAndProperty()
            throws PackageManager.NameNotFoundException {
        // Property is unset
        final OptProp optUnset = createOptProp(() -> false);
        assertFalse(optUnset.shouldEnableWithOverrideAndProperty(/* override */ true));

        // The value is the override one
        final OptProp optUnsetOn = createOptProp();
        assertTrue(optUnsetOn.shouldEnableWithOverrideAndProperty(/* override */ true));
        assertFalse(optUnsetOn.shouldEnableWithOverrideAndProperty(/* override */ false));

        // Property is set to true
        initPropAs(true);
        final OptProp optTrue = createOptProp(() -> false);
        assertFalse(optTrue.shouldEnableWithOverrideAndProperty(/* override */ true));

        final OptProp optTrueOn = createOptProp(() -> true);
        assertTrue(optTrueOn.shouldEnableWithOverrideAndProperty(/* override */ true));
        assertTrue(optTrueOn.shouldEnableWithOverrideAndProperty(/* override */ false));

        // Property is set to false
        initPropAs(false);
        final OptProp optFalse = createOptProp(() -> false);
        assertFalse(optFalse.shouldEnableWithOverrideAndProperty(/* override */ true));

        final OptProp optFalseOn = createOptProp();
        assertFalse(optFalseOn.shouldEnableWithOverrideAndProperty(/* override */ true));
        assertFalse(optFalseOn.shouldEnableWithOverrideAndProperty(/* override */ false));
    }

    @Test
    public void optProp_shouldEnableWithOptInOverrideAndOptOutProperty()
            throws PackageManager.NameNotFoundException {
        // Property is unset
        final OptProp optUnset = createOptProp(() -> false);
        assertFalse(optUnset.shouldEnableWithOptInOverrideAndOptOutProperty(/* override */ true));

        final OptProp optUnsetOn = createOptProp();
        assertTrue(optUnsetOn.shouldEnableWithOptInOverrideAndOptOutProperty(/* override */ true));
        assertFalse(
                optUnsetOn.shouldEnableWithOptInOverrideAndOptOutProperty(/* override */ false));

        // Property is set to true
        initPropAs(true);
        final OptProp optTrue = createOptProp(() -> false);
        assertFalse(optTrue.shouldEnableWithOptInOverrideAndOptOutProperty(/* override */ true));

        // Is the value of the override
        final OptProp optTrueOn = createOptProp(() -> true);
        assertTrue(optTrueOn.shouldEnableWithOptInOverrideAndOptOutProperty(/* override */ true));
        assertFalse(optTrueOn.shouldEnableWithOptInOverrideAndOptOutProperty(/* override */ false));

        // Property is set to false
        initPropAs(false);
        final OptProp optFalse = createOptProp(() -> false);
        assertFalse(optFalse.shouldEnableWithOptInOverrideAndOptOutProperty(/* override */ true));

        // Always false ahatever is the value of the override
        final OptProp optFalseOn = createOptProp();
        assertFalse(optFalseOn.shouldEnableWithOptInOverrideAndOptOutProperty(/* override */ true));
        assertFalse(
                optFalseOn.shouldEnableWithOptInOverrideAndOptOutProperty(/* override */ false));
    }

    @Test
    public void optProp_shouldEnableWithOptOutOverrideAndProperty()
            throws PackageManager.NameNotFoundException {
        // Property is unset
        final OptProp optUnset = createOptProp(() -> false);
        assertFalse(optUnset.shouldEnableWithOptOutOverrideAndProperty(/* override */ true));

        // Is the negate of the override value
        final OptProp optUnsetOn = createOptProp();
        assertTrue(optUnsetOn.shouldEnableWithOptOutOverrideAndProperty(/* override */ false));
        assertFalse(optUnsetOn.shouldEnableWithOptOutOverrideAndProperty(/* override */ true));

        // Property is set to true
        initPropAs(true);
        final OptProp optTrue = createOptProp(() -> false);
        assertFalse(optTrue.shouldEnableWithOptOutOverrideAndProperty(/* override */ true));

        // Is the negate of the override value
        final OptProp optTrueOn = createOptProp(() -> true);
        assertTrue(optTrueOn.shouldEnableWithOptOutOverrideAndProperty(/* override */ false));
        assertFalse(optTrueOn.shouldEnableWithOptOutOverrideAndProperty(/* override */ true));

        // Property is set to false
        initPropAs(false);
        final OptProp optFalse = createOptProp(() -> false);
        assertFalse(optFalse.shouldEnableWithOptOutOverrideAndProperty(/* override */ true));

        // Always false ahatever is the value of the override
        final OptProp optFalseOn = createOptProp();
        assertFalse(optFalseOn.shouldEnableWithOptOutOverrideAndProperty(/* override */ true));
        assertFalse(optFalseOn.shouldEnableWithOptOutOverrideAndProperty(/* override */ false));
    }

    @Test
    public void optProp_gateConditionIsInvokedOnlyOncePerInvocation()
            throws PackageManager.NameNotFoundException {

        final FakeGateCondition trueCondition = new FakeGateCondition(/* returnValue */ true);
        final OptProp optProp = createOptProp(trueCondition);

        optProp.shouldEnableWithOverrideAndProperty(/* override value */ true);
        assertEquals(1, trueCondition.getInvocationCount());
        trueCondition.clearInvocationCount();

        initPropAs(true);
        optProp.shouldEnableWithOptInOverrideAndOptOutProperty(/* override value */ true);
        assertEquals(1, trueCondition.getInvocationCount());
        trueCondition.clearInvocationCount();

        optProp.shouldEnableWithOptOutOverrideAndProperty(/* override value */ true);
        assertEquals(1, trueCondition.getInvocationCount());
        trueCondition.clearInvocationCount();
    }

    private void initPropAs(boolean propertyValue) throws PackageManager.NameNotFoundException {
        Mockito.clearInvocations(mPackageManager);
        final PackageManager.Property prop = new PackageManager.Property(
                "", /* value */ propertyValue, "", "");
        when(mPackageManager.getProperty(anyString(), anyString())).thenReturn(prop);
    }

    private void initPropAsWithException() throws PackageManager.NameNotFoundException {
        Mockito.clearInvocations(mPackageManager);
        when(mPackageManager.getProperty("", "")).thenThrow(
                new PackageManager.NameNotFoundException());
    }

    private OptProp createOptProp() {
        return mOptPropFactory.create("");
    }

    private OptProp createOptProp(BooleanSupplier condition) {
        return mOptPropFactory.create("", condition);
    }

    private static class FakeGateCondition implements BooleanSupplier {

        private int mInvocationCount = 0;
        private final boolean mReturnValue;

        private FakeGateCondition(boolean returnValue) {
            mReturnValue = returnValue;
        }

        @Override
        public boolean getAsBoolean() {
            mInvocationCount++;
            return mReturnValue;
        }

        int getInvocationCount() {
            return mInvocationCount;
        }

        void clearInvocationCount() {
            mInvocationCount = 0;
        }

    }
}
