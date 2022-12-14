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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.camera2.params.InputConfiguration;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Component for providing Camera data to the system.
 * <p>
 * {@link #getStreamDescriptor(InputConfiguration)} will be called by the system when Camera should
 * start sending image data. Camera data will continue to be sent into {@link ParcelFileDescriptor}
 * until {@link #closeStream()} is called by the system, at which point {@link ParcelFileDescriptor}
 * will be closed.
 *
 * @hide
 */
@VisibleForTesting
public class VirtualCameraOutput {

    private static final String TAG = "VirtualCameraDeviceImpl";

    @NonNull
    private final VirtualCameraInput mVirtualCameraInput;
    @NonNull
    private final Executor mExecutor;
    @Nullable
    private VirtualCameraStream mCameraStream;

    @VisibleForTesting
    public VirtualCameraOutput(@NonNull VirtualCameraInput cameraInput,
            @NonNull Executor executor) {
        mVirtualCameraInput = Objects.requireNonNull(cameraInput);
        mExecutor = Objects.requireNonNull(executor);
    }

    /**
     * Get a read Descriptor on which Camera HAL will receive data. At any point in time there can
     * exist a maximum of one active {@link ParcelFileDescriptor}.
     * Calling this method with a different {@link InputConfiguration} is going to close the
     * previously created file descriptor.
     *
     * @param imageConfiguration for which to create the {@link ParcelFileDescriptor}.
     * @return Newly created ParcelFileDescriptor if stream param is different from previous or if
     *         this is first time call. Will return null if there was an error during Descriptor
     *         creation process.
     */
    @Nullable
    @VisibleForTesting
    public ParcelFileDescriptor getStreamDescriptor(
            @NonNull InputConfiguration imageConfiguration) {
        Objects.requireNonNull(imageConfiguration);

        // Reuse same descriptor if stream is the same, otherwise create a new one.
        try {
            if (mCameraStream == null) {
                mCameraStream = new VirtualCameraStream(imageConfiguration, mExecutor);
            } else if (!mCameraStream.isSameConfiguration(imageConfiguration)) {
                mCameraStream.close();
                mCameraStream = new VirtualCameraStream(imageConfiguration, mExecutor);
            }
        } catch (IOException exception) {
            Log.e(TAG, "Unable to open file descriptor.", exception);
            return null;
        }

        InputStream imageStream = mVirtualCameraInput.openStream(imageConfiguration);
        mCameraStream.startSending(imageStream);
        return mCameraStream.getDescriptor();
    }

    /**
     * Closes currently opened stream. If there is no stream, do nothing.
     */
    @VisibleForTesting
    public void closeStream() {
        mVirtualCameraInput.closeStream();
        if (mCameraStream != null) {
            mCameraStream.close();
            mCameraStream = null;
        }

        try {
            mVirtualCameraInput.closeStream();
        } catch (Exception e) {
            Log.e(TAG, "Error during closing stream.", e);
        }
    }

    private static class VirtualCameraStream implements AutoCloseable {

        private static final String TAG = "VirtualCameraStream";
        private static final int BUFFER_SIZE = 1024;

        private static final int SENDING_STATE_INITIAL = 0;
        private static final int SENDING_STATE_IN_PROGRESS = 1;
        private static final int SENDING_STATE_CLOSED = 2;

        @NonNull
        private final InputConfiguration mImageConfiguration;
        @NonNull
        private final Executor mExecutor;
        @Nullable
        private final ParcelFileDescriptor mReadDescriptor;
        @Nullable
        private final ParcelFileDescriptor mWriteDescriptor;
        private int mSendingState;

        VirtualCameraStream(@NonNull InputConfiguration imageConfiguration,
                @NonNull Executor executor) throws IOException {
            mSendingState = SENDING_STATE_INITIAL;
            mImageConfiguration = Objects.requireNonNull(imageConfiguration);
            mExecutor = Objects.requireNonNull(executor);
            ParcelFileDescriptor[] parcels = ParcelFileDescriptor.createPipe();
            mReadDescriptor = parcels[0];
            mWriteDescriptor = parcels[1];
        }

        boolean isSameConfiguration(@NonNull InputConfiguration imageConfiguration) {
            return mImageConfiguration == Objects.requireNonNull(imageConfiguration);
        }

        @Nullable
        ParcelFileDescriptor getDescriptor() {
            return mReadDescriptor;
        }

        public void startSending(@NonNull InputStream inputStream) {
            Objects.requireNonNull(inputStream);

            if (mSendingState != SENDING_STATE_INITIAL) {
                return;
            }

            mSendingState = SENDING_STATE_IN_PROGRESS;
            mExecutor.execute(() -> sendData(inputStream));
        }

        @Override
        public void close() {
            mSendingState = SENDING_STATE_CLOSED;
            try {
                mReadDescriptor.close();
            } catch (IOException e) {
                Log.e(TAG, "Unable to close read descriptor.", e);
            }
            try {
                mWriteDescriptor.close();
            } catch (IOException e) {
                Log.e(TAG, "Unable to close write descriptor.", e);
            }
        }

        private void sendData(@NonNull InputStream inputStream) {
            Objects.requireNonNull(inputStream);

            byte[] buffer = new byte[BUFFER_SIZE];
            FileDescriptor fd = mWriteDescriptor.getFileDescriptor();
            try (FileOutputStream outputStream = new FileOutputStream(fd)) {
                while (mSendingState == SENDING_STATE_IN_PROGRESS) {
                    int bytesRead = inputStream.read(buffer, 0, BUFFER_SIZE);
                    if (bytesRead < 1) continue;

                    outputStream.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error while sending camera data.", e);
            }
        }
    }
}
