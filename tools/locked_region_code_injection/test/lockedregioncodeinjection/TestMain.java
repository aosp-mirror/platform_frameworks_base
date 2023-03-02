/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package lockedregioncodeinjection;

import org.junit.Assert;
import org.junit.Test;

/**
 * To run the unit tests, first build the two necessary artifacts.  Do this explicitly as they are
 * not generally retained by a normal "build all".  After lunching a target:
 *   m lockedregioncodeinjection
 *   m lockedregioncodeinjection_input
 *
 * <pre>
 * <code>
 * set -x
 *
 * croot frameworks/base/tools/locked_region_code_injection
 *
 * # Clean
 * mkdir -p out
 * rm -fr out/*
 *
 * # Paths to the build artifacts.  These assume linux-x86; YMMV.
 * ROOT=$TOP/out/host/linux-x86
 * EXE=$ROOT/bin/lockedregioncodeinjection
 * INPUT=$ROOT/frameworkd/lockedregioncodeinjection_input.jar
 *
 * # Run tool on unit tests.
 * $EXE -i $INPUT -o out/test_output.jar \
 *     --targets 'Llockedregioncodeinjection/TestTarget;' \
 *     --pre     'lockedregioncodeinjection/TestTarget.boost' \
 *     --post    'lockedregioncodeinjection/TestTarget.unboost'
 *
 * # Run unit tests.
 * java -ea -cp out/test_output.jar \
 *     org.junit.runner.JUnitCore lockedregioncodeinjection.TestMain
 * </code>
 * OR
 * <code>
 * bash test/unit-test.sh
 * </code>
 * </pre>
 */
public class TestMain {
    @Test
    public void testSimpleSynchronizedBlock() {
        TestTarget.resetCount();
        TestTarget t = new TestTarget();

        Assert.assertEquals(TestTarget.boostCount, 0);
        Assert.assertEquals(TestTarget.unboostCount, 0);
        Assert.assertEquals(TestTarget.invokeCount, 0);
        Assert.assertEquals(TestTarget.boostCountLocked, 0);
        Assert.assertEquals(TestTarget.unboostCountLocked, 0);

        synchronized (t) {
            Assert.assertEquals(TestTarget.boostCount, 1);
            Assert.assertEquals(TestTarget.unboostCount, 0);
            TestTarget.invoke();
        }

        Assert.assertEquals(TestTarget.boostCount, 1);
        Assert.assertEquals(TestTarget.unboostCount, 1);
        Assert.assertEquals(TestTarget.invokeCount, 1);
        Assert.assertEquals(TestTarget.boostCountLocked, 0);
        Assert.assertEquals(TestTarget.unboostCountLocked, 0);
    }

    @Test
    public void testSimpleSynchronizedMethod() {
        TestTarget.resetCount();
        TestTarget t = new TestTarget();

        Assert.assertEquals(TestTarget.boostCount, 0);
        Assert.assertEquals(TestTarget.unboostCount, 0);
        Assert.assertEquals(TestTarget.boostCountLocked, 0);
        Assert.assertEquals(TestTarget.unboostCountLocked, 0);

        t.synchronizedCall();

        Assert.assertEquals(TestTarget.boostCount, 1);
        Assert.assertEquals(TestTarget.unboostCount, 1);
        Assert.assertEquals(TestTarget.invokeCount, 1);
        Assert.assertEquals(TestTarget.boostCountLocked, 0);
        Assert.assertEquals(TestTarget.unboostCountLocked, 0);
    }

    @Test
    public void testSimpleSynchronizedMethod2() {
        TestTarget.resetCount();
        TestTarget t = new TestTarget();

        Assert.assertEquals(TestTarget.boostCount, 0);
        Assert.assertEquals(TestTarget.unboostCount, 0);
        Assert.assertEquals(TestTarget.boostCountLocked, 0);
        Assert.assertEquals(TestTarget.unboostCountLocked, 0);

        t.synchronizedCallReturnInt();

        Assert.assertEquals(TestTarget.boostCount, 1);
        Assert.assertEquals(TestTarget.unboostCount, 1);
        Assert.assertEquals(TestTarget.invokeCount, 1);
        Assert.assertEquals(TestTarget.boostCountLocked, 0);
        Assert.assertEquals(TestTarget.unboostCountLocked, 0);
    }

    @Test
    public void testSimpleSynchronizedMethod3() {
        TestTarget.resetCount();
        TestTarget t = new TestTarget();

        Assert.assertEquals(TestTarget.boostCount, 0);
        Assert.assertEquals(TestTarget.unboostCount, 0);

        t.synchronizedCallReturnObject();

        Assert.assertEquals(TestTarget.boostCount, 1);
        Assert.assertEquals(TestTarget.unboostCount, 1);
        Assert.assertEquals(TestTarget.invokeCount, 1);
    }

