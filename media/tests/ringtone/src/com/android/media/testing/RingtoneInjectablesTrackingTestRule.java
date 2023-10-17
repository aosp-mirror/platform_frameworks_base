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

package com.android.media.testing;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.audiofx.HapticGenerator;
import android.util.ArrayMap;
import android.util.ArraySet;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.Mockito;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;

/**
 * This rule ensures that all expected media player creations from the factory do actually
 * occur. The reason for this level of control is that creating a media player is fairly
 * expensive and blocking, so we do want unit tests of this class to "declare" interactions
 * of all created media players.
 * <p>
 * This needs to be a TestRule so that the teardown assertions can be skipped if the test has
 * failed (and media player assertions may just be a distracting side effect). Otherwise, the
 * teardown failures hide the real test ones.
 */
public class RingtoneInjectablesTrackingTestRule implements TestRule {

    private final Ringtone.Injectables mRingtoneTestInjectables = new TestInjectables();

    // Queue of (local) media players, in order of expected creation. Enqueue using
    // expectNewMediaPlayer(), dequeued by the media player factory passed to Ringtone.
    // This queue is asserted to be empty at the end of the test.
    private final Queue<MediaPlayer> mMockMediaPlayerQueue = new ArrayDeque<>();

    // Similar to media players, but for haptic generator, which also needs releasing.
    private final Map<MediaPlayer, HapticGenerator> mMockHapticGeneratorMap = new ArrayMap<>();

    // Media players with haptic channels.
    private final ArraySet<MediaPlayer> mHapticChannels = new ArraySet<>();

    private boolean mHapticGeneratorAvailable = true;

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                base.evaluate();
                // Only assert if the test didn't fail (base.evaluate() would throw).
                assertWithMessage("Test setup an expectLocalMediaPlayer but it wasn't consumed")
                        .that(mMockMediaPlayerQueue).isEmpty();
                // Only assert if the test didn't fail (base.evaluate() would throw).
                assertWithMessage(
                        "Test setup an expectLocalHapticGenerator but it wasn't consumed")
                        .that(mMockHapticGeneratorMap).isEmpty();
            }
        };
    }

    /** The {@link Ringtone.Injectables} to be used for creating a testable {@link Ringtone}. */
    public Ringtone.Injectables getRingtoneTestInjectables() {
        return mRingtoneTestInjectables;
    }

    /**
     * Create a test {@link MediaPlayer} that will be provided to the {@link Ringtone} instance
     * created with {@link #getRingtoneTestInjectables()}.
     *
     * <p>If a media player is not created during the test execution after this method is called
     * then the test will fail. It will also fail if the ringtone attempts to create one without
     * this method being called first.
     */
    public TestMediaPlayer expectLocalMediaPlayer() {
        TestMediaPlayer mockMediaPlayer = Mockito.mock(TestMediaPlayer.class);
        // Delegate to simulated methods. This means they can be verified but also reflect
        // realistic transitions from the TestMediaPlayer.
        doCallRealMethod().when(mockMediaPlayer).start();
        doCallRealMethod().when(mockMediaPlayer).stop();
        doCallRealMethod().when(mockMediaPlayer).setLooping(anyBoolean());
        when(mockMediaPlayer.isLooping()).thenCallRealMethod();
        mMockMediaPlayerQueue.add(mockMediaPlayer);
        return mockMediaPlayer;
    }

    /**
     * Create a test {@link HapticGenerator} that will be provided to the {@link Ringtone} instance
     * created with {@link #getRingtoneTestInjectables()}.
     *
     * <p>If a haptic generator is not created during the test execution after this method is called
     * then the test will fail. It will also fail if the ringtone attempts to create one without
     * this method being called first.
     */
    public HapticGenerator expectHapticGenerator(MediaPlayer mediaPlayer) {
        HapticGenerator mockHapticGenerator = Mockito.mock(HapticGenerator.class);
        // A test should never want this.
        assertWithMessage("Can't expect a second haptic generator created "
                + "for one media player")
                .that(mMockHapticGeneratorMap.put(mediaPlayer, mockHapticGenerator))
                .isNull();
        return mockHapticGenerator;
    }

    /**
     * Configures the {@link MediaPlayer} to always return given flag when
     * {@link Ringtone.Injectables#hasHapticChannels(MediaPlayer)} is called.
     */
    public void setHasHapticChannels(MediaPlayer mp, boolean hasHapticChannels) {
        if (hasHapticChannels) {
            mHapticChannels.add(mp);
        } else {
            mHapticChannels.remove(mp);
        }
    }

    /** Test implementation of {@link Ringtone.Injectables} that uses the test rule setup. */
    private class TestInjectables extends Ringtone.Injectables {
        @Override
        public MediaPlayer newMediaPlayer() {
            assertWithMessage(
                    "Unexpected MediaPlayer creation. Bug or need expectNewMediaPlayer")
                    .that(mMockMediaPlayerQueue)
                    .isNotEmpty();
            return mMockMediaPlayerQueue.remove();
        }

        @Override
        public boolean isHapticGeneratorAvailable() {
            return mHapticGeneratorAvailable;
        }

        @Override
        public HapticGenerator createHapticGenerator(MediaPlayer mediaPlayer) {
            HapticGenerator mockHapticGenerator = mMockHapticGeneratorMap.remove(mediaPlayer);
            assertWithMessage("Unexpected HapticGenerator creation. "
                    + "Bug or need expectHapticGenerator")
                    .that(mockHapticGenerator)
                    .isNotNull();
            return mockHapticGenerator;
        }

        @Override
        public boolean isHapticPlaybackSupported() {
            return true;
        }

        @Override
        public boolean hasHapticChannels(MediaPlayer mp) {
            return mHapticChannels.contains(mp);
        }
    }

    /**
     * MediaPlayer relies on a native backend and so its necessary to intercept calls from
     * fake usage hitting them.
     * <p>
     * Mocks don't work directly on native calls, but if they're overridden then it does work.
     * Some basic state faking is also done to make the mocks more realistic.
     */
    public static class TestMediaPlayer extends MediaPlayer {
        private boolean mIsPlaying = false;
        private boolean mIsLooping = false;

        @Override
        public void start() {
            mIsPlaying = true;
        }

        @Override
        public void stop() {
            mIsPlaying = false;
        }

        @Override
        public void setLooping(boolean value) {
            mIsLooping = value;
        }

        @Override
        public boolean isLooping() {
            return mIsLooping;
        }

        @Override
        public boolean isPlaying() {
            return mIsPlaying;
        }

        /**
         * Updates {@link #isPlaying()} result to false, if it's set to true.
         *
         * @throws IllegalStateException is {@link #isPlaying()} is already false
         */
        public void simulatePlayingFinished() {
            if (!mIsPlaying) {
                throw new IllegalStateException(
                        "Attempted to pretend playing finished when not playing");
            }
            mIsPlaying = false;
        }
    }
}
