/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Binder;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.os.BinderCallHeavyHitterWatcher.HeavyHitterContainer;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Tests for {@link BinderCallHeavyHitterWatcher}.
 */
@RunWith(AndroidJUnit4.class)
public final class BinderHeavyHitterTest {

    private boolean mListenerNotified = false;

    private List<HeavyHitterContainer> mExpectedResult = null;

    /**
     * Generate random input.
     */
    private ArrayList<HeavyHitterContainer> generateRandomInput(final int total,
            final List<HeavyHitterContainer> heavyHitters,
            final List<Integer> numOfHeavyHits) {
        final ArrayList<HeavyHitterContainer> result = new ArrayList<>();
        List<HeavyHitterContainer> flatternedHeavyHitters = null;
        int totalHeavyHitters = 0;
        if (numOfHeavyHits != null) {
            flatternedHeavyHitters = new ArrayList<>();
            for (int i = numOfHeavyHits.size() - 1; i >= 0; i--) {
                final int k = numOfHeavyHits.get(i);
                totalHeavyHitters += k;
                final HeavyHitterContainer container = heavyHitters.get(i);
                for (int j = 0; j < k; j++) {
                    flatternedHeavyHitters.add(container);
                }
            }
        }
        int totalLightHitters = total - totalHeavyHitters;
        final Binder[] binders = {new TestBinder1(), new TestBinder2(), new TestBinder3()};
        final int maxUid = 1000;
        final int maxCode = 1000;
        final Random rand = new Random();
        for (int i = 0; i < total; i++) {
            HeavyHitterContainer container = null;
            if (totalLightHitters <= 0) {
                container = flatternedHeavyHitters.remove(rand.nextInt(totalHeavyHitters));
                totalHeavyHitters--;
            } else if (totalHeavyHitters <= 0) {
                container = newContainer(rand.nextInt(maxUid),
                        binders[rand.nextInt(binders.length)].getClass(),
                        rand.nextInt(maxCode), 0.0f);
                totalLightHitters--;
            } else {
                int val = rand.nextInt(total - i);
                if (val >= totalLightHitters) {
                    container = flatternedHeavyHitters.remove(rand.nextInt(totalHeavyHitters));
                    totalHeavyHitters--;
                } else {
                    container = newContainer(rand.nextInt(maxUid),
                            binders[rand.nextInt(binders.length)].getClass(),
                            rand.nextInt(maxCode), 0.0f);
                    totalLightHitters--;
                }
            }
            result.add(container);
        }
        return result;
    }

    private HeavyHitterContainer newContainer(final int uid, final Class clazz, final int code,
            final float freq) {
        final HeavyHitterContainer container = new HeavyHitterContainer();
        container.mUid = uid;
        container.mClass = clazz;
        container.mCode = code;
        container.mFrequency = freq;
        return container;
    }

    private void onResult(final List<HeavyHitterContainer> results, final Integer inputSize,
            final Float threshod, final Long timeSpan) {
        mListenerNotified = true;
        if (mExpectedResult == null) {
            assertTrue(results == null || results.size() == 0);
        } else {
            int size = mExpectedResult.size();
            assertEquals(size, results.size());
            for (int i = 0; i < size; i++) {
                final HeavyHitterContainer container = mExpectedResult.get(i);
                assertNotNull(container);
                assertTrue(results.remove(container));
            }
            assertEquals(0, results.size());
        }
    }

    @Test
    public void testPositive() throws Exception {
        BinderCallHeavyHitterWatcher watcher = BinderCallHeavyHitterWatcher.getInstance();
        try {
            List<HeavyHitterContainer> hitters = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            hitters.add(newContainer(1001, TestBinder4.class, 1002, 0.4f));
            counts.add(400);
            hitters.add(newContainer(2001, TestBinder5.class, 2002, 0.333f));
            counts.add(333);
            ArrayList<HeavyHitterContainer> inputs = generateRandomInput(1000, hitters, counts);
            inputs.addAll((List<HeavyHitterContainer>) inputs.clone());

            watcher.setConfig(true, inputs.size(), 0.333f, this::onResult);
            mListenerNotified = false;
            mExpectedResult = hitters;

            for (int i = inputs.size() - 1; i >= 0; i--) {
                final HeavyHitterContainer container = inputs.get(i);
                watcher.onTransaction(container.mUid, container.mClass, container.mCode);
            }
            assertTrue(mListenerNotified);
        } finally {
            watcher.setConfig(false, 0, 0.0f, null);
            mListenerNotified = false;
            mExpectedResult = null;
        }
    }

    @Test
    public void testNegative() throws Exception {
        BinderCallHeavyHitterWatcher watcher = BinderCallHeavyHitterWatcher.getInstance();
        try {
            List<HeavyHitterContainer> hitters = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            hitters.add(newContainer(1001, TestBinder4.class, 1002, 0.332f));
            counts.add(332);
            hitters.add(newContainer(2001, TestBinder5.class, 2002, 0.331f));
            counts.add(331);
            ArrayList<HeavyHitterContainer> inputs = generateRandomInput(1000, hitters, counts);
            inputs.addAll((List<HeavyHitterContainer>) inputs.clone());

            watcher.setConfig(true, inputs.size(), 0.333f, this::onResult);
            mListenerNotified = false;
            mExpectedResult = null;

            for (int i = inputs.size() - 1; i >= 0; i--) {
                final HeavyHitterContainer container = inputs.get(i);
                watcher.onTransaction(container.mUid, container.mClass, container.mCode);
            }
            assertFalse(mListenerNotified);
        } finally {
            watcher.setConfig(false, 0, 0.0f, null);
            mListenerNotified = false;
            mExpectedResult = null;
        }
    }

    private class TestBinder1 extends Binder {
    }

    private class TestBinder2 extends Binder {
    }

    private class TestBinder3 extends Binder {
    }

    private class TestBinder4 extends Binder {
    }

    private class TestBinder5 extends Binder {
    }
}
