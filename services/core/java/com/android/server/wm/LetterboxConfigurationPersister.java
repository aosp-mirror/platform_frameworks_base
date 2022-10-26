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

package com.android.server.wm;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wm.LetterboxConfiguration.LetterboxHorizontalReachabilityPosition;
import com.android.server.wm.LetterboxConfiguration.LetterboxVerticalReachabilityPosition;
import com.android.server.wm.nano.WindowManagerProtos;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Persists the values of letterboxPositionForHorizontalReachability and
 * letterboxPositionForVerticalReachability for {@link LetterboxConfiguration}.
 */
class LetterboxConfigurationPersister {

    private static final String TAG =
            TAG_WITH_CLASS_NAME ? "LetterboxConfigurationPersister" : TAG_WM;

    @VisibleForTesting
    static final String LETTERBOX_CONFIGURATION_FILENAME = "letterbox_config";

    private final Context mContext;
    private final Supplier<Integer> mDefaultHorizontalReachabilitySupplier;
    private final Supplier<Integer> mDefaultVerticalReachabilitySupplier;

    // Horizontal position of a center of the letterboxed app window which is global to prevent
    // "jumps" when switching between letterboxed apps. It's updated to reposition the app window
    // in response to a double tap gesture (see LetterboxUiController#handleDoubleTap). Used in
    // LetterboxUiController#getHorizontalPositionMultiplier which is called from
    // ActivityRecord#updateResolvedBoundsPosition.
    @LetterboxHorizontalReachabilityPosition
    private volatile int mLetterboxPositionForHorizontalReachability;

    // Vertical position of a center of the letterboxed app window which is global to prevent
    // "jumps" when switching between letterboxed apps. It's updated to reposition the app window
    // in response to a double tap gesture (see LetterboxUiController#handleDoubleTap). Used in
    // LetterboxUiController#getVerticalPositionMultiplier which is called from
    // ActivityRecord#updateResolvedBoundsPosition.
    @LetterboxVerticalReachabilityPosition
    private volatile int mLetterboxPositionForVerticalReachability;

    @NonNull
    private final AtomicFile mConfigurationFile;

    @Nullable
    private final Consumer<String> mCompletionCallback;

    @NonNull
    private final PersisterQueue mPersisterQueue;

    LetterboxConfigurationPersister(Context systemUiContext,
            Supplier<Integer> defaultHorizontalReachabilitySupplier,
            Supplier<Integer> defaultVerticalReachabilitySupplier) {
        this(systemUiContext, defaultHorizontalReachabilitySupplier,
                defaultVerticalReachabilitySupplier,
                Environment.getDataSystemDirectory(), new PersisterQueue(),
                /* completionCallback */ null);
    }

    @VisibleForTesting
    LetterboxConfigurationPersister(Context systemUiContext,
            Supplier<Integer> defaultHorizontalReachabilitySupplier,
            Supplier<Integer> defaultVerticalReachabilitySupplier, File configFolder,
            PersisterQueue persisterQueue, @Nullable Consumer<String> completionCallback) {
        mContext = systemUiContext.createDeviceProtectedStorageContext();
        mDefaultHorizontalReachabilitySupplier = defaultHorizontalReachabilitySupplier;
        mDefaultVerticalReachabilitySupplier = defaultVerticalReachabilitySupplier;
        mCompletionCallback = completionCallback;
        final File prefFiles = new File(configFolder, LETTERBOX_CONFIGURATION_FILENAME);
        mConfigurationFile = new AtomicFile(prefFiles);
        mPersisterQueue = persisterQueue;
        readCurrentConfiguration();
    }

    /**
     * Startes the persistence queue
     */
    void start() {
        mPersisterQueue.startPersisting();
    }

    /*
     * Gets the horizontal position of the letterboxed app window when horizontal reachability is
     * enabled.
     */
    @LetterboxHorizontalReachabilityPosition
    int getLetterboxPositionForHorizontalReachability() {
        return mLetterboxPositionForHorizontalReachability;
    }

    /*
     * Gets the vertical position of the letterboxed app window when vertical reachability is
     * enabled.
     */
    @LetterboxVerticalReachabilityPosition
    int getLetterboxPositionForVerticalReachability() {
        return mLetterboxPositionForVerticalReachability;
    }

