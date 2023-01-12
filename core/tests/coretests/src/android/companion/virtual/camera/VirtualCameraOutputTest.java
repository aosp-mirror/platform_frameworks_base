/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.companion.virtual.camera;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.graphics.PixelFormat;
import android.hardware.camera2.params.InputConfiguration;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class VirtualCameraOutputTest {

    private static final String TAG = "VirtualCameraOutputTest";

    private ExecutorService mExecutor;

    private InputConfiguration mConfiguration;

    @Before
    public void setUp() {
        mExecutor = Executors.newSingleThreadExecutor();
        mConfiguration = new InputConfiguration(64, 64, PixelFormat.RGB_888);
    }

    @After
    public void cleanUp() {
        mExecutor.shutdownNow();
    }

    @Test
    public void createStreamDescriptor_successfulDataStream() {
        byte[] cameraData = new byte[]{1, 2, 3, 4, 5};
        VirtualCameraInput input = createCameraInput(cameraData);
        VirtualCameraOutput output = new VirtualCameraOutput(input, mExecutor);
        ParcelFileDescriptor descriptor = output.getStreamDescriptor(mConfiguration);

        try (FileInputStream fis = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] receivedData = fis.readNBytes(cameraData.length);

            output.closeStream();
            assertThat(receivedData).isEqualTo(cameraData);
        } catch (IOException exception) {
            fail("Unable to read bytes from FileInputStream. Message: " + exception.getMessage());
        }
    }

    @Test
    public void createStreamDescriptor_multipleCallsSameStream() {
        VirtualCameraInput input = createCameraInput(new byte[]{0});
        VirtualCameraOutput output = new VirtualCameraOutput(input, mExecutor);

        ParcelFileDescriptor firstDescriptor = output.getStreamDescriptor(mConfiguration);
        ParcelFileDescriptor secondDescriptor = output.getStreamDescriptor(mConfiguration);

        assertThat(firstDescriptor).isSameInstanceAs(secondDescriptor);
    }

    @Test
    public void createStreamDescriptor_differentStreams() {
        VirtualCameraInput input = createCameraInput(new byte[]{0});
        VirtualCameraOutput callback = new VirtualCameraOutput(input, mExecutor);

        InputConfiguration differentConfig = new InputConfiguration(mConfiguration.getWidth() + 1,
                mConfiguration.getHeight() + 1, mConfiguration.getFormat());

        ParcelFileDescriptor firstDescriptor = callback.getStreamDescriptor(mConfiguration);
        ParcelFileDescriptor secondDescriptor = callback.getStreamDescriptor(differentConfig);

        assertThat(firstDescriptor).isNotSameInstanceAs(secondDescriptor);
    }

    private VirtualCameraInput createCameraInput(byte[] data) {
        return new VirtualCameraInput() {
            private ByteArrayInputStream mInputStream = null;

            @Override
            @NonNull
            public InputStream openStream(@NonNull InputConfiguration inputConfiguration) {
                closeStream();
                mInputStream = new ByteArrayInputStream(data);
                return mInputStream;
            }

            @Override
            public void closeStream() {
                if (mInputStream == null) {
                    return;
                }
                try {
                    mInputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Unable to close image stream.", e);
                }
                mInputStream = null;
            }
        };
    }
}
