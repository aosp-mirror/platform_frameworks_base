/*
* Copyright (C) 2015 The Android Open Source Project
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
package android.animation;

import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;

import static android.test.MoreAsserts.assertNotEqual;

public class ValueAnimatorTests extends ActivityInstrumentationTestCase2<BasicAnimatorActivity> {
    private ValueAnimator a1;
    private ValueAnimator a2;

    // Tolerance of error in calculations related to duration, frame time, etc. due to frame delay.
    private final static long TOLERANCE = 100; // ms
    private final static long POLL_INTERVAL = 100; // ms

    private final static float A1_START_VALUE = 0f;
    private final static float A1_END_VALUE = 1f;
    private final static int A2_START_VALUE = 100;
    private final static int A2_END_VALUE = 200;

    public ValueAnimatorTests() {
        super(BasicAnimatorActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        a1 = ValueAnimator.ofFloat(A1_START_VALUE, A1_END_VALUE).setDuration(300);
        a2 = ValueAnimator.ofInt(A2_START_VALUE, A2_END_VALUE).setDuration(500);
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        a1 = null;
        a2 = null;
    }

    @SmallTest
    public void testStartDelay() throws Throwable {
        final ValueAnimator a = ValueAnimator.ofFloat(5f, 20f);
        assertEquals(a.getStartDelay(), 0);
        final long delay = 200;
        a.setStartDelay(delay);
        assertEquals(a.getStartDelay(), delay);

        final MyUpdateListener listener = new MyUpdateListener();
        a.addUpdateListener(listener);
        final long[] startTime = new long[1];

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Test the time between isRunning() and isStarted()
                assertFalse(a.isStarted());
                assertFalse(a.isRunning());
                a.start();
                startTime[0] = SystemClock.uptimeMillis();
                assertTrue(a.isStarted());
                assertFalse(a.isRunning());
            }
        });

        Thread.sleep(a.getTotalDuration());
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(listener.wasRunning);
                assertTrue(listener.firstRunningFrameTime - startTime[0] >= delay);
            }
        });

        Thread.sleep(a.getTotalDuration());
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(a.isStarted());
            }
        });
    }

    @SmallTest
    public void testListenerCallbacks() throws Throwable {
        final MyListener l1 = new MyListener();
        final MyListener l2 = new MyListener();
        a1.addListener(l1);
        a2.addListener(l2);
        a2.setStartDelay(400);

        assertFalse(l1.startCalled);
        assertFalse(l1.cancelCalled);
        assertFalse(l1.endCalled);
        assertFalse(l2.startCalled);
        assertFalse(l2.cancelCalled);
        assertFalse(l2.endCalled);

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                a1.start();
                a2.start();
            }
        });

        long wait = 0;
        Thread.sleep(POLL_INTERVAL);
        wait += POLL_INTERVAL;

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(l1.cancelCalled);
                a1.cancel();
                assertTrue(l1.cancelCalled);
                assertTrue(l1.endCalled);
            }
        });

        while (wait < a2.getStartDelay()) {
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Make sure a2's start listener isn't called during start delay.
                    assertTrue(l1.startCalled);
                    assertFalse(l2.startCalled);
                }
            });
            Thread.sleep(POLL_INTERVAL);
            wait += POLL_INTERVAL;
        }

        long delay = Math.max(a1.getTotalDuration(), a2.getTotalDuration()) + TOLERANCE;
        Thread.sleep(delay);

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // a1 is canceled.
                assertTrue(l1.startCalled);
                assertTrue(l1.cancelCalled);
                assertTrue(l1.endCalled);

                // a2 is supposed to finish normally
                assertTrue(l2.startCalled);
                assertFalse(l2.cancelCalled);
                assertTrue(l2.endCalled);
            }
        });
    }

    @SmallTest
    public void testIsStarted() throws Throwable {
        assertFalse(a1.isStarted());
        assertFalse(a2.isStarted());
        assertFalse(a1.isRunning());
        assertFalse(a2.isRunning());
        final long startDelay = 150;
        a1.setStartDelay(startDelay);
        final long[] startTime = new long[1];

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                a1.start();
                a2.start();
                startTime[0] = SystemClock.uptimeMillis();
                assertTrue(a1.isStarted());
                assertTrue(a2.isStarted());
            }
        });
        long delayMs = 0;
        while (delayMs < startDelay) {
            Thread.sleep(POLL_INTERVAL);
            delayMs += POLL_INTERVAL;
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (SystemClock.uptimeMillis() - startTime[0] < startDelay) {
                        assertFalse(a1.isRunning());
                    }
                }
            });
        }

        Thread.sleep(startDelay);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(a1.isRunning());
                assertTrue(a2.isRunning());
            }
        });

        long delay = Math.max(a1.getTotalDuration(), a2.getTotalDuration()) * 2;
        Thread.sleep(delay);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(a1.isStarted());
                assertFalse(a1.isRunning());
                assertFalse(a2.isStarted());
                assertFalse(a2.isRunning());
            }
        });
    }

    @SmallTest
    public void testPause() throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(a1.isPaused());
                assertFalse(a2.isPaused());

                a1.start();
                a2.start();

                assertFalse(a1.isPaused());
                assertFalse(a2.isPaused());
                assertTrue(a1.isStarted());
                assertTrue(a2.isStarted());
            }
        });

        Thread.sleep(POLL_INTERVAL);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(a1.isRunning());
                assertTrue(a2.isRunning());
                a1.pause();
                assertTrue(a1.isPaused());
                assertFalse(a2.isPaused());
                assertTrue(a1.isRunning());
            }
        });

        Thread.sleep(a2.getTotalDuration());
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // By this time, a2 should have finished, and a1 is still paused
                assertFalse(a2.isStarted());
                assertFalse(a2.isRunning());
                assertTrue(a1.isStarted());
                assertTrue(a1.isRunning());
                assertTrue(a1.isPaused());

                a1.resume();
            }
        });

        Thread.sleep(POLL_INTERVAL);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(a1.isRunning());
                assertTrue(a1.isStarted());
                assertFalse(a1.isPaused());
            }
        });

        Thread.sleep(a1.getTotalDuration());
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // a1 should finish by now.
                assertFalse(a1.isRunning());
                assertFalse(a1.isStarted());
                assertFalse(a1.isPaused());
            }
        });

    }

    @SmallTest
    public void testPauseListener() throws Throwable {
        MyPauseListener l1 = new MyPauseListener();
        MyPauseListener l2 = new MyPauseListener();
        a1.addPauseListener(l1);
        a2.addPauseListener(l2);

        assertFalse(l1.pauseCalled);
        assertFalse(l1.resumeCalled);
        assertFalse(l2.pauseCalled);
        assertFalse(l2.resumeCalled);

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                a1.start();
                a2.start();
            }
        });

        Thread.sleep(a1.getTotalDuration() / 2);
        a1.pause();

        Thread.sleep(a2.getTotalDuration());

        // Only a1's pause listener should be called.
        assertTrue(l1.pauseCalled);
        assertFalse(l1.resumeCalled);
        a1.resume();

        Thread.sleep(a1.getTotalDuration());

        assertTrue(l1.pauseCalled);
        assertTrue(l1.resumeCalled);
        assertFalse(l2.pauseCalled);
        assertFalse(l2.resumeCalled);
    }

    @SmallTest
    public void testResume() throws Throwable {
        final MyUpdateListener l1 = new MyUpdateListener();
        final long totalDuration = a1.getTotalDuration();
        a1.addUpdateListener(l1);
        // Set a longer duration on a1 for this test
        a1.setDuration(1000);
        assertTrue(l1.firstRunningFrameTime < 0);
        assertTrue(l1.lastUpdateTime < 0);

        final long[] lastUpdate = new long[1];

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                a1.start();
            }
        });

        Thread.sleep(totalDuration / 2);

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(l1.firstRunningFrameTime > 0);
                assertTrue(l1.lastUpdateTime > l1.firstRunningFrameTime);
                lastUpdate[0] = l1.lastUpdateTime;
                a1.pause();
            }
        });

        Thread.sleep(totalDuration);

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // There should be no update after pause()
                assertEquals(lastUpdate[0], l1.lastUpdateTime);
                a1.resume();
            }
        });

        do {
            Thread.sleep(POLL_INTERVAL);
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    assertTrue(l1.lastUpdateTime > lastUpdate[0]);
                    lastUpdate[0] = l1.lastUpdateTime;
                }
            });
        } while (!a1.isStarted());

        // Time between pause and resume: totalDuration
        long entireSpan = totalDuration * 2;
        long frameDelta = l1.lastUpdateTime - l1.firstRunningFrameTime;
        assertTrue(Math.abs(entireSpan - frameDelta) < TOLERANCE);
    }

    @SmallTest
    public void testEnd() throws Throwable {
        final MyListener l1 = new MyListener();
        final MyListener l2 = new MyListener();
        a1.addListener(l1);
        a2.addListener(l2);
        a1.addListener(new MyListener() {
            @Override
            public void onAnimationEnd(Animator anim) {
                anim.cancel();
            }
        });
        a2.addListener(new MyListener() {
            @Override
            public void onAnimationCancel(Animator anim) {
                anim.end();
            }
        });

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(l1.cancelCalled);
                assertFalse(l1.endCalled);
                assertFalse(l2.cancelCalled);
                assertFalse(l2.endCalled);
                a1.start();
                a2.start();
            }
        });
        Thread.sleep(POLL_INTERVAL);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                a1.end();
                a2.cancel();
            }
        });
        Thread.sleep(POLL_INTERVAL);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Calling cancel from onAnimationEnd will be ignored.
                assertFalse(l1.cancelCalled);
                assertTrue(l1.endCalled);
                assertTrue(l2.cancelCalled);
                assertTrue(l2.endCalled);

                float value1 = (Float) a1.getAnimatedValue();
                int value2 = (Integer) a2.getAnimatedValue();
                assertEquals(A1_END_VALUE, value1);
                assertEquals(A2_END_VALUE, value2);
            }
        });

    }

    @SmallTest
    public void testEndValue() throws Throwable {
        final MyListener l1 = new MyListener();
        a1.addListener(l1);

        final MyListener l2 = new MyListener();
        a2.addListener(l2);

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                a1.start();
                a2.start();
            }
        });

        Thread.sleep(POLL_INTERVAL);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Animation has started but not finished, check animated values against end values
                assertFalse(l1.endCalled);
                assertFalse(l2.endCalled);
                assertNotEqual(A1_END_VALUE, a1.getAnimatedValue());
                assertNotEqual(A1_END_VALUE, a2.getAnimatedValue());

                // Force a2 to end.
                a2.end();
            }
        });

        Thread.sleep(a1.getTotalDuration());

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(l1.cancelCalled);
                assertTrue(l1.endCalled);
                assertFalse(l2.cancelCalled);
                assertTrue(l2.endCalled);

                // By now a1 should have finished normally and a2 has skipped to the end, check
                // their end values.
                assertEquals(A1_END_VALUE, ((Float) (a1.getAnimatedValue())).floatValue());
                assertEquals(A2_END_VALUE, ((Integer) (a2.getAnimatedValue())).intValue());
            }
        });
    }

    class MyUpdateListener implements ValueAnimator.AnimatorUpdateListener {
        boolean wasRunning = false;
        long firstRunningFrameTime = -1;
        long lastUpdateTime = -1;

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            lastUpdateTime = SystemClock.uptimeMillis();
            if (animation.isRunning() && !wasRunning) {
                // Delay has passed
                firstRunningFrameTime = lastUpdateTime;
                wasRunning = animation.isRunning();
            }
        }
    }

    class MyListener implements Animator.AnimatorListener {
        boolean startCalled = false;
        boolean cancelCalled = false;
        boolean endCalled = false;

        @Override
        public void onAnimationStart(Animator animation) {
            startCalled = true;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            endCalled = true;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            cancelCalled = true;
        }

        @Override
        public void onAnimationRepeat(Animator animation) {

        }
    }

    class MyPauseListener implements Animator.AnimatorPauseListener {
        boolean pauseCalled = false;
        boolean resumeCalled = false;

        @Override
        public void onAnimationPause(Animator animation) {
            pauseCalled = true;
        }

        @Override
        public void onAnimationResume(Animator animation) {
            resumeCalled = true;
        }
    }
}