    /**
     * Updates letterboxPositionForVerticalReachability if different from the current value
     */
    void setLetterboxPositionForHorizontalReachability(
            int letterboxPositionForHorizontalReachability) {
        if (mLetterboxPositionForHorizontalReachability
                != letterboxPositionForHorizontalReachability) {
            mLetterboxPositionForHorizontalReachability =
                    letterboxPositionForHorizontalReachability;
            updateConfiguration();
        }
    }

    /**
     * Updates letterboxPositionForVerticalReachability if different from the current value
     */
    void setLetterboxPositionForVerticalReachability(
            int letterboxPositionForVerticalReachability) {
        if (mLetterboxPositionForVerticalReachability != letterboxPositionForVerticalReachability) {
            mLetterboxPositionForVerticalReachability = letterboxPositionForVerticalReachability;
            updateConfiguration();
        }
    }

    @VisibleForTesting
    void useDefaultValue() {
        mLetterboxPositionForHorizontalReachability = mDefaultHorizontalReachabilitySupplier.get();
        mLetterboxPositionForVerticalReachability = mDefaultVerticalReachabilitySupplier.get();
    }

    private void readCurrentConfiguration() {
        FileInputStream fis = null;
        try {
            fis = mConfigurationFile.openRead();
            byte[] protoData = readInputStream(fis);
            final WindowManagerProtos.LetterboxProto letterboxData =
                    WindowManagerProtos.LetterboxProto.parseFrom(protoData);
            mLetterboxPositionForHorizontalReachability =
                    letterboxData.letterboxPositionForHorizontalReachability;
            mLetterboxPositionForVerticalReachability =
                    letterboxData.letterboxPositionForVerticalReachability;
        } catch (IOException ioe) {
            Slog.e(TAG,
                    "Error reading from LetterboxConfigurationPersister. "
                            + "Using default values!", ioe);
            useDefaultValue();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    useDefaultValue();
                    Slog.e(TAG, "Error reading from LetterboxConfigurationPersister ", e);
                }
            }
        }
    }

    private void updateConfiguration() {
        mPersisterQueue.addItem(new UpdateValuesCommand(mConfigurationFile,
                mLetterboxPositionForHorizontalReachability,
                mLetterboxPositionForVerticalReachability,
                mCompletionCallback), /* flush */ true);
    }

    private static byte[] readInputStream(InputStream in) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[1024];
            int size = in.read(buffer);
            while (size > 0) {
                outputStream.write(buffer, 0, size);
                size = in.read(buffer);
            }
            return outputStream.toByteArray();
        } finally {
            outputStream.close();
        }
    }

    private static class UpdateValuesCommand implements
            PersisterQueue.WriteQueueItem<UpdateValuesCommand> {

        @NonNull
        private final AtomicFile mFileToUpdate;
        @Nullable
        private final Consumer<String> mOnComplete;


        private final int mHorizontalReachability;
        private final int mVerticalReachability;

        UpdateValuesCommand(@NonNull AtomicFile fileToUpdate,
                int horizontalReachability, int verticalReachability,
                @Nullable Consumer<String> onComplete) {
            mFileToUpdate = fileToUpdate;
            mHorizontalReachability = horizontalReachability;
            mVerticalReachability = verticalReachability;
            mOnComplete = onComplete;
        }

        @Override
        public void process() {
            final WindowManagerProtos.LetterboxProto letterboxData =
                    new WindowManagerProtos.LetterboxProto();
            letterboxData.letterboxPositionForHorizontalReachability = mHorizontalReachability;
            letterboxData.letterboxPositionForVerticalReachability = mVerticalReachability;
            final byte[] bytes = WindowManagerProtos.LetterboxProto.toByteArray(letterboxData);

            FileOutputStream fos = null;
            try {
                fos = mFileToUpdate.startWrite();
                fos.write(bytes);
                mFileToUpdate.finishWrite(fos);
            } catch (IOException ioe) {
                mFileToUpdate.failWrite(fos);
                Slog.e(TAG,
                        "Error writing to LetterboxConfigurationPersister. "
                                + "Using default values!", ioe);
            } finally {
                if (mOnComplete != null) {
                    mOnComplete.accept("UpdateValuesCommand");
                }
            }
        }
    }
}
