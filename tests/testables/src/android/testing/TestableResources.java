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
import static org.mockito.Mockito.withSettings;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.util.SparseArray;

import org.mockito.invocation.InvocationOnMock;

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
     * Sets the return value for the specified resource id.
     * <p>
     * Since resource ids are unique there is a single addOverride that will override the value
     * whenever it is gotten regardless of which method is used (i.e. getColor or getDrawable).
     * </p>
     * @param id The resource id to be overridden
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
     * @param id
     */
    public void removeOverride(int id) {
        mOverrides.remove(id);
    }

    private Object answer(InvocationOnMock invocationOnMock) throws Throwable {
        try {
            int id = invocationOnMock.getArgument(0);
            int index = mOverrides.indexOfKey(id);
            if (index >= 0) {
                Object value = mOverrides.valueAt(index);
                if (value == null) throw new Resources.NotFoundException();
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
        return invocationOnMock.callRealMethod();
    }
}
