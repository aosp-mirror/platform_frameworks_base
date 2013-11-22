/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.mediaframeworktest.unit;

import android.test.suitebuilder.annotation.SmallTest;
import android.hardware.camera2.utils.*;
import android.hardware.camera2.utils.Decorator.DecoratorListener;

import junit.framework.Assert;

import java.lang.reflect.Method;

/**
 * adb shell am instrument -e class 'com.android.mediaframeworktest.unit.CameraUtilsDecoratorTest' \
 *      -w com.android.mediaframeworktest/.MediaFrameworkUnitTestRunner
 */
public class CameraUtilsDecoratorTest extends junit.framework.TestCase {
    private DummyListener mDummyListener;
    private DummyInterface mIface;

    @Override
    public void setUp() {
        mDummyListener = new DummyListener();
        mIface = Decorator.newInstance(new DummyImpl(), mDummyListener);
    }

    interface DummyInterface {
        int addValues(int x, int y, int z);

        void raiseException() throws Exception;

        void raiseUnsupportedOperationException() throws UnsupportedOperationException;
    }

    class DummyImpl implements DummyInterface {
        @Override
        public int addValues(int x, int y, int z) {
            return x + y + z;
        }

        @Override
        public void raiseException() throws Exception {
            throw new Exception("Test exception");
        }

        @Override
        public void raiseUnsupportedOperationException() throws UnsupportedOperationException {
            throw new UnsupportedOperationException("Test exception");
        }
    }

    class DummyListener implements DecoratorListener {

        public boolean beforeCalled = false;
        public boolean afterCalled = false;
        public boolean catchCalled = false;
        public boolean finallyCalled = false;
        public Object resultValue = null;

        public boolean raiseException = false;

        @Override
        public void onBeforeInvocation(Method m, Object[] args) {
            beforeCalled = true;
        }

        @Override
        public void onAfterInvocation(Method m, Object[] args, Object result) {
            afterCalled = true;
            resultValue = result;

            if (raiseException) {
                throw new UnsupportedOperationException("Test exception");
            }
        }

        @Override
        public boolean onCatchException(Method m, Object[] args, Throwable t) {
            catchCalled = true;
            return false;
        }

        @Override
        public void onFinally(Method m, Object[] args) {
            finallyCalled = true;
        }

    };

    @SmallTest
    public void testDecorator() {

        // TODO rewrite this using mocks

        assertTrue(mIface.addValues(1, 2, 3) == 6);
        assertTrue(mDummyListener.beforeCalled);
        assertTrue(mDummyListener.afterCalled);

        int resultValue = (Integer)mDummyListener.resultValue;
        assertTrue(resultValue == 6);
        assertTrue(mDummyListener.finallyCalled);
        assertFalse(mDummyListener.catchCalled);
    }

    @SmallTest
    public void testDecoratorExceptions() {

        boolean gotExceptions = false;
        try {
            mIface.raiseException();
        } catch (Exception e) {
            gotExceptions = true;
            assertTrue(e.getMessage() == "Test exception");
        }
        assertTrue(gotExceptions);
        assertTrue(mDummyListener.beforeCalled);
        assertFalse(mDummyListener.afterCalled);
        assertTrue(mDummyListener.catchCalled);
        assertTrue(mDummyListener.finallyCalled);
    }

    @SmallTest
    public void testDecoratorUnsupportedOperationException() {

        boolean gotExceptions = false;
        try {
            mIface.raiseUnsupportedOperationException();
        } catch (UnsupportedOperationException e) {
            gotExceptions = true;
            assertTrue(e.getMessage() == "Test exception");
        }
        assertTrue(gotExceptions);
        assertTrue(mDummyListener.beforeCalled);
        assertFalse(mDummyListener.afterCalled);
        assertTrue(mDummyListener.catchCalled);
        assertTrue(mDummyListener.finallyCalled);
    }

    @SmallTest
    public void testDecoratorRaisesException() {

        boolean gotExceptions = false;
        try {
            mDummyListener.raiseException = true;
            mIface.addValues(1, 2, 3);
            Assert.fail("unreachable");
        } catch (UnsupportedOperationException e) {
            gotExceptions = true;
            assertTrue(e.getMessage() == "Test exception");
        }
        assertTrue(gotExceptions);
        assertTrue(mDummyListener.beforeCalled);
        assertTrue(mDummyListener.afterCalled);
        assertFalse(mDummyListener.catchCalled);
        assertTrue(mDummyListener.finallyCalled);
    }
}
