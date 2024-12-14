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

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.TypedValue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.frameworks.coretests.R;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ConfigurationBoundResourceCacheTest {

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder().build();

    private ConfigurationBoundResourceCache<Float> mCache;
    private Context mContext;

    private void assertEquals(float expected, float actual) {
        Assert.assertEquals(expected, actual, 0);
    }

    @Before
    public void setUp() throws Exception {
        mCache = new ConfigurationBoundResourceCache<>();
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void testGetEmpty() {
        final Resources res = mContext.getResources();
        assertNull(mCache.getInstance(-1, res, null));
    }

    @Test
    public void testSetGet() {
        mCache.put(1, null, new DummyFloatConstantState(5f),
                ThemedResourceCache.UNDEFINED_GENERATION);
        final Resources res = mContext.getResources();
        assertEquals(5f, mCache.getInstance(1, res, null));
        assertNotSame(5f, mCache.getInstance(1, res, null));
        assertNull(mCache.getInstance(1, res, mContext.getTheme()));
    }

    @Test
    public void testSetGetThemed() {
        mCache.put(1, mContext.getTheme(), new DummyFloatConstantState(5f),
                ThemedResourceCache.UNDEFINED_GENERATION);
        final Resources res = mContext.getResources();
        assertNull(mCache.getInstance(1, res, null));
        assertEquals(5f, mCache.getInstance(1, res, mContext.getTheme()));
        assertNotSame(5f, mCache.getInstance(1, res, mContext.getTheme()));
    }

    @Test
    public void testMultiThreadPutGet() {
        mCache.put(1, mContext.getTheme(), new DummyFloatConstantState(5f),
                ThemedResourceCache.UNDEFINED_GENERATION);
        mCache.put(1, null, new DummyFloatConstantState(10f),
                ThemedResourceCache.UNDEFINED_GENERATION);
        final Resources res = mContext.getResources();
        assertEquals(10f, mCache.getInstance(1, res, null));
        assertNotSame(10f, mCache.getInstance(1, res, null));
        assertEquals(5f, mCache.getInstance(1, res, mContext.getTheme()));
        assertNotSame(5f, mCache.getInstance(1, res, mContext.getTheme()));
    }

    @Test
    public void testVoidConfigChange() {
        TypedValue staticValue = new TypedValue();
        long key = 3L;
        final Resources res = mContext.getResources();
        res.getValue(R.dimen.resource_cache_test_generic, staticValue, true);
        float staticDim = TypedValue.complexToDimension(staticValue.data, res.getDisplayMetrics());
        mCache.put(key, mContext.getTheme(),
                new DummyFloatConstantState(staticDim, staticValue.changingConfigurations),
                ThemedResourceCache.UNDEFINED_GENERATION);
        final Configuration cfg = res.getConfiguration();
        Configuration newCnf = new Configuration(cfg);
        newCnf.orientation = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE ?
                Configuration.ORIENTATION_PORTRAIT
                : Configuration.ORIENTATION_LANDSCAPE;
        int changes = calcConfigChanges(res, newCnf);
        assertEquals(staticDim, mCache.getInstance(key, res, mContext.getTheme()));
        mCache.onConfigurationChange(changes);
        assertEquals(staticDim, mCache.getInstance(key, res, mContext.getTheme()));
    }

    @Test
    public void testEffectiveConfigChange() {
        TypedValue changingValue = new TypedValue();
        long key = 4L;
        final Resources res = mContext.getResources();
        res.getValue(R.dimen.resource_cache_test_orientation_dependent, changingValue, true);
        float changingDim = TypedValue.complexToDimension(changingValue.data,
                res.getDisplayMetrics());
        mCache.put(key, mContext.getTheme(),
                new DummyFloatConstantState(changingDim, changingValue.changingConfigurations),
                ThemedResourceCache.UNDEFINED_GENERATION);

        final Configuration cfg = res.getConfiguration();
        Configuration newCnf = new Configuration(cfg);
        newCnf.orientation = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE ?
                Configuration.ORIENTATION_PORTRAIT
                : Configuration.ORIENTATION_LANDSCAPE;
        int changes = calcConfigChanges(res, newCnf);
        assertEquals(changingDim,
                mCache.getInstance(key, res, mContext.getTheme()));
        mCache.onConfigurationChange(changes);
        assertNull(mCache.get(key, mContext.getTheme()));
    }

    @Test
    public void testConfigChangeMultipleResources() {
        TypedValue staticValue = new TypedValue();
        TypedValue changingValue = new TypedValue();
        final Resources res = mContext.getResources();
        res.getValue(R.dimen.resource_cache_test_generic, staticValue, true);
        res.getValue(R.dimen.resource_cache_test_orientation_dependent, changingValue, true);
        float staticDim = TypedValue.complexToDimension(staticValue.data, res.getDisplayMetrics());
        float changingDim = TypedValue.complexToDimension(changingValue.data,
                res.getDisplayMetrics());
        mCache.put(R.dimen.resource_cache_test_generic, mContext.getTheme(),
                new DummyFloatConstantState(staticDim, staticValue.changingConfigurations),
                ThemedResourceCache.UNDEFINED_GENERATION);
        mCache.put(R.dimen.resource_cache_test_orientation_dependent, mContext.getTheme(),
                new DummyFloatConstantState(changingDim, changingValue.changingConfigurations),
                ThemedResourceCache.UNDEFINED_GENERATION);
        final Configuration cfg = res.getConfiguration();
        Configuration newCnf = new Configuration(cfg);
        newCnf.orientation = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE ?
                Configuration.ORIENTATION_PORTRAIT
                : Configuration.ORIENTATION_LANDSCAPE;
        int changes = calcConfigChanges(res, newCnf);
        assertEquals(staticDim, mCache.getInstance(R.dimen.resource_cache_test_generic, res,
                mContext.getTheme()));
        assertEquals(changingDim,
                mCache.getInstance(R.dimen.resource_cache_test_orientation_dependent, res,
                        mContext.getTheme()));
        mCache.onConfigurationChange(changes);
        assertEquals(staticDim, mCache.getInstance(R.dimen.resource_cache_test_generic, res,
                mContext.getTheme()));
        assertNull(mCache.getInstance(R.dimen.resource_cache_test_orientation_dependent, res,
                mContext.getTheme()));
    }

    @Test
    public void testConfigChangeMultipleThemes() {
        TypedValue[] staticValues = new TypedValue[]{new TypedValue(), new TypedValue()};
        TypedValue[] changingValues = new TypedValue[]{new TypedValue(), new TypedValue()};
        float staticDim = 0;
        float changingDim = 0;
        final Resources res = mContext.getResources();
        for (int i = 0; i < 2; i++) {
            res.getValue(R.dimen.resource_cache_test_generic, staticValues[i], true);
            staticDim = TypedValue
                    .complexToDimension(staticValues[i].data, res.getDisplayMetrics());

            res.getValue(R.dimen.resource_cache_test_orientation_dependent, changingValues[i],
                    true);
            changingDim = TypedValue.complexToDimension(changingValues[i].data,
                    res.getDisplayMetrics());
            final Resources.Theme theme = i == 0 ? mContext.getTheme() : null;
            mCache.put(R.dimen.resource_cache_test_generic, theme,
                    new DummyFloatConstantState(staticDim, staticValues[i].changingConfigurations),
                    ThemedResourceCache.UNDEFINED_GENERATION);
            mCache.put(R.dimen.resource_cache_test_orientation_dependent, theme,
                    new DummyFloatConstantState(changingDim,
                            changingValues[i].changingConfigurations),
                    ThemedResourceCache.UNDEFINED_GENERATION);
        }
        final Configuration cfg = res.getConfiguration();
        Configuration newCnf = new Configuration(cfg);
        newCnf.orientation = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE ?
                Configuration.ORIENTATION_PORTRAIT
                : Configuration.ORIENTATION_LANDSCAPE;
        int changes = calcConfigChanges(res, newCnf);
        for (int i = 0; i < 2; i++) {
            final Resources.Theme theme = i == 0 ? mContext.getTheme() : null;
            assertEquals(staticDim,
                    mCache.getInstance(R.dimen.resource_cache_test_generic, res, theme));
            assertEquals(changingDim,
                    mCache.getInstance(R.dimen.resource_cache_test_orientation_dependent, res,
                            theme));
        }
        mCache.onConfigurationChange(changes);
        for (int i = 0; i < 2; i++) {
            final Resources.Theme theme = i == 0 ? mContext.getTheme() : null;
            assertEquals(staticDim,
                    mCache.getInstance(R.dimen.resource_cache_test_generic, res, theme));
            assertNull(mCache.getInstance(R.dimen.resource_cache_test_orientation_dependent, res,
                    theme));
        }
    }

    private static int calcConfigChanges(Resources resources, Configuration configuration) {
        return resources.calcConfigChanges(configuration);
    }

    static class DummyFloatConstantState extends ConstantState<Float> {

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
