/*
 * Copyright 2024 The Android Open Source Project
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

import static android.Manifest.permission.OBSERVE_PICTURE_PROFILES;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import static java.util.Arrays.stream;

import android.hardware.HardwareBuffer;
import android.media.quality.PictureProfileHandle;
import android.os.Process;
import android.view.SurfaceControl;
import android.view.SurfaceControlActivePicture;
import android.view.SurfaceControlActivePictureListener;
import android.view.SurfaceView;

import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class SurfaceControlPictureProfileTest {
    private static final String TAG = SurfaceControlPictureProfileTest.class.getSimpleName();

    private SurfaceControl[] mSurfaceControls;
    private SurfaceControl mSurfaceControl;

    @Rule
    public ActivityTestRule<SurfaceControlPictureProfileTestActivity> mActivityRule =
            new ActivityTestRule<>(SurfaceControlPictureProfileTestActivity.class);

    @Before
    public void setup() {
        SurfaceView[] surfaceViews = mActivityRule.getActivity().getSurfaceViews();
        mSurfaceControls = new SurfaceControl[surfaceViews.length];
        // Create a child surface control so we can set a buffer, priority and profile handle all
        // on one single surface control
        for (int i = 0; i < mSurfaceControls.length; ++i) {
            mSurfaceControls[i] = new SurfaceControl.Builder().setName("test").setHidden(false)
                    .setParent(surfaceViews[i].getSurfaceControl()).build();
        }
        mSurfaceControl = mSurfaceControls[0];
    }

    @Test
    public void whenPictureProfileApplied_noExecptionsThrown() {
        assumeTrue("Skipping test because feature flag is disabled",
                   com.android.graphics.libgui.flags.Flags.applyPictureProfiles());
        // TODO(b/337330263): Call MediaQualityManager.getMaxPictureProfiles instead
        assumeTrue("Skipping test because no picture profile support",
                   SurfaceControl.getMaxPictureProfiles() > 0);

        // TODO(b/337330263): Load the handle from MediaQualityManager instead
        PictureProfileHandle handle = new PictureProfileHandle(1);
        HardwareBuffer buffer = getSolidBuffer(100, 100);
        new SurfaceControl.Transaction()
                    .setBuffer(mSurfaceControl, buffer)
                    .setPictureProfileHandle(mSurfaceControl, handle)
                    .apply();
    }

    @Test
    public void whenStartsListening_callsListener() {
        assumeTrue("Skipping test because feature flag is disabled",
                   com.android.graphics.libgui.flags.Flags.applyPictureProfiles());
        // TODO(b/337330263): Call MediaQualityManager.getMaxPictureProfiles instead
        assumeTrue("Skipping test because no picture profile support",
                   SurfaceControl.getMaxPictureProfiles() > 0);

        BlockingQueue<SurfaceControlActivePicture[]> picturesQueue = new LinkedBlockingQueue<>();
        SurfaceControlActivePicture[] pictures;
        SurfaceControlActivePictureListener listener = new SurfaceControlActivePictureListener() {
                @Override
                public void onActivePicturesChanged(SurfaceControlActivePicture[] pictures) {
                    picturesQueue.add(pictures);
                }
            };
        // TODO(b/337330263): Call MediaQualityManager.addActivePictureListener instead
        adoptShellPermissionIdentity(OBSERVE_PICTURE_PROFILES);
        listener.startListening();
        {
            HardwareBuffer buffer = getSolidBuffer(100, 100);
            new SurfaceControl.Transaction().setBuffer(mSurfaceControl, buffer).apply();
        }

        pictures = pollMs(picturesQueue, 200);
        assertThat(pictures).isNotNull();
        assertThat(pictures).isEmpty();
    }

    @Test
    public void whenPictureProfileApplied_callsListenerWithUidAndProfileId() {
        assumeTrue("Skipping test because feature flag is disabled",
                   com.android.graphics.libgui.flags.Flags.applyPictureProfiles());
        // TODO(b/337330263): Call MediaQualityManager.getMaxPictureProfiles instead
        assumeTrue("Skipping test because no picture profile support",
                   SurfaceControl.getMaxPictureProfiles() > 0);

        BlockingQueue<SurfaceControlActivePicture[]> picturesQueue = new LinkedBlockingQueue<>();
        SurfaceControlActivePicture[] pictures;
        SurfaceControlActivePictureListener listener = new SurfaceControlActivePictureListener() {
                @Override
                public void onActivePicturesChanged(SurfaceControlActivePicture[] pictures) {
                    picturesQueue.add(pictures);
                }
            };
        // TODO(b/337330263): Call MediaQualityManager.addActivePictureListener instead
        adoptShellPermissionIdentity(OBSERVE_PICTURE_PROFILES);
        listener.startListening();
        {
            HardwareBuffer buffer = getSolidBuffer(100, 100);
            new SurfaceControl.Transaction().setBuffer(mSurfaceControl, buffer).apply();
        }

        pictures = pollMs(picturesQueue, 200);
        assertThat(pictures).isNotNull();
        assertThat(pictures).isEmpty();

        // TODO(b/337330263): Load the handle from MediaQualityManager instead
        PictureProfileHandle handle = new PictureProfileHandle(1);
        HardwareBuffer buffer = getSolidBuffer(100, 100);
        new SurfaceControl.Transaction()
                    .setBuffer(mSurfaceControl, buffer)
                    .setPictureProfileHandle(mSurfaceControl, handle)
                    .apply();

        pictures = pollMs(picturesQueue, 200);
        assertThat(pictures).isNotNull();
        assertThat(stream(pictures).map(picture -> picture.getPictureProfileHandle().getId()))
                .containsExactly(handle.getId());
        assertThat(stream(pictures).map(picture -> picture.getOwnerUid()))
                .containsExactly(Process.myUid());
    }

    @Test
    public void whenPriorityChanges_callsListenerOnlyForLowerPriorityLayers() {
        assumeTrue("Skipping test because feature flag is disabled",
                   com.android.graphics.libgui.flags.Flags.applyPictureProfiles());
        // TODO(b/337330263): Call MediaQualityManager.getMaxPictureProfiles instead
        int maxPictureProfiles = SurfaceControl.getMaxPictureProfiles();
        assumeTrue("Skipping test because no picture profile support", maxPictureProfiles > 0);

        BlockingQueue<SurfaceControlActivePicture[]> picturesQueue = new LinkedBlockingQueue<>();
        SurfaceControlActivePicture[] pictures;
        SurfaceControlActivePictureListener listener = new SurfaceControlActivePictureListener() {
                @Override
                public void onActivePicturesChanged(SurfaceControlActivePicture[] pictures) {
                    picturesQueue.add(pictures);
                }
            };
        // TODO(b/337330263): Call MediaQualityManager.addActivePictureListener instead
        adoptShellPermissionIdentity(OBSERVE_PICTURE_PROFILES);
        listener.startListening();
        {
            HardwareBuffer buffer = getSolidBuffer(100, 100);
            new SurfaceControl.Transaction().setBuffer(mSurfaceControl, buffer).apply();
        }

        pictures = pollMs(picturesQueue, 200);
        assertThat(pictures).isNotNull();
        assertThat(pictures).isEmpty();

        SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        // Use one more picture profile than allowed
        for (int i = 0; i <= maxPictureProfiles; ++i) {
            // Increase the number of surface views as necessary to support device configuration.
            assertThat(i).isLessThan(mSurfaceControls.length);

            // TODO(b/337330263): Load the handle from MediaQualityManager instead
            PictureProfileHandle handle = new PictureProfileHandle(i + 1);
            HardwareBuffer buffer = getSolidBuffer(100, 100);
            transaction
                    .setBuffer(mSurfaceControls[i], buffer)
                    .setPictureProfileHandle(mSurfaceControls[i], handle)
                    .setContentPriority(mSurfaceControls[i], 0);
        }
        // Make the first layer low priority (high value)
        transaction.setContentPriority(mSurfaceControls[0], 2);
        // Make the last layer higher priority (lower value)
        transaction.setContentPriority(mSurfaceControls[maxPictureProfiles], 1);
        transaction.apply();

        pictures = pollMs(picturesQueue, 200);
        assertThat(pictures).isNotNull();
        assertThat(stream(pictures).map(picture -> picture.getLayerId()))
                .containsNoDuplicates();
        // Expect all but the first layer to be listed as an active picture
        assertThat(stream(pictures).map(picture -> picture.getPictureProfileHandle().getId()))
                .containsExactlyElementsIn(toIterableRange(2, maxPictureProfiles + 1));

        // Change priority and ensure that the first layer gets access
        new SurfaceControl.Transaction().setContentPriority(mSurfaceControls[0], 0).apply();
        pictures = pollMs(picturesQueue, 200);
        assertThat(pictures).isNotNull();
        // Expect all but the last layer to be listed as an active picture
        assertThat(stream(pictures).map(picture -> picture.getPictureProfileHandle().getId()))
                .containsExactlyElementsIn(toIterableRange(1, maxPictureProfiles));
    }

    private static SurfaceControlActivePicture[] pollMs(
            BlockingQueue<SurfaceControlActivePicture[]> picturesQueue, int waitMs) {
        SurfaceControlActivePicture[] pictures = null;
        long nowMs = System.currentTimeMillis();
        long endTimeMs = nowMs + waitMs;
        while (nowMs < endTimeMs && pictures == null) {
            try {
                pictures = picturesQueue.poll(endTimeMs - nowMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // continue polling until timeout when interrupted
            }
            nowMs = System.currentTimeMillis();
        }
        return pictures;
    }

    Iterable<Long> toIterableRange(int start, int stop) {
        return () -> LongStream.rangeClosed(start, stop).iterator();
    }

    private void adoptShellPermissionIdentity(String permission) {
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(permission);
    }

    private HardwareBuffer getSolidBuffer(int width, int height) {
        // We can assume that RGBA_8888 format is supported for every platform.
        return HardwareBuffer.create(
                width, height, HardwareBuffer.RGBA_8888, 1, HardwareBuffer.USAGE_CPU_WRITE_OFTEN);
    }
}
