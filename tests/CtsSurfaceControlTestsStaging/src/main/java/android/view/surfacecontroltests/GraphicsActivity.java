/*
 * Copyright 2023 The Android Open Source Project
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

package android.view.surfacecontroltests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.sysprop.SurfaceFlingerProperties;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An Activity to help with frame rate testing.
 */
public class GraphicsActivity extends Activity {
    private static final String TAG = "GraphicsActivity";
    private static final long FRAME_RATE_SWITCH_GRACE_PERIOD_SECONDS = 2;
    private static final long STABLE_FRAME_RATE_WAIT_SECONDS = 1;
    private static final long POST_BUFFER_INTERVAL_MILLIS = 500;
    private static final int PRECONDITION_WAIT_MAX_ATTEMPTS = 5;
    private static final long PRECONDITION_WAIT_TIMEOUT_SECONDS = 20;
    private static final long PRECONDITION_VIOLATION_WAIT_TIMEOUT_SECONDS = 3;
    private static final float FRAME_RATE_TOLERANCE = 0.01f;

    static class FpsRange {
        // The max difference between refresh rates in order to be considered equal.
        private static final double FPS_THRESHOLD = 0.001;
        double mMin;
        double mMax;
        FpsRange(double min, double max) {
            mMin = min;
            mMax = max;
        }
        public boolean includes(double fps) {
            return fps >= mMin - FPS_THRESHOLD && mMax + FPS_THRESHOLD >= fps;
        }
    }

    // TODO(b/293651105): Unhardcode category fps range mapping
    private static final FpsRange FRAME_RATE_CATEGORY_HIGH = new FpsRange(90, 120);
    private static final FpsRange FRAME_RATE_CATEGORY_NORMAL = new FpsRange(60, 90);
    private static final FpsRange FRAME_RATE_CATEGORY_LOW = new FpsRange(30, 30);

    private DisplayManager mDisplayManager;
    private SurfaceView mSurfaceView;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private final Object mLock = new Object();
    private Surface mSurface = null;
    private float mDisplayModeRefreshRate;
    private float mDisplayRefreshRate;
    private ModeChangedEvents mModeChangedEvents = new ModeChangedEvents();

    private enum ActivityState { RUNNING, PAUSED, DESTROYED }

    private ActivityState mActivityState;

    SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            synchronized (mLock) {
                mSurface = holder.getSurface();
                mLock.notify();
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            synchronized (mLock) {
                mSurface = null;
                mLock.notify();
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    };

    DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                return;
            }
            synchronized (mLock) {
                Display display = mDisplayManager.getDisplay(displayId);
                Display.Mode mode = display.getMode();
                mModeChangedEvents.add(mode);
                float displayModeRefreshRate = mode.getRefreshRate();
                float displayRefreshRate = display.getRefreshRate();
                if (displayModeRefreshRate != mDisplayModeRefreshRate
                        || displayRefreshRate != mDisplayRefreshRate) {
                    Log.i(TAG,
                            String.format("Refresh rate changed: (mode) %.2f --> %.2f, "
                                            + "(display) %.2f --> %.2f",
                                    mDisplayModeRefreshRate, displayModeRefreshRate,
                                    mDisplayRefreshRate, displayRefreshRate));
                    mDisplayModeRefreshRate = displayModeRefreshRate;
                    mDisplayRefreshRate = displayRefreshRate;
                    mLock.notify();
                }
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {}
    };

    // Wrapper around ArrayList for which the only allowed mutable operation is add().
    // We use this to store all mode change events during a test. When we need to iterate over
    // all mode changes during a certain operation, we use the number of events in the beginning
    // and in the end. It's important to never clear or modify the elements in this list hence the
    // wrapper.
    private static class ModeChangedEvents {
        private List<Display.Mode> mEvents = new ArrayList<>();

        public void add(Display.Mode mode) {
            mEvents.add(mode);
        }

        public Display.Mode get(int i) {
            return mEvents.get(i);
        }

        public int size() {
            return mEvents.size();
        }
    }

    private static class PreconditionViolatedException extends RuntimeException {
        PreconditionViolatedException() {}
    }

    private static class FrameRateTimeoutException extends RuntimeException {
        FrameRateTimeoutException(float expectedFrameRate, float deviceFrameRate) {
            this.expectedFrameRate = expectedFrameRate;
            this.deviceFrameRate = deviceFrameRate;
        }