    @SuppressWarnings("unused")
    @Test
    public void testCaughtException() {
        TestTarget.resetCount();
        TestTarget t = new TestTarget();
        boolean caughtException = false;

        Assert.assertEquals(TestTarget.boostCount, 0);
        Assert.assertEquals(TestTarget.unboostCount, 0);
        Assert.assertEquals(TestTarget.unboostCount, 0);

        try {
            synchronized (t) {
                Assert.assertEquals(TestTarget.boostCount, 1);
                Assert.assertEquals(TestTarget.unboostCount, 0);
                if (true) {
                    throw new RuntimeException();
                }
                TestTarget.invoke();
            }
        } catch (Throwable e) {
            caughtException = true;
        }

        Assert.assertEquals(TestTarget.boostCount, 1);
        Assert.assertEquals(TestTarget.unboostCount, 1);
        Assert.assertEquals(TestTarget.invokeCount, 0); // Not called
        Assert.assertTrue(caughtException);
    }

    @SuppressWarnings("unused")
    private void testUncaughtException() {
        TestTarget t = new TestTarget();
        synchronized (t) {
            if (true) {
                throw new RuntimeException();
            }
            TestTarget.invoke();
        }
    }

    @SuppressWarnings("unused")
    @Test
    public void testHandledFinally() {
        TestTarget.resetCount();
        try {
            testUncaughtException();
        } catch (Throwable t) {

        }
        Assert.assertEquals(TestTarget.boostCount, 1);
        Assert.assertEquals(TestTarget.unboostCount, 1);
        Assert.assertEquals(TestTarget.invokeCount, 0); // Not called
    }

    @Test
    public void testNestedSynchronizedBlock() {
        TestTarget.resetCount();
        TestTarget t = new TestTarget();

        Assert.assertEquals(TestTarget.boostCount, 0);
        Assert.assertEquals(TestTarget.unboostCount, 0);
        Assert.assertEquals(TestTarget.unboostCount, 0);

        synchronized (t) {
            synchronized (t) {
                synchronized (t) {
                    synchronized (t) {
                        synchronized (t) {
                            synchronized (t) {
                                Assert.assertEquals(TestTarget.boostCount, 6);
                                Assert.assertEquals(TestTarget.unboostCount, 0);
                                TestTarget.invoke();
                            }
                            Assert.assertEquals(TestTarget.unboostCount, 1);
                        }
                        Assert.assertEquals(TestTarget.unboostCount, 2);
                    }
                    Assert.assertEquals(TestTarget.unboostCount, 3);
                }
                Assert.assertEquals(TestTarget.unboostCount, 4);
            }
            Assert.assertEquals(TestTarget.unboostCount, 5);
        }

        Assert.assertEquals(TestTarget.boostCount, 6);
        Assert.assertEquals(TestTarget.unboostCount, 6);
        Assert.assertEquals(TestTarget.invokeCount, 1);
    }

    @Test
    public void testMethodWithControlFlow() {
        TestTarget.resetCount();
        TestTarget t = new TestTarget();

        Assert.assertEquals(TestTarget.boostCount, 0);
        Assert.assertEquals(TestTarget.unboostCount, 0);

        if ((t.hashCode() + " ").contains("1")) {
            t.synchronizedCall();
        } else {
            t.synchronizedCall();
        }

        // Should only be boosted once.
        Assert.assertEquals(TestTarget.boostCount, 1);
        Assert.assertEquals(TestTarget.unboostCount, 1);
        Assert.assertEquals(TestTarget.invokeCount, 1);
    }

    @Test
    public void testUnboostThatThrows() {
        TestTarget.resetCount();
        TestTarget t = new TestTarget();
        boolean asserted = false;

        Assert.assertEquals(TestTarget.boostCount, 0);
        Assert.assertEquals(TestTarget.unboostCount, 0);

        try {
            t.synchronizedThrowsOnUnboost();
        } catch (RuntimeException e) {
            asserted = true;
        }

        Assert.assertEquals(asserted, true);
        Assert.assertEquals(TestTarget.boostCount, 1);
        Assert.assertEquals(TestTarget.unboostCount, 0);
        Assert.assertEquals(TestTarget.invokeCount, 1);
    }

