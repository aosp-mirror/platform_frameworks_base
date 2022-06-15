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
 * To run the unit tests:
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
 * # Make booster
 * javac -cp lib/asm-6.0_BETA.jar:lib/asm-commons-6.0_BETA.jar:lib/asm-tree-6.0_BETA.jar:lib/asm-analysis-6.0_BETA.jar:lib/guava-21.0.jar src&#47;*&#47;*.java -d out/
 * pushd out
 * jar cfe lockedregioncodeinjection.jar lockedregioncodeinjection.Main *&#47;*.class
 * popd
 *
 * # Make unit tests.
 * javac -cp lib/junit-4.12.jar test&#47;*&#47;*.java -d out/
 *
 * pushd out
 * jar cfe test_input.jar lockedregioncodeinjection.Test *&#47;*.class
 * popd
 *
 * # Run tool on unit tests.
 * java -ea -cp lib/asm-6.0_BETA.jar:lib/asm-commons-6.0_BETA.jar:lib/asm-tree-6.0_BETA.jar:lib/asm-analysis-6.0_BETA.jar:lib/guava-21.0.jar:out/lockedregioncodeinjection.jar \
 *     lockedregioncodeinjection.Main \
 *     -i out/test_input.jar -o out/test_output.jar \
 *     --targets 'Llockedregioncodeinjection/TestTarget;' \
 *     --pre     'lockedregioncodeinjection/TestTarget.boost' \
 *     --post    'lockedregioncodeinjection/TestTarget.unboost'
 *
 * # Run unit tests.
 * java -ea -cp lib/hamcrest-core-1.3.jar:lib/junit-4.12.jar:out/test_output.jar \
 *     org.junit.runner.JUnitCore lockedregioncodeinjection.TestMain
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
        Assert.assertEquals(TestTarget.unboostCount, 0);

        synchronized (t) {
            Assert.assertEquals(TestTarget.boostCount, 1);
            Assert.assertEquals(TestTarget.unboostCount, 0);
            TestTarget.invoke();
        }

        Assert.assertEquals(TestTarget.boostCount, 1);
        Assert.assertEquals(TestTarget.unboostCount, 1);
        Assert.assertEquals(TestTarget.invokeCount, 1);
    }

    @Test
    public void testSimpleSynchronizedMethod() {
        TestTarget.resetCount();
        TestTarget t = new TestTarget();

        Assert.assertEquals(TestTarget.boostCount, 0);
        Assert.assertEquals(TestTarget.unboostCount, 0);

        t.synchronizedCall();

        Assert.assertEquals(TestTarget.boostCount, 1);
        Assert.assertEquals(TestTarget.unboostCount, 1);
        Assert.assertEquals(TestTarget.invokeCount, 1);
    }

    @Test
    public void testSimpleSynchronizedMethod2() {
        TestTarget.resetCount();
        TestTarget t = new TestTarget();

        Assert.assertEquals(TestTarget.boostCount, 0);
        Assert.assertEquals(TestTarget.unboostCount, 0);

        t.synchronizedCallReturnInt();

        Assert.assertEquals(TestTarget.boostCount, 1);
        Assert.assertEquals(TestTarget.unboostCount, 1);
        Assert.assertEquals(TestTarget.invokeCount, 1);
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

}