        FrameRateTimeoutException(List<Float> expectedFrameRates, float deviceFrameRate) {
            this.expectedFrameRates = expectedFrameRates;
            this.deviceFrameRate = deviceFrameRate;
        }

        public float expectedFrameRate;
        public float deviceFrameRate;
        public List<Float> expectedFrameRates;
    }

    public enum Api {
        // Much of the code is copied from the SetFrameRate cts test. Add APIs as support grows.
        SURFACE_CONTROL("SurfaceControl");

        private final String mName;
        Api(String name) {
            mName = name;
        }

        public String toString() {
            return mName;
        }
    }

    private static class TestSurface {
        private String mName;
        private SurfaceControl mSurfaceControl;
        private Surface mSurface;
        private int mColor;
        private boolean mLastBufferPostTimeValid;
        private long mLastBufferPostTime;

        TestSurface(SurfaceControl parentSurfaceControl, Surface parentSurface, String name,
                Rect destFrame, boolean visible, int color) {
            mName = name;
            mColor = color;

            assertNotNull("No parent surface", parentSurfaceControl);
            mSurfaceControl = new SurfaceControl.Builder()
                                      .setParent(parentSurfaceControl)
                                      .setName(mName)
                                      .setBufferSize(destFrame.right - destFrame.left,
                                              destFrame.bottom - destFrame.top)
                                      .build();
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setGeometry(mSurfaceControl, null, destFrame, Surface.ROTATION_0)
                        .apply();
            }
            mSurface = new Surface(mSurfaceControl);

            setVisibility(visible);
            postBuffer();
        }

        Surface getSurface() {
            return mSurface;
        }

        SurfaceControl getSurfaceControl() {
            return mSurfaceControl;
        }

        public int setFrameRate(float frameRate) {
            return setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        }