    @Test
    public void testScopedTarget() {
        TestScopedTarget target = new TestScopedTarget();
        Assert.assertEquals(0, target.scopedLock().mLevel);

        synchronized (target.scopedLock()) {
            Assert.assertEquals(1, target.scopedLock().mLevel);
        }
        Assert.assertEquals(0, target.scopedLock().mLevel);

        synchronized (target.scopedLock()) {
            synchronized (target.scopedLock()) {
                Assert.assertEquals(2, target.scopedLock().mLevel);
            }
        }
        Assert.assertEquals(0, target.scopedLock().mLevel);
    }

    @Test
    public void testScopedExceptionHandling() {
        TestScopedTarget target = new TestScopedTarget();
        Assert.assertEquals(0, target.scopedLock().mLevel);

        boolean handled;

        // 1: an exception inside the block properly releases the lock.
        handled = false;
        try {
            synchronized (target.scopedLock()) {
                Assert.assertEquals(true, Thread.holdsLock(target.scopedLock()));
                Assert.assertEquals(1, target.scopedLock().mLevel);
                throw new RuntimeException();
            }
        } catch (RuntimeException e) {
            Assert.assertEquals(0, target.scopedLock().mLevel);
            handled = true;
        }
        Assert.assertEquals(0, target.scopedLock().mLevel);
        Assert.assertEquals(true, handled);
        // Just verify that the lock can still be taken
        Assert.assertEquals(false, Thread.holdsLock(target.scopedLock()));

        // 2: An exception inside the monitor enter function
        handled = false;
        target.throwOnEnter(true);
        try {
            synchronized (target.scopedLock()) {
                // The exception was thrown inside monitorEnter(), so the code should
                // never reach this point.
                Assert.assertEquals(0, 1);
            }
        } catch (RuntimeException e) {
            Assert.assertEquals(0, target.scopedLock().mLevel);
            handled = true;
        }
        Assert.assertEquals(0, target.scopedLock().mLevel);
        Assert.assertEquals(true, handled);
        // Just verify that the lock can still be taken
        Assert.assertEquals(false, Thread.holdsLock(target.scopedLock()));

        // 3: An exception inside the monitor exit function
        handled = false;
        target.throwOnEnter(true);
        try {
            synchronized (target.scopedLock()) {
                Assert.assertEquals(true, Thread.holdsLock(target.scopedLock()));
                Assert.assertEquals(1, target.scopedLock().mLevel);
            }
        } catch (RuntimeException e) {
            Assert.assertEquals(0, target.scopedLock().mLevel);
            handled = true;
        }
        Assert.assertEquals(0, target.scopedLock().mLevel);
        Assert.assertEquals(true, handled);
        // Just verify that the lock can still be taken
        Assert.assertEquals(false, Thread.holdsLock(target.scopedLock()));
    }

    // Provide an in-class type conversion for the scoped target.
    private Object untypedLock(TestScopedTarget target) {
        return target.scopedLock();
    }

    @Test
    public void testScopedLockTyping() {
        TestScopedTarget target = new TestScopedTarget();
        Assert.assertEquals(target.scopedLock().mLevel, 0);

        // Scoped lock injection works on the static type of an object.  In general, it is
        // a very bad idea to do type conversion on scoped locks, but the general rule is
        // that conversions within a single method are recognized by the lock injection
        // tool and injection occurs.  Conversions outside a single method are not
        // recognized and injection does not occur.

        // 1. Conversion occurs outside the class.  The visible type of the lock is Object
        // in this block, so no injection takes place on 'untypedLock', even though the
        // dynamic type is TestScopedLock.
        synchronized (target.untypedLock()) {
            Assert.assertEquals(0, target.scopedLock().mLevel);
            Assert.assertEquals(true, target.untypedLock() instanceof TestScopedLock);
            Assert.assertEquals(true, Thread.holdsLock(target.scopedLock()));
        }

        // 2. Conversion occurs inside the class but in another method.  The visible type
        // of the lock is Object in this block, so no injection takes place on
        // 'untypedLock', even though the dynamic type is TestScopedLock.
        synchronized (untypedLock(target)) {
            Assert.assertEquals(0, target.scopedLock().mLevel);
            Assert.assertEquals(true, target.untypedLock() instanceof TestScopedLock);
            Assert.assertEquals(true, Thread.holdsLock(target.scopedLock()));
        }

        // 3. Conversion occurs inside the method.  The compiler can determine the type of
        // the lock within a single function, so injection does take place here.
        Object untypedLock = target.scopedLock();
        synchronized (untypedLock) {
            Assert.assertEquals(1, target.scopedLock().mLevel);
            Assert.assertEquals(true, untypedLock instanceof TestScopedLock);
            Assert.assertEquals(true, Thread.holdsLock(target.scopedLock()));
        }
    }
}
