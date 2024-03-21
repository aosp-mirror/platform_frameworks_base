/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.Log;
import android.util.SparseArray;

import org.mockito.invocation.InvocationOnMock;

import java.util.Arrays;

/**
 * Provides a version of Resources that defaults to all existing resources, but can have ids
 * changed to return specific values.
 * <p>
 * TestableResources are lazily initialized, be sure to call
 * {@link TestableContext#ensureTestableResources} before your tested code has an opportunity
 * to cache {@link Context#getResources}.
 * </p>
 */
public class TestableResources {

    private static final String TAG = "TestableResources";
    private final Resources mResources;
    private final SparseArray<Object> mOverrides = new SparseArray<>();

    /** Creates a TestableResources instance that calls through to the given real Resources. */
    public TestableResources(Resources realResources) {
        mResources = mock(Resources.class, withSettings()
                .spiedInstance(realResources)
                .defaultAnswer(this::answer));
    }

    /**
     * Gets the implementation of Resources that will return overridden values when called.
     */
    public Resources getResources() {
        return mResources;
    }

    /**
     * Sets a configuration for {@link #getResources()} to return to allow custom configs to
     * be set and tested.
     *
     * @param configuration the configuration to return from resources.
     */
    public void overrideConfiguration(Configuration configuration) {
        when(mResources.getConfiguration()).thenReturn(configuration);
    }

    /**
     * Sets the return value for the specified resource id.
     * <p>
     * Since resource ids are unique there is a single addOverride that will override the value
     * whenever it is gotten regardless of which method is used (i.e. getColor or getDrawable).
     * </p>
     *
     * @param id    The resource id to be overridden
     * @param value The value of the resource, null to cause a {@link Resources.NotFoundException}
     *              when gotten.
     */
    public void addOverride(int id, Object value) {
        mOverrides.put(id, value);
    }

    /**
     * Removes the override for the specified id.
     * <p>
     * This should be called over addOverride(id, null), because specifying a null value will
     * cause a {@link Resources.NotFoundException} whereas removing the override will actually
     * switch back to returning the default/real value of the resource.
     * </p>
     */
    public void removeOverride(int id) {
        mOverrides.remove(id);
    }

    private Object answer(InvocationOnMock invocationOnMock) throws Throwable {
        // Only try to override methods with an integer first argument
        if (invocationOnMock.getArguments().length > 0) {
            Object argument = invocationOnMock.getArgument(0);
            if (argument instanceof Integer) {
                try {
                    int id = (Integer)argument;
                    int index = mOverrides.indexOfKey(id);
                    if (index >= 0) {
                        Object value = mOverrides.valueAt(index);
                        if (value == null) throw new Resources.NotFoundException();
                        // Support for Resources.getString(resId, Object... formatArgs)
                        if (value instanceof String
                                && invocationOnMock.getMethod().getName().equals("getString")
                                && invocationOnMock.getArguments().length > 1) {
                            value = String.format(mResources.getConfiguration().getLocales().get(0),
                                    (String) value,
                                    Arrays.copyOfRange(invocationOnMock.getArguments(), 1,
                                            invocationOnMock.getArguments().length));
                        }
                        return value;
                    }
                } catch (Resources.NotFoundException e) {
                    // Let through NotFoundException.
                    throw e;
                } catch (Throwable t) {
                    // Generic catching for the many things that can go wrong, fall back to
                    // the real implementation.
                    Log.i(TAG, "Falling back to default resources call " + t);
                }
            }
        }
        return invocationOnMock.callRealMethod();
    }
}