        public int setFrameRate(
                float frameRate, @Surface.FrameRateCompatibility int compatibility) {
            Log.i(TAG,
                    String.format("Setting frame rate for %s: frameRate=%.2f", mName, frameRate));

            int rc = 0;
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setFrameRate(mSurfaceControl, frameRate, compatibility);
                transaction.apply();
            }
            return rc;
        }

        public int setFrameRateCategory(int category) {
            Log.i(TAG,
                    String.format(
                            "Setting frame rate category for %s: category=%d", mName, category));

            int rc = 0;
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setFrameRateCategory(mSurfaceControl, category, false);
                transaction.apply();
            }
            return rc;
        }

        public int setFrameRateSelectionStrategy(int strategy) {
            Log.i(TAG,
                    String.format("Setting frame rate selection strategy for %s: strategy=%d",
                            mName, strategy));

            int rc = 0;
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setFrameRateSelectionStrategy(mSurfaceControl, strategy);
                transaction.apply();
            }
            return rc;
        }

        public void setVisibility(boolean visible) {
            Log.i(TAG,
                    String.format("Setting visibility for %s: %s", mName,
                            visible ? "visible" : "hidden"));
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                transaction.setVisibility(mSurfaceControl, visible).apply();
            }
        }

        public void postBuffer() {
            mLastBufferPostTimeValid = true;
            mLastBufferPostTime = System.nanoTime();
            Canvas canvas = mSurface.lockHardwareCanvas();
            canvas.drawColor(mColor);
            mSurface.unlockCanvasAndPost(canvas);
        }

        public long getLastBufferPostTime() {
            assertTrue("No buffer posted yet", mLastBufferPostTimeValid);
            return mLastBufferPostTime;
        }

        public void release() {
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            if (mSurfaceControl != null) {
                try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                    transaction.reparent(mSurfaceControl, null).apply();
                }
                mSurfaceControl.release();
                mSurfaceControl = null;
            }
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                release();
            } finally {
                super.finalize();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        synchronized (mLock) {
            mDisplayManager = getSystemService(DisplayManager.class);
            Display display = getDisplay();
            Display.Mode mode = display.getMode();
            mDisplayModeRefreshRate = mode.getRefreshRate();
            mDisplayRefreshRate = display.getRefreshRate();
            // Insert the initial mode so we have the full display mode history.
            mModeChangedEvents.add(mode);
            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
            mSurfaceView = new SurfaceView(this);
            mSurfaceView.setWillNotDraw(false);
            mSurfaceView.setZOrderOnTop(true);
            setContentView(mSurfaceView,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        synchronized (mLock) {
            mActivityState = ActivityState.DESTROYED;
            mLock.notify();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        synchronized (mLock) {
            mActivityState = ActivityState.PAUSED;
            mLock.notify();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        synchronized (mLock) {
            mActivityState = ActivityState.RUNNING;
            mLock.notify();
        }
    }

    // Returns the refresh rates with the same resolution as "mode".
    private ArrayList<Float> getRefreshRates(Display.Mode mode, Display display) {
        Display.Mode[] modes = display.getSupportedModes();
        ArrayList<Float> frameRates = new ArrayList<>();
        for (Display.Mode supportedMode : modes) {
            if (hasSameResolution(supportedMode, mode)) {
                frameRates.add(supportedMode.getRefreshRate());
            }
        }
        Collections.sort(frameRates);
        ArrayList<Float> uniqueFrameRates = new ArrayList<>();
        for (float frameRate : frameRates) {
            if (uniqueFrameRates.isEmpty()
                    || frameRate - uniqueFrameRates.get(uniqueFrameRates.size() - 1)
                            >= FRAME_RATE_TOLERANCE) {
                uniqueFrameRates.add(frameRate);
            }
        }
        Log.i(TAG,
                "**** Available display refresh rates: "
                        + uniqueFrameRates.stream()
                                  .map(Object::toString)
                                  .collect(Collectors.joining(", ")));
        return uniqueFrameRates;
    }

    private boolean hasSameResolution(Display.Mode mode1, Display.Mode mode2) {
        return mode1.getPhysicalHeight() == mode2.getPhysicalHeight()
                && mode1.getPhysicalWidth() == mode2.getPhysicalWidth();
    }

    private boolean isFrameRateMultiple(float higherFrameRate, float lowerFrameRate) {
        float multiple = higherFrameRate / lowerFrameRate;
        int roundedMultiple = Math.round(multiple);
        return roundedMultiple > 0
                && Math.abs(roundedMultiple * lowerFrameRate - higherFrameRate)
                <= FRAME_RATE_TOLERANCE;
    }

    private boolean frameRateEquals(float frameRate1, float frameRate2) {
        return Math.abs(frameRate1 - frameRate2) <= FRAME_RATE_TOLERANCE;
    }

    // Waits until our SurfaceHolder has a surface and the activity is resumed.
    private void waitForPreconditions() throws InterruptedException {
        assertNotSame(
                "Activity was unexpectedly destroyed", mActivityState, ActivityState.DESTROYED);
        if (mSurface == null || mActivityState != ActivityState.RUNNING) {
            Log.i(TAG,
                    String.format(
                            "Waiting for preconditions. Have surface? %b. Activity resumed? %b.",
                            mSurface != null, mActivityState == ActivityState.RUNNING));
        }
        long nowNanos = System.nanoTime();
        long endTimeNanos = nowNanos + PRECONDITION_WAIT_TIMEOUT_SECONDS * 1_000_000_000L;
        while (mSurface == null || mActivityState != ActivityState.RUNNING) {
            long timeRemainingMillis = (endTimeNanos - nowNanos) / 1_000_000;
            assertTrue(String.format("Timed out waiting for preconditions. Have surface? %b."
                                       + " Activity resumed? %b.",
                               mSurface != null, mActivityState == ActivityState.RUNNING),
                    timeRemainingMillis > 0);
            mLock.wait(timeRemainingMillis);
            assertNotSame(
                    "Activity was unexpectedly destroyed", mActivityState, ActivityState.DESTROYED);
            nowNanos = System.nanoTime();
        }
        // Make sure any previous mode changes are completed.
        waitForStableFrameRate();
    }

    // Returns true if we encounter a precondition violation, false otherwise.
    private boolean waitForPreconditionViolation() throws InterruptedException {
        assertNotSame(
                "Activity was unexpectedly destroyed", mActivityState, ActivityState.DESTROYED);
        long nowNanos = System.nanoTime();
        long endTimeNanos = nowNanos + PRECONDITION_VIOLATION_WAIT_TIMEOUT_SECONDS * 1_000_000_000L;
        while (mSurface != null && mActivityState == ActivityState.RUNNING) {
            long timeRemainingMillis = (endTimeNanos - nowNanos) / 1_000_000;
            if (timeRemainingMillis <= 0) {
                break;
            }
            mLock.wait(timeRemainingMillis);
            assertNotSame(
                    "Activity was unexpectedly destroyed", mActivityState, ActivityState.DESTROYED);
            nowNanos = System.nanoTime();
        }
        return mSurface == null || mActivityState != ActivityState.RUNNING;
    }

    private void verifyPreconditions() {
        if (mSurface == null || mActivityState != ActivityState.RUNNING) {
            throw new PreconditionViolatedException();
        }
    }

    // Returns true if we reached waitUntilNanos, false if some other event occurred.
    private boolean waitForEvents(long waitUntilNanos, TestSurface[] surfaces)
            throws InterruptedException {
        int numModeChangedEvents = mModeChangedEvents.size();
        long nowNanos = System.nanoTime();
        while (nowNanos < waitUntilNanos) {
            long surfacePostTime = Long.MAX_VALUE;
            for (TestSurface surface : surfaces) {
                surfacePostTime = Math.min(surfacePostTime,
                        surface.getLastBufferPostTime()
                                + (POST_BUFFER_INTERVAL_MILLIS * 1_000_000L));
            }
            long timeoutNs = Math.min(waitUntilNanos, surfacePostTime) - nowNanos;
            long timeoutMs = timeoutNs / 1_000_000L;
            int remainderNs = (int) (timeoutNs % 1_000_000L);
            // Don't call wait(0, 0) - it blocks indefinitely.
            if (timeoutMs > 0 || remainderNs > 0) {
                mLock.wait(timeoutMs, remainderNs);
            }
            nowNanos = System.nanoTime();
            verifyPreconditions();
            if (mModeChangedEvents.size() > numModeChangedEvents) {
                return false;
            }
            if (nowNanos >= surfacePostTime) {
                for (TestSurface surface : surfaces) {
                    surface.postBuffer();
                }
            }
        }
        return true;
    }

    private void waitForStableFrameRate(TestSurface... surfaces) throws InterruptedException {
        verifyFrameRates(List.of(), surfaces);
    }

    private void verifyExactAndStableFrameRate(
            float expectedFrameRate,
            TestSurface... surfaces) throws InterruptedException {
        verifyFrameRate(List.of(expectedFrameRate), false, surfaces);
    }

    private void verifyCompatibleAndStableFrameRate(
            float expectedFrameRate,
            TestSurface... surfaces) throws InterruptedException {
        verifyFrameRate(List.of(expectedFrameRate), true, surfaces);
    }

    /** Verify stable frame rate at one of the expectedFrameRates. */
    private void verifyFrameRates(List<Float> expectedFrameRates, TestSurface... surfaces)
            throws InterruptedException {
        verifyFrameRate(expectedFrameRates, true, surfaces);
    }

    // Set expectedFrameRates to empty to verify only stable frame rate.
    private void verifyFrameRate(List<Float> expectedFrameRates, boolean multiplesAllowed,
            TestSurface... surfaces) throws InterruptedException {
        Log.i(TAG, "Verifying compatible and stable frame rate");
        long nowNanos = System.nanoTime();
        long gracePeriodEndTimeNanos =
                nowNanos + FRAME_RATE_SWITCH_GRACE_PERIOD_SECONDS * 1_000_000_000L;
        while (true) {
            if (expectedFrameRates.size() == 1) {
                float expectedFrameRate = expectedFrameRates.get(0);
                // Wait until we switch to a compatible frame rate.
                Log.i(TAG,
                        String.format(
                                "Verifying expected frame rate: actual=%.2f, expected=%.2f",
                                multiplesAllowed ? mDisplayModeRefreshRate : mDisplayRefreshRate,
                                expectedFrameRate));
                if (multiplesAllowed) {
                    while (!isFrameRateMultiple(mDisplayModeRefreshRate, expectedFrameRate)
                            && !waitForEvents(gracePeriodEndTimeNanos, surfaces)) {
                        // Empty
                    }
                } else {
                    while (!frameRateEquals(mDisplayRefreshRate, expectedFrameRate)
                            && !waitForEvents(gracePeriodEndTimeNanos, surfaces)) {
                        // Empty
                    }
                }
                nowNanos = System.nanoTime();
                if (nowNanos >= gracePeriodEndTimeNanos) {
                    throw new FrameRateTimeoutException(expectedFrameRate,
                            multiplesAllowed ? mDisplayModeRefreshRate : mDisplayRefreshRate);
                }
            }

            // We've switched to a compatible frame rate. Now wait for a while to see if we stay at
            // that frame rate.
            long endTimeNanos = nowNanos + STABLE_FRAME_RATE_WAIT_SECONDS * 1_000_000_000L;
            while (endTimeNanos > nowNanos) {
                int numModeChangedEvents = mModeChangedEvents.size();
                if (waitForEvents(endTimeNanos, surfaces)) {
                    // Verify any expected frame rate since there are multiple that will suffice.
                    // Mainly to account for running tests on real devices, where other non-test
                    // layers may affect the outcome.
                    if (expectedFrameRates.size() > 1) {
                        for (float expectedFrameRate : expectedFrameRates) {
                            if (isFrameRateMultiple(mDisplayModeRefreshRate, expectedFrameRate)) {
                                return;
                            }
                        }
                        // The frame rate is stable but it is not one of the expected frame rates.
                        throw new FrameRateTimeoutException(
                                expectedFrameRates, mDisplayModeRefreshRate);
                    } else {
                        Log.i(TAG,
                                String.format("Stable frame rate %.2f verified",
                                        multiplesAllowed ? mDisplayModeRefreshRate
                                                         : mDisplayRefreshRate));
                        return;
                    }
                }
                nowNanos = System.nanoTime();
                if (mModeChangedEvents.size() > numModeChangedEvents) {
                    break;
                }
            }
        }
    }

    private void verifyModeSwitchesDontChangeResolution(int fromId, int toId) {
        assertTrue(fromId <= toId);
        for (int eventId = fromId; eventId < toId; eventId++) {
            Display.Mode fromMode = mModeChangedEvents.get(eventId - 1);
            Display.Mode toMode = mModeChangedEvents.get(eventId);
            assertTrue("Resolution change was not expected, but there was such from " + fromMode
                            + " to " + toMode + ".",
                    hasSameResolution(fromMode, toMode));
        }
    }

    // Unfortunately, we can't just use Consumer<Api> for this, because we need to declare that it
    // throws InterruptedException.
    private interface TestInterface {
        void run() throws InterruptedException;
    }

    private interface OneSurfaceTestInterface {
        void run(TestSurface surface) throws InterruptedException;
    }

    // Runs the given test for each api, waiting for the preconditions to be satisfied before
    // running the test. Includes retry logic when the test fails because the preconditions are
    // violated. E.g. if we lose the SurfaceHolder's surface, or the activity is paused/resumed,
    // we'll retry the test. The activity being intermittently paused/resumed has been observed to
    // cause test failures in practice.
    private void runTestsWithPreconditions(TestInterface test, String testName)
            throws InterruptedException {
        synchronized (mLock) {
            for (Api api : Api.values()) {
                Log.i(TAG, String.format("Testing %s %s", api, testName));
                int attempts = 0;
                boolean testPassed = false;
                try {
                    while (!testPassed) {
                        waitForPreconditions();
                        try {
                            test.run();
                            testPassed = true;
                        } catch (PreconditionViolatedException exc) {
                            // The logic below will retry if we're below max attempts.
                        } catch (FrameRateTimeoutException exc) {
                            StringWriter stringWriter = new StringWriter();
                            PrintWriter printWriter = new PrintWriter(stringWriter);
                            exc.printStackTrace(printWriter);
                            String stackTrace = stringWriter.toString();

                            // Sometimes we get a test timeout failure before we get the
                            // notification that the activity was paused, and it was the pause that
                            // caused the timeout failure. Wait for a bit to see if we get notified
                            // of a precondition violation, and if so, retry the test. Otherwise
                            // fail.
                            if (exc.expectedFrameRates.isEmpty()) {
                                assertTrue(
                                        String.format(
                                                "Timed out waiting for a stable and compatible"
                                                        + " frame rate."
                                                        + " expected=%.2f received=%.2f."
                                                        + " Stack trace: " + stackTrace,
                                                exc.expectedFrameRate, exc.deviceFrameRate),
                                        waitForPreconditionViolation());
                            } else {
                                assertTrue(
                                        String.format(
                                                "Timed out waiting for a stable and compatible"
                                                        + " frame rate."
                                                        + " expected={%.2f} received=%.2f."
                                                        + " Stack trace: " + stackTrace,
                                                exc.expectedFrameRates.stream()
                                                        .map(Object::toString)
                                                        .collect(Collectors.joining(", ")),
                                                exc.deviceFrameRate),
                                        waitForPreconditionViolation());
                            }
                        }

                        if (!testPassed) {
                            Log.i(TAG,
                                    String.format("Preconditions violated while running the test."
                                                    + " Have surface? %b. Activity resumed? %b.",
                                            mSurface != null,
                                            mActivityState == ActivityState.RUNNING));
                            attempts++;
                            assertTrue(String.format(
                                               "Exceeded %d precondition wait attempts. Giving up.",
                                               PRECONDITION_WAIT_MAX_ATTEMPTS),
                                    attempts < PRECONDITION_WAIT_MAX_ATTEMPTS);
                        }
                    }
                } finally {
                    String passFailMessage = String.format(
                            "%s %s %s", testPassed ? "Passed" : "Failed", api, testName);
                    if (testPassed) {
                        Log.i(TAG, passFailMessage);
                    } else {
                        Log.e(TAG, passFailMessage);
                    }
                }
            }
        }
    }

    private void runOneSurfaceTest(OneSurfaceTestInterface test) throws InterruptedException {
        TestSurface surface = null;
        try {
            surface = new TestSurface(mSurfaceView.getSurfaceControl(), mSurface, "testSurface",
                    mSurfaceView.getHolder().getSurfaceFrame(),
                    /*visible=*/true, Color.RED);

            test.run(surface);
        } finally {
            if (surface != null) {
                surface.release();
            }
        }
    }

    private void testSurfaceControlFrameRateCompatibilityInternal(
            @Surface.FrameRateCompatibility int compatibility) throws InterruptedException {
        runOneSurfaceTest((TestSurface surface) -> {
            Log.i(TAG,
                    "**** Running testSurfaceControlFrameRateCompatibility with compatibility "
                            + compatibility);

            List<Float> expectedFrameRates = getExpectedFrameRateForCompatibility(compatibility);
            Log.i(TAG,
                    "Expected frame rates: "
                            + expectedFrameRates.stream()
                                      .map(Object::toString)
                                      .collect(Collectors.joining(", ")));
            int initialNumEvents = mModeChangedEvents.size();
            surface.setFrameRate(30.f, compatibility);
            verifyFrameRates(expectedFrameRates, surface);
            verifyModeSwitchesDontChangeResolution(initialNumEvents, mModeChangedEvents.size());
        });
    }

    public void testSurfaceControlFrameRateCompatibility(
            @Surface.FrameRateCompatibility int compatibility) throws InterruptedException {
        runTestsWithPreconditions(
                () -> testSurfaceControlFrameRateCompatibilityInternal(compatibility),
                "frame rate compatibility=" + compatibility);
    }

    private void testSurfaceControlFrameRateCategoryInternal(
            @Surface.FrameRateCategory int category) throws InterruptedException {
        runOneSurfaceTest((TestSurface surface) -> {
            Log.i(TAG, "**** Running testSurfaceControlFrameRateCategory for category " + category);

            List<Float> expectedFrameRates = getExpectedFrameRateForCategory(category);
            int initialNumEvents = mModeChangedEvents.size();
            surface.setFrameRateCategory(category);
            verifyFrameRates(expectedFrameRates, surface);
            verifyModeSwitchesDontChangeResolution(initialNumEvents, mModeChangedEvents.size());
        });
    }

    public void testSurfaceControlFrameRateCategory(@Surface.FrameRateCategory int category)
            throws InterruptedException {
        runTestsWithPreconditions(()
                -> testSurfaceControlFrameRateCategoryInternal(category),
                "frame rate category=" + category);
    }

    private void testSurfaceControlFrameRateSelectionStrategyInternal(int parentStrategy)
            throws InterruptedException {
        Log.i(TAG,
                "**** Running testSurfaceControlFrameRateSelectionStrategy for strategy "
                        + parentStrategy);
        TestSurface parent = null;
        TestSurface child = null;
        try {
            parent = new TestSurface(mSurfaceView.getSurfaceControl(), mSurface,
                    "testSurfaceParent", mSurfaceView.getHolder().getSurfaceFrame(),
                    /*visible=*/true, Color.RED);
            child = new TestSurface(parent.getSurfaceControl(), parent.getSurface(),
                    "testSurfaceChild", mSurfaceView.getHolder().getSurfaceFrame(),
                    /*visible=*/true, Color.BLUE);

            // Test
            Display display = getDisplay();
            List<Float> frameRates = getRefreshRates(display.getMode(), display);
            assumeTrue("**** SKIPPED due to frame rate override disabled",
                    SurfaceFlingerProperties.enable_frame_rate_override().orElse(true));
            float childFrameRate = Collections.max(frameRates);
            float parentFrameRate = childFrameRate / 2;
            int initialNumEvents = mModeChangedEvents.size();
            parent.setFrameRateSelectionStrategy(parentStrategy);

            // For Self case, we want to test that child gets default behavior
            if (parentStrategy == SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_SELF) {
                parent.setFrameRateCategory(Surface.FRAME_RATE_CATEGORY_NO_PREFERENCE);
            } else {
                parent.setFrameRate(parentFrameRate);
                child.setFrameRate(childFrameRate);
            }

            // Verify
            float expectedFrameRate =
                    parentStrategy == SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN
                    ? parentFrameRate
                    : childFrameRate;
            verifyExactAndStableFrameRate(expectedFrameRate, parent, child);
            verifyModeSwitchesDontChangeResolution(initialNumEvents, mModeChangedEvents.size());
        } finally {
            if (parent != null) {
                parent.release();
            }
            if (child != null) {
                child.release();
            }
        }
    }

    public void testSurfaceControlFrameRateSelectionStrategy(int parentStrategy)
            throws InterruptedException {
        runTestsWithPreconditions(
                () -> testSurfaceControlFrameRateSelectionStrategyInternal(parentStrategy),
                "frame rate strategy=" + parentStrategy);
    }

    private List<Float> getExpectedFrameRateForCompatibility(int compatibility) {
        assumeTrue("**** testSurfaceControlFrameRateCompatibility SKIPPED for compatibility "
                        + compatibility,
                compatibility == Surface.FRAME_RATE_COMPATIBILITY_GTE);

        Display display = getDisplay();
        List<Float> expectedFrameRates = getRefreshRates(display.getMode(), display)
                                                 .stream()
                                                 .filter(rate -> rate >= 30.f)
                                                 .collect(Collectors.toList());

        assumeTrue("**** testSurfaceControlFrameRateCompatibility SKIPPED because no refresh rate "
                        + "is >= 30",
                !expectedFrameRates.isEmpty());
        return expectedFrameRates;
    }

    private List<Float> getExpectedFrameRateForCategory(int category) {
        Display display = getDisplay();
        List<Float> frameRates = getRefreshRates(display.getMode(), display);

        if (category == Surface.FRAME_RATE_CATEGORY_DEFAULT) {
            // Max due to default vote and no other frame rate specifications.
            return List.of(Collections.max(frameRates));
        } else if (category == Surface.FRAME_RATE_CATEGORY_NO_PREFERENCE) {
            return frameRates;
        }

        FpsRange categoryRange = convertCategory(category);
        List<Float> expectedFrameRates = frameRates.stream()
                                                 .filter(fps -> categoryRange.includes(fps))
                                                 .collect(Collectors.toList());
        assumeTrue("**** testSurfaceControlFrameRateCategory SKIPPED for category " + category,
                !expectedFrameRates.isEmpty());
        return expectedFrameRates;
    }

    private FpsRange convertCategory(int category) {
        switch (category) {
            case Surface.FRAME_RATE_CATEGORY_HIGH_HINT:
            case Surface.FRAME_RATE_CATEGORY_HIGH:
                return FRAME_RATE_CATEGORY_HIGH;
            case Surface.FRAME_RATE_CATEGORY_NORMAL:
                return FRAME_RATE_CATEGORY_NORMAL;
            case Surface.FRAME_RATE_CATEGORY_LOW:
                return FRAME_RATE_CATEGORY_LOW;
            case Surface.FRAME_RATE_CATEGORY_DEFAULT:
            case Surface.FRAME_RATE_CATEGORY_NO_PREFERENCE:
                fail("Should not get range for category=" + category);
        }
        return new FpsRange(0, 0);
    }
}
