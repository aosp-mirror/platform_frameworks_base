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

import static android.test.MoreAsserts.assertNotEqual;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.animation.LinearInterpolator;

import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class ValueAnimatorTests {
    private static final long WAIT_TIME_OUT = 5000;
    private ValueAnimator a1;
    private ValueAnimator a2;

    // Tolerance of error in calculations related to duration, frame time, etc. due to frame delay.
    private final static long TOLERANCE = 100; // ms
    private final static long POLL_INTERVAL = 100; // ms

    private final static float A1_START_VALUE = 0f;
    private final static float A1_END_VALUE = 1f;
    private final static int A2_START_VALUE = 100;
    private final static int A2_END_VALUE = 200;

    private final static long DEFAULT_FRAME_INTERVAL = 5; //ms
    private final static long COMMIT_DELAY = 3; //ms

    private ActivityTestRule<BasicAnimatorActivity> mActivityRule =
            new ActivityTestRule<>(BasicAnimatorActivity.class);

    @Before
    public void setUp() throws Exception {
        a1 = ValueAnimator.ofFloat(A1_START_VALUE, A1_END_VALUE).setDuration(300);
        a2 = ValueAnimator.ofInt(A2_START_VALUE, A2_END_VALUE).setDuration(500);
    }

    @After
    public void tearDown() throws Exception {
        a1 = null;
        a2 = null;
    }

    @Test
    public void testStartDelay() throws Throwable {
        final ValueAnimator a = ValueAnimator.ofFloat(5f, 20f);
        assertEquals(a.getStartDelay(), 0);
        final long delay = 200;
        a.setStartDelay(delay);
        assertEquals(a.getStartDelay(), delay);

        final MyUpdateListener listener = new MyUpdateListener();
        a.addUpdateListener(listener);
        final long[] startTime = new long[1];

        mActivityRule.runOnUiThread(new Runnable() {
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
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(listener.wasRunning);
                assertTrue(listener.firstRunningFrameTime - startTime[0] >= delay);
            }
        });

        Thread.sleep(a.getTotalDuration());
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(a.isStarted());
            }
        });
    }

    @Test
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

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                a1.start();
                a2.start();
            }
        });

        long wait = 0;
        Thread.sleep(POLL_INTERVAL);
        wait += POLL_INTERVAL;

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(l1.cancelCalled);
                a1.cancel();
                assertTrue(l1.cancelCalled);
                assertTrue(l1.endCalled);
            }
        });

        while (wait < a2.getStartDelay()) {
            mActivityRule.runOnUiThread(new Runnable() {
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

        mActivityRule.runOnUiThread(new Runnable() {
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

    @Test
    public void testIsStarted() throws Throwable {
        assertFalse(a1.isStarted());
        assertFalse(a2.isStarted());
        assertFalse(a1.isRunning());
        assertFalse(a2.isRunning());
        final long startDelay = 150;
        a1.setStartDelay(startDelay);
        final long[] startTime = new long[1];

        mActivityRule.runOnUiThread(new Runnable() {
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
            mActivityRule.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (SystemClock.uptimeMillis() - startTime[0] < startDelay) {
                        assertFalse(a1.isRunning());
                    }
                }
            });
        }

        Thread.sleep(startDelay);
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(a1.isRunning());
                assertTrue(a2.isRunning());
            }
        });

        long delay = Math.max(a1.getTotalDuration(), a2.getTotalDuration()) * 2;
        Thread.sleep(delay);
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(a1.isStarted());
                assertFalse(a1.isRunning());
                assertFalse(a2.isStarted());
                assertFalse(a2.isRunning());
            }
        });
    }

    @Test
    public void testPause() throws Throwable {
        mActivityRule.runOnUiThread(new Runnable() {
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
        mActivityRule.runOnUiThread(new Runnable() {
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
        mActivityRule.runOnUiThread(new Runnable() {
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
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(a1.isRunning());
                assertTrue(a1.isStarted());
                assertFalse(a1.isPaused());
            }
        });

        Thread.sleep(a1.getTotalDuration());
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // a1 should finish by now.
                assertFalse(a1.isRunning());
                assertFalse(a1.isStarted());
                assertFalse(a1.isPaused());
            }
        });

    }

    @Test
    public void testPauseListener() throws Throwable {
        MyPauseListener l1 = new MyPauseListener();
        MyPauseListener l2 = new MyPauseListener();
        a1.addPauseListener(l1);
        a2.addPauseListener(l2);

        assertFalse(l1.pauseCalled);
        assertFalse(l1.resumeCalled);
        assertFalse(l2.pauseCalled);
        assertFalse(l2.resumeCalled);

        mActivityRule.runOnUiThread(new Runnable() {
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
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                a1.resume();
            }
        });

        Thread.sleep(a1.getTotalDuration());

        assertTrue(l1.pauseCalled);
        assertTrue(l1.resumeCalled);
        assertFalse(l2.pauseCalled);
        assertFalse(l2.resumeCalled);
    }

    @Test
    public void testResume() throws Throwable {
        final MyUpdateListener l1 = new MyUpdateListener();
        final long totalDuration = a1.getTotalDuration();
        a1.addUpdateListener(l1);
        // Set a longer duration on a1 for this test
        a1.setDuration(1000);
        assertTrue(l1.firstRunningFrameTime < 0);
        assertTrue(l1.lastUpdateTime < 0);

        final long[] lastUpdate = new long[1];

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                a1.start();
            }
        });

        Thread.sleep(totalDuration / 2);

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(l1.firstRunningFrameTime > 0);
                assertTrue(l1.lastUpdateTime > l1.firstRunningFrameTime);
                lastUpdate[0] = l1.lastUpdateTime;
                a1.pause();
            }
        });

        Thread.sleep(totalDuration);

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // There should be no update after pause()
                assertEquals(lastUpdate[0], l1.lastUpdateTime);
                a1.resume();
            }
        });

        do {
            Thread.sleep(POLL_INTERVAL);
            mActivityRule.runOnUiThread(new Runnable() {
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

    @Test
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

        mActivityRule.runOnUiThread(new Runnable() {
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
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                a1.end();
                a2.cancel();
            }
        });
        Thread.sleep(POLL_INTERVAL);
        mActivityRule.runOnUiThread(new Runnable() {
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

    @Test
    public void testEndValue() throws Throwable {
        final MyListener l1 = new MyListener();
        a1.addListener(l1);

        final MyListener l2 = new MyListener();
        a2.addListener(l2);

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                a1.start();
                a2.start();
            }
        });

        Thread.sleep(POLL_INTERVAL);
        mActivityRule.runOnUiThread(new Runnable() {
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

        mActivityRule.runOnUiThread(new Runnable() {
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

    @Test
    public void testUpdateListener() throws InterruptedException {

        final MyFrameCallbackProvider provider = new MyFrameCallbackProvider();
        long sleep = 0;
        while (provider.mHandler == null) {
            Thread.sleep(POLL_INTERVAL);
            sleep += POLL_INTERVAL;
            if (sleep > WAIT_TIME_OUT) {
                break;
            }
        }
        // Either the looper has started, or timed out
        assertNotNull(provider.mHandler);

        final MyListener listener = new MyListener();
        final MyUpdateListener l1 = new MyUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                long currentTime = SystemClock.uptimeMillis();
                long frameDelay = provider.getFrameDelay();
                if (lastUpdateTime > 0) {
                    // Error tolerance here is 3 frame.
                    assertTrue((currentTime - lastUpdateTime) < frameDelay * 4);
                } else {
                    // First frame:
                    assertTrue(listener.startCalled);
                    assertTrue(listener.startTime > 0);
                    assertTrue(currentTime - listener.startTime < frameDelay * 4);
                }
                super.onAnimationUpdate(animation);
            }
        };
        a1.addUpdateListener(l1);
        a1.addListener(listener);
        a1.setStartDelay(100);

        provider.mHandler.post(new Runnable() {
            @Override
            public void run() {
                AnimationHandler.getInstance().setProvider(provider);
                a1.start();
            }
        });
        Thread.sleep(POLL_INTERVAL);
        assertTrue(a1.isStarted());
        Thread.sleep(a1.getTotalDuration() + TOLERANCE);
        // Finished by now.
        if (provider.mAssertionError != null) {
            throw provider.mAssertionError;
        }
        assertFalse(a1.isStarted());
        assertTrue(listener.endTime > 0);

        // Check the time difference between last frame and end time.
        assertTrue(listener.endTime >= l1.lastUpdateTime);
        assertTrue(listener.endTime - l1.lastUpdateTime < 2 * provider.getFrameDelay());
    }


    @Test
    public void testConcurrentModification() throws Throwable {
        // Attempt to modify list of animations as the list is being iterated
        final ValueAnimator a0 = ValueAnimator.ofInt(100, 200).setDuration(500);
        final ValueAnimator a3 = ValueAnimator.ofFloat(0, 1).setDuration(500);
        final ValueAnimator a4 = ValueAnimator.ofInt(200, 300).setDuration(500);
        final MyListener listener = new MyListener() {
            @Override
            public void onAnimationEnd(Animator anim) {
                super.onAnimationEnd(anim);
                // AnimationHandler should be iterating the list at the moment, end/cancel all
                // the other animations. No ConcurrentModificationException should happen.
                a0.cancel();
                a1.end();
                a3.end();
                a4.cancel();
            }
        };
        a2.addListener(listener);

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                a0.start();
                a1.start();
                a2.start();
                a3.start();
                a4.start();
            }
        });
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(a0.isStarted());
                assertTrue(a1.isStarted());
                assertTrue(a2.isStarted());
                assertTrue(a3.isStarted());
                assertTrue(a4.isStarted());
            }
        });
        Thread.sleep(POLL_INTERVAL);
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // End the animator that should be in the middle of the list.
                a2.end();
            }
        });
        Thread.sleep(POLL_INTERVAL);
        assertTrue(listener.endCalled);
        assertFalse(a0.isStarted());
        assertFalse(a1.isStarted());
        assertFalse(a2.isStarted());
        assertFalse(a3.isStarted());
        assertFalse(a4.isStarted());
    }

    @Test
    public void testSeek() throws Throwable {
        final MyListener l1 = new MyListener();
        final MyListener l2 = new MyListener();
        final MyUpdateListener updateListener1 = new MyUpdateListener();
        final MyUpdateListener updateListener2 = new MyUpdateListener();
        final float a1StartFraction = 0.2f;
        final float a2StartFraction = 0.3f;

        // Extend duration so we have plenty of latitude to manipulate the animations when they
        // are running.
        a1.setDuration(1000);
        a2.setDuration(1000);
        a1.addListener(l1);
        a2.addListener(l2);
        a1.addUpdateListener(updateListener1);
        a2.addUpdateListener(updateListener2);
        TimeInterpolator interpolator = new LinearInterpolator();
        a1.setInterpolator(interpolator);
        a2.setInterpolator(interpolator);

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(a1.isStarted());
                assertFalse(a1.isRunning());
                assertFalse(a2.isStarted());
                assertFalse(a2.isRunning());

                // Test isRunning() and isStarted() before and after seek
                a1.setCurrentFraction(a1StartFraction);
                a2.setCurrentFraction(a2StartFraction);

                assertFalse(a1.isStarted());
                assertFalse(a1.isRunning());
                assertFalse(a2.isStarted());
                assertFalse(a2.isRunning());
            }
        });
        Thread.sleep(POLL_INTERVAL);

        // Start animation and seek during the animation.
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(a1.isStarted());
                assertFalse(a1.isRunning());
                assertFalse(a2.isStarted());
                assertFalse(a2.isRunning());
                assertEquals(a1StartFraction, a1.getAnimatedFraction());
                assertEquals(a2StartFraction, a2.getAnimatedFraction());

                a1.start();
                a2.start();
            }
        });

        Thread.sleep(POLL_INTERVAL);
        final float halfwayFraction = 0.5f;
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(l1.startCalled);
                assertTrue(l2.startCalled);
                assertFalse(l1.endCalled);
                assertFalse(l2.endCalled);

                // Check whether the animations start from the seeking fraction
                assertTrue(updateListener1.startFraction >= a1StartFraction);
                assertTrue(updateListener2.startFraction >= a2StartFraction);

                assertTrue(a1.isStarted());
                assertTrue(a1.isRunning());
                assertTrue(a2.isStarted());
                assertTrue(a2.isRunning());

                a1.setCurrentFraction(halfwayFraction);
                a2.setCurrentFraction(halfwayFraction);
            }
        });

        Thread.sleep(POLL_INTERVAL);

        // Check that seeking during running doesn't change animation's internal state
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(l1.startCalled);
                assertTrue(l2.startCalled);
                assertFalse(l1.endCalled);
                assertFalse(l2.endCalled);

                assertTrue(a1.isStarted());
                assertTrue(a1.isRunning());
                assertTrue(a2.isStarted());
                assertTrue(a2.isRunning());
            }
        });

        // Wait until the animators finish successfully.
        long wait = Math.max(a1.getTotalDuration(), a2.getTotalDuration());
        Thread.sleep(wait);

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Verify that the animators have finished.
                assertTrue(l1.endCalled);
                assertTrue(l2.endCalled);

                assertFalse(a1.isStarted());
                assertFalse(a2.isStarted());
                assertFalse(a1.isRunning());
                assertFalse(a2.isRunning());
            }
        });

        // Re-start animator a1 after it ends normally, and check that seek value from last run
        // does not affect the new run.
        updateListener1.reset();
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                a1.start();
            }
        });

        Thread.sleep(POLL_INTERVAL);
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(updateListener1.wasRunning);
                assertTrue(updateListener1.startFraction >= 0);
                assertTrue(updateListener1.startFraction < halfwayFraction);
                a1.end();
            }
        });

    }

    @Test
    public void testSeekWhileRunning() throws Throwable {
        // Seek one animator to the beginning and the other one to the end when they are running.
        final MyListener l1 = new MyListener();
        final MyListener l2 = new MyListener();
        a1.addListener(l1);
        a2.addListener(l2);
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(l1.startCalled);
                assertFalse(l2.startCalled);
                assertEquals(0f, a1.getAnimatedFraction());
                assertEquals(0f, a2.getAnimatedFraction());
                a1.start();
                a2.start();
            }
        });
        Thread.sleep(POLL_INTERVAL);
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(l1.endCalled);
                assertFalse(l2.endCalled);
                assertTrue(a1.isRunning());
                assertTrue(a2.isRunning());
                // During the run, seek one to the beginning, the other to the end
                a1.setCurrentFraction(0f);
                a2.setCurrentFraction(1f);
            }
        });
        Thread.sleep(POLL_INTERVAL);
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Check that a2 has finished due to the seeking, but a1 hasn't finished.
                assertFalse(l1.endCalled);
                assertTrue(l2.endCalled);
                assertEquals(1f, a2.getAnimatedFraction());
            }
        });

        Thread.sleep(a1.getTotalDuration());
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // By now a1 should finish also.
                assertTrue(l1.endCalled);
                assertEquals(1f, a1.getAnimatedFraction());
            }
        });
    }

    @Test
    public void testEndBeforeStart() throws Throwable {
        // This test calls two animators that are not yet started. One animator has completed a
        // previous run but hasn't started since then, the other one has never run. When end() is
        // called on these two animators, we expected their animation listeners to receive both
        // onAnimationStarted(Animator) and onAnimationEnded(Animator) callbacks, in that sequence.

        a1.setStartDelay(20);

        // First start a1's first run.
        final MyListener normalEndingListener = new MyListener();
        a1.addListener(normalEndingListener);
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(a1.isStarted());
                assertFalse(normalEndingListener.startCalled);
                assertFalse(normalEndingListener.endCalled);
                // Start normally
                a1.start();
            }
        });

        Thread.sleep(a1.getTotalDuration() + POLL_INTERVAL);

        // a1 should have finished by now.
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Call end() on both a1 and a2 without calling start()
                final MyListener l1 = new MyListener();
                a1.addListener(l1);
                final MyListener l2 = new MyListener();
                a2.addListener(l2);

                assertFalse(a1.isStarted());
                assertFalse(l1.startCalled);
                assertFalse(l1.endCalled);
                assertFalse(a2.isStarted());
                assertFalse(l2.startCalled);
                assertFalse(l1.endCalled);

                a1.end();
                a2.end();

                // Check that both animators' listeners have received the animation callbacks.
                assertTrue(l1.startCalled);
                assertTrue(l1.endCalled);
                assertFalse(a1.isStarted());
                assertTrue(l1.endTime >= l1.startTime);

                assertTrue(l2.startCalled);
                assertTrue(l2.endCalled);
                assertFalse(a2.isStarted());
                assertTrue(l2.endTime >= l1.startTime);
            }
        });
    }

    @Test
    public void testZeroDuration() throws Throwable {
        // Run two animators with zero duration, with one running forward and the other one
        // backward. Check that the animations start and finish with the correct end fractions.
        a1.setDuration(0);
        a2.setDuration(0);

        // Set a fraction on an animation with 0-duration
        final ValueAnimator a3 = ValueAnimator.ofInt(0, 100);
        a3.setDuration(0);
        a3.setCurrentFraction(1.0f);
        assertEquals(1.0f, a3.getAnimatedFraction());

        final MyListener l1 = new MyListener();
        final MyListener l2 = new MyListener();
        final MyListener l3 = new MyListener();
        a1.addListener(l1);
        a2.addListener(l2);
        a3.addListener(l3);
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(l1.startCalled);
                assertFalse(l2.startCalled);
                assertFalse(l3.startCalled);
                assertFalse(l1.endCalled);
                assertFalse(l2.endCalled);
                assertFalse(l3.endCalled);
                a1.start();
                a2.reverse();
                a3.start();
                // Check that the animators' values are immediately set to end value in the case of
                // 0-duration.
                assertEquals(A1_END_VALUE, a1.getAnimatedValue());
                assertEquals(A2_START_VALUE, a2.getAnimatedValue());
            }
        });
        Thread.sleep(POLL_INTERVAL);
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Check that the animators have started and finished with the right values.
                assertTrue(l1.startCalled);
                assertTrue(l2.startCalled);
                assertTrue(l3.startCalled);
                assertTrue(l1.endCalled);
                assertTrue(l2.endCalled);
                assertTrue(l3.endCalled);
                assertEquals(1.0f, a1.getAnimatedFraction());
                assertEquals(0f, a2.getAnimatedFraction());
                assertEquals(1f, a3.getAnimatedFraction());
                assertEquals(A1_END_VALUE, a1.getAnimatedValue());
                assertEquals(A2_START_VALUE, a2.getAnimatedValue());
                assertEquals(100, a3.getAnimatedValue());
            }
        });
    }

    @Test
    public void testZeroScale() throws Throwable {
        // Test whether animations would end properly when the scale is forced to be zero
        float scale = ValueAnimator.getDurationScale();
        ValueAnimator.setDurationScale(0f);

        // Run two animators, one of which has a start delay, after setting the duration scale to 0
        a1.setStartDelay(200);
        final MyListener l1 = new MyListener();
        final MyListener l2 = new MyListener();
        a1.addListener(l1);
        a2.addListener(l2);

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(l1.startCalled);
                assertFalse(l2.startCalled);
                assertFalse(l1.endCalled);
                assertFalse(l2.endCalled);

                a1.start();
                a2.start();

                // In the case of 0 duration scale applied to a non-0 duration, check that the
                // value is immediately set to the start value.
                assertEquals(A2_START_VALUE, a2.getAnimatedValue());
            }
        });
        Thread.sleep(POLL_INTERVAL);

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(l1.startCalled);
                assertTrue(l2.startCalled);
                assertTrue(l1.endCalled);
                assertTrue(l2.endCalled);
                assertEquals(A1_END_VALUE, a1.getAnimatedValue());
                assertEquals(A2_END_VALUE, a2.getAnimatedValue());
            }
        });

        // Restore duration scale
        ValueAnimator.setDurationScale(scale);
    }

    @Test
    public void testReverse() throws Throwable {
        // Prolong animators duration so that we can do multiple checks during their run
        final ValueAnimator a3 = ValueAnimator.ofInt(0, 100);
        a1.setDuration(400);
        a2.setDuration(600);
        a3.setDuration(400);
        final MyListener l1 = new MyListener();
        final MyListener l2 = new MyListener();
        final MyListener l3 = new MyListener();
        a1.addListener(l1);
        a2.addListener(l2);
        a3.addListener(l3);

        // Reverse three animators, seek one to the beginning and another to the end, and force
        // to end the third one during reversing.
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertFalse(l1.startCalled);
                assertFalse(l2.startCalled);
                assertFalse(l3.startCalled);
                assertFalse(l1.endCalled);
                assertFalse(l2.endCalled);
                assertFalse(l3.endCalled);
                a1.reverse();
                a2.reverse();
                a3.reverse();
            }
        });
        Thread.sleep(POLL_INTERVAL);
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(l1.startCalled);
                assertTrue(l2.startCalled);
                assertTrue(l3.startCalled);

                a1.setCurrentFraction(0f);
                a2.setCurrentFraction(1f);
                a3.end();

                // Check that the fraction has been set, and the getter returns the correct values.
                assertEquals(1f, a1.getAnimatedFraction());
                assertEquals(0f, a2.getAnimatedFraction());
            }
        });
        Thread.sleep(POLL_INTERVAL);

        // By now, a2 should have finished due to the seeking. It wouldn't have finished otherwise.
        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Check that both animations have started, and a2 has finished.
                assertFalse(l1.endCalled);
                assertTrue(l2.endCalled);
                assertTrue(l3.endCalled);
            }
        });
        Thread.sleep(a1.getTotalDuration());

        mActivityRule.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Verify that a1 has finished as well.
                assertTrue(l1.endCalled);
                assertEquals(0f, a1.getAnimatedFraction());
                assertEquals(0f, a2.getAnimatedFraction());
                assertEquals(0f, a3.getAnimatedFraction());
            }
        });
    }

    class MyUpdateListener implements ValueAnimator.AnimatorUpdateListener {
        boolean wasRunning = false;
        long firstRunningFrameTime = -1;
        long lastUpdateTime = -1;
        float startFraction = 0;

        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            lastUpdateTime = SystemClock.uptimeMillis();
            if (animation.isRunning() && !wasRunning) {
                // Delay has passed
                firstRunningFrameTime = lastUpdateTime;
                startFraction = animation.getAnimatedFraction();
                wasRunning = animation.isRunning();
            }
        }

        void reset() {
            wasRunning = false;
            firstRunningFrameTime = -1;
            lastUpdateTime = -1;
            startFraction = 0;
        }
    }

    class MyListener implements Animator.AnimatorListener {
        boolean startCalled = false;
        boolean cancelCalled = false;
        boolean endCalled = false;
        long startTime = -1;
        long endTime = -1;

        @Override
        public void onAnimationStart(Animator animation) {
            startCalled = true;
            startTime = SystemClock.uptimeMillis();
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            endCalled = true;
            endTime = SystemClock.uptimeMillis();
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

    class MyFrameCallbackProvider implements AnimationHandler.AnimationFrameCallbackProvider {

        Handler mHandler = null;
        private final static int MSG_FRAME = 0;
        private long mFrameDelay = DEFAULT_FRAME_INTERVAL;
        private ArrayList<Choreographer.FrameCallback> mFrameCallbacks = new ArrayList<>();
        volatile AssertionError mAssertionError = null;

        final LooperThread mThread = new LooperThread();

        public MyFrameCallbackProvider() {
            mThread.start();
        }

        @Override
        public void postFrameCallback(Choreographer.FrameCallback callback) {
            mHandler.sendEmptyMessageDelayed(MSG_FRAME, mFrameDelay);
            if (!mFrameCallbacks.contains(callback)) {
                mFrameCallbacks.add(callback);
            }
        }

        @Override
        public void postCommitCallback(Runnable runnable) {
            // Run the runnable after a commit delay
            mHandler.postDelayed(runnable, COMMIT_DELAY);
        }

        @Override
        public long getFrameTime() {
            return SystemClock.uptimeMillis();
        }

        @Override
        public long getFrameDelay() {
            return mFrameDelay;
        }

        @Override
        public void setFrameDelay(long delay) {
            mFrameDelay = delay;
            if (mFrameCallbacks.size() != 0) {
                mHandler.removeMessages(MSG_FRAME);
                mHandler.sendEmptyMessageDelayed(MSG_FRAME, mFrameDelay);
            }
        }

        class LooperThread extends Thread {
            public void run() {
                Looper.prepare();
                mHandler = new Handler() {
                    public void handleMessage(Message msg) {
                        try {
                            // Handle message here.
                            switch (msg.what) {
                                case MSG_FRAME:
                                    for (int i = 0; i < mFrameCallbacks.size(); i++) {
                                        mFrameCallbacks.get(i).doFrame(SystemClock.uptimeMillis());
                                    }
                                    break;
                                default:
                                    break;
                            }
                        } catch (AssertionError e) {
                            mAssertionError = e;
                            Looper.myLooper().quit();
                        }
                    }
                };
                Looper.loop();
            }
        }
    }
}
