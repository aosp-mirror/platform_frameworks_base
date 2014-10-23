/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.content.res;

import android.test.ActivityInstrumentationTestCase2;
import android.util.TypedValue;

import com.android.frameworks.coretests.R;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ConfigurationBoundResourceCacheTest
        extends ActivityInstrumentationTestCase2<ResourceCacheActivity> {

    ConfigurationBoundResourceCache<Float> mCache;

    Method mCalcConfigChanges;

    public ConfigurationBoundResourceCacheTest() {
        super(ResourceCacheActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mCache = new ConfigurationBoundResourceCache<Float>(getActivity().getResources());
    }

    public void testGetEmpty() {
        assertNull(mCache.get(-1, null));
    }

    public void testSetGet() {
        mCache.put(1, null, new DummyFloatConstantState(5f));
        assertEquals(5f, mCache.get(1, null));
        assertNotSame(5f, mCache.get(1, null));
        assertEquals(null, mCache.get(1, getActivity().getTheme()));
    }

    public void testSetGetThemed() {
        mCache.put(1, getActivity().getTheme(), new DummyFloatConstantState(5f));
        assertEquals(null, mCache.get(1, null));
        assertEquals(5f, mCache.get(1, getActivity().getTheme()));
        assertNotSame(5f, mCache.get(1, getActivity().getTheme()));
    }

    public void testMultiThreadPutGet() {
        mCache.put(1, getActivity().getTheme(), new DummyFloatConstantState(5f));
        mCache.put(1, null, new DummyFloatConstantState(10f));
        assertEquals(10f, mCache.get(1, null));
        assertNotSame(10f, mCache.get(1, null));
        assertEquals(5f, mCache.get(1, getActivity().getTheme()));
        assertNotSame(5f, mCache.get(1, getActivity().getTheme()));
    }

    public void testVoidConfigChange()
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        TypedValue staticValue = new TypedValue();
        long key = 3L;
        final Resources res = getActivity().getResources();
        res.getValue(R.dimen.resource_cache_test_generic, staticValue, true);
        float staticDim = TypedValue.complexToDimension(staticValue.data, res.getDisplayMetrics());
        mCache.put(key, getActivity().getTheme(),
                new DummyFloatConstantState(staticDim, staticValue.changingConfigurations));
        final Configuration cfg = res.getConfiguration();
        Configuration newCnf = new Configuration(cfg);
        newCnf.orientation = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE ?
                Configuration.ORIENTATION_PORTRAIT
                : Configuration.ORIENTATION_LANDSCAPE;
        int changes = calcConfigChanges(res, newCnf);
        assertEquals(staticDim, mCache.get(key, getActivity().getTheme()));
        mCache.onConfigurationChange(changes);
        assertEquals(staticDim, mCache.get(key, getActivity().getTheme()));
    }

    public void testEffectiveConfigChange()
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        TypedValue changingValue = new TypedValue();
        long key = 4L;
        final Resources res = getActivity().getResources();
        res.getValue(R.dimen.resource_cache_test_orientation_dependent, changingValue, true);
        float changingDim = TypedValue.complexToDimension(changingValue.data,
                res.getDisplayMetrics());
        mCache.put(key, getActivity().getTheme(),
                new DummyFloatConstantState(changingDim, changingValue.changingConfigurations));

        final Configuration cfg = res.getConfiguration();
        Configuration newCnf = new Configuration(cfg);
        newCnf.orientation = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE ?
                Configuration.ORIENTATION_PORTRAIT
                : Configuration.ORIENTATION_LANDSCAPE;
        int changes = calcConfigChanges(res, newCnf);
        assertEquals(changingDim, mCache.get(key, getActivity().getTheme()));
        mCache.onConfigurationChange(changes);
        assertNull(mCache.get(key, getActivity().getTheme()));
    }

    public void testConfigChangeMultipleResources()
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        TypedValue staticValue = new TypedValue();
        TypedValue changingValue = new TypedValue();
        final Resources res = getActivity().getResources();
        res.getValue(R.dimen.resource_cache_test_generic, staticValue, true);
        res.getValue(R.dimen.resource_cache_test_orientation_dependent, changingValue, true);
        float staticDim = TypedValue.complexToDimension(staticValue.data, res.getDisplayMetrics());
        float changingDim = TypedValue.complexToDimension(changingValue.data,
                res.getDisplayMetrics());
        mCache.put(R.dimen.resource_cache_test_generic, getActivity().getTheme(),
                new DummyFloatConstantState(staticDim, staticValue.changingConfigurations));
        mCache.put(R.dimen.resource_cache_test_orientation_dependent, getActivity().getTheme(),
                new DummyFloatConstantState(changingDim, changingValue.changingConfigurations));
        final Configuration cfg = res.getConfiguration();
        Configuration newCnf = new Configuration(cfg);
        newCnf.orientation = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE ?
                Configuration.ORIENTATION_PORTRAIT
                : Configuration.ORIENTATION_LANDSCAPE;
        int changes = calcConfigChanges(res, newCnf);
        assertEquals(staticDim, mCache.get(R.dimen.resource_cache_test_generic,
                getActivity().getTheme()));
        assertEquals(changingDim, mCache.get(R.dimen.resource_cache_test_orientation_dependent,
                getActivity().getTheme()));
        mCache.onConfigurationChange(changes);
        assertEquals(staticDim, mCache.get(R.dimen.resource_cache_test_generic,
                getActivity().getTheme()));
        assertNull(mCache.get(R.dimen.resource_cache_test_orientation_dependent,
                getActivity().getTheme()));
    }

    public void testConfigChangeMultipleThemes()
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        TypedValue[] staticValues = new TypedValue[]{new TypedValue(), new TypedValue()};
        TypedValue[] changingValues = new TypedValue[]{new TypedValue(), new TypedValue()};
        float staticDim = 0;
        float changingDim = 0;
        final Resources res = getActivity().getResources();
        for (int i = 0; i < 2; i++) {
            res.getValue(R.dimen.resource_cache_test_generic, staticValues[i], true);
            staticDim = TypedValue
                    .complexToDimension(staticValues[i].data, res.getDisplayMetrics());

            res.getValue(R.dimen.resource_cache_test_orientation_dependent, changingValues[i],
                    true);
            changingDim = TypedValue.complexToDimension(changingValues[i].data,
                    res.getDisplayMetrics());
            final Resources.Theme theme = i == 0 ? getActivity().getTheme() : null;
            mCache.put(R.dimen.resource_cache_test_generic, theme,
                    new DummyFloatConstantState(staticDim, staticValues[i].changingConfigurations));
            mCache.put(R.dimen.resource_cache_test_orientation_dependent, theme,
                    new DummyFloatConstantState(changingDim,
                            changingValues[i].changingConfigurations));
        }
        final Configuration cfg = res.getConfiguration();
        Configuration newCnf = new Configuration(cfg);
        newCnf.orientation = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE ?
                Configuration.ORIENTATION_PORTRAIT
                : Configuration.ORIENTATION_LANDSCAPE;
        int changes = calcConfigChanges(res, newCnf);
        for (int i = 0; i < 2; i++) {
            final Resources.Theme theme = i == 0 ? getActivity().getTheme() : null;
            assertEquals(staticDim, mCache.get(R.dimen.resource_cache_test_generic, theme));
            assertEquals(changingDim,
                    mCache.get(R.dimen.resource_cache_test_orientation_dependent, theme));
        }
        mCache.onConfigurationChange(changes);
        for (int i = 0; i < 2; i++) {
            final Resources.Theme theme = i == 0 ? getActivity().getTheme() : null;
            assertEquals(staticDim, mCache.get(R.dimen.resource_cache_test_generic, theme));
            assertNull(mCache.get(R.dimen.resource_cache_test_orientation_dependent, theme));
        }
    }

    private int calcConfigChanges(Resources resources, Configuration configuration)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (mCalcConfigChanges == null) {
            mCalcConfigChanges = Resources.class.getDeclaredMethod("calcConfigChanges",
                    Configuration.class);
            mCalcConfigChanges.setAccessible(true);
        }
        return (Integer) mCalcConfigChanges.invoke(resources, configuration);

    }

    static class DummyFloatConstantState extends
            ConstantState<Float> {

        final Float mObj;

        int mChangingConf = 0;

        DummyFloatConstantState(Float obj) {
            mObj = obj;
        }

        DummyFloatConstantState(Float obj, int changingConf) {
            mObj = obj;
            mChangingConf = changingConf;
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConf;
        }

        @Override
        public Float newInstance() {
            return new Float(mObj);
        }
    }
}
