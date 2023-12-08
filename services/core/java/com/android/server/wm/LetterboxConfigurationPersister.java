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

import static android.os.StrictMode.setThreadPolicy;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Environment;
import android.os.StrictMode;
import android.os.StrictMode.ThreadPolicy;
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

    private static final String LETTERBOX_CONFIGURATION_FILENAME = "letterbox_config";

    private final Supplier<Integer> mDefaultHorizontalReachabilitySupplier;
    private final Supplier<Integer> mDefaultVerticalReachabilitySupplier;
    private final Supplier<Integer> mDefaultBookModeReachabilitySupplier;
    private final Supplier<Integer> mDefaultTabletopModeReachabilitySupplier;

    // Horizontal position of a center of the letterboxed app window which is global to prevent
    // "jumps" when switching between letterboxed apps. It's updated to reposition the app window
    // in response to a double tap gesture (see LetterboxUiController#handleDoubleTap). Used in
    // LetterboxUiController#getHorizontalPositionMultiplier which is called from
    // ActivityRecord#updateResolvedBoundsPosition.
    @LetterboxHorizontalReachabilityPosition
    private volatile int mLetterboxPositionForHorizontalReachability;

    // The same as mLetterboxPositionForHorizontalReachability but used when the device is
    // half-folded.
    @LetterboxHorizontalReachabilityPosition
    private volatile int mLetterboxPositionForBookModeReachability;

    // Vertical position of a center of the letterboxed app window which is global to prevent
    // "jumps" when switching between letterboxed apps. It's updated to reposition the app window
    // in response to a double tap gesture (see LetterboxUiController#handleDoubleTap). Used in
    // LetterboxUiController#getVerticalPositionMultiplier which is called from
    // ActivityRecord#updateResolvedBoundsPosition.
    @LetterboxVerticalReachabilityPosition
    private volatile int mLetterboxPositionForVerticalReachability;

    // The same as mLetterboxPositionForVerticalReachability but used when the device is
    // half-folded.
    @LetterboxVerticalReachabilityPosition
    private volatile int mLetterboxPositionForTabletopModeReachability;

    @NonNull
    private final AtomicFile mConfigurationFile;

    @Nullable
    private final Consumer<String> mCompletionCallback;

    @NonNull
    private final PersisterQueue mPersisterQueue;

    LetterboxConfigurationPersister(
            @NonNull Supplier<Integer> defaultHorizontalReachabilitySupplier,
            @NonNull Supplier<Integer> defaultVerticalReachabilitySupplier,
            @NonNull Supplier<Integer> defaultBookModeReachabilitySupplier,
            @NonNull Supplier<Integer> defaultTabletopModeReachabilitySupplier) {
        this(defaultHorizontalReachabilitySupplier, defaultVerticalReachabilitySupplier,
                defaultBookModeReachabilitySupplier, defaultTabletopModeReachabilitySupplier,
                Environment.getDataSystemDirectory(), new PersisterQueue(),
                /* completionCallback */ null, LETTERBOX_CONFIGURATION_FILENAME);
    }

    @VisibleForTesting
    LetterboxConfigurationPersister(
            @NonNull Supplier<Integer> defaultHorizontalReachabilitySupplier,
            @NonNull Supplier<Integer> defaultVerticalReachabilitySupplier,
            @NonNull Supplier<Integer> defaultBookModeReachabilitySupplier,
            @NonNull Supplier<Integer> defaultTabletopModeReachabilitySupplier,
            @NonNull File configFolder, @NonNull PersisterQueue persisterQueue,
            @Nullable Consumer<String> completionCallback,
            @NonNull String letterboxConfigurationFileName) {
        mDefaultHorizontalReachabilitySupplier = defaultHorizontalReachabilitySupplier;
        mDefaultVerticalReachabilitySupplier = defaultVerticalReachabilitySupplier;
        mDefaultBookModeReachabilitySupplier = defaultBookModeReachabilitySupplier;
        mDefaultTabletopModeReachabilitySupplier = defaultTabletopModeReachabilitySupplier;
        mCompletionCallback = completionCallback;
        final File prefFiles = new File(configFolder, letterboxConfigurationFileName);
        mConfigurationFile = new AtomicFile(prefFiles);
        mPersisterQueue = persisterQueue;
        runWithDiskReadsThreadPolicy(this::readCurrentConfiguration);
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
    int getLetterboxPositionForHorizontalReachability(boolean forBookMode) {
        if (forBookMode) {
            return mLetterboxPositionForBookModeReachability;
        } else {
            return mLetterboxPositionForHorizontalReachability;
        }
    }

    /*
     * Gets the vertical position of the letterboxed app window when vertical reachability is
     * enabled.
     */
    @LetterboxVerticalReachabilityPosition
    int getLetterboxPositionForVerticalReachability(boolean forTabletopMode) {
        if (forTabletopMode) {
            return mLetterboxPositionForTabletopModeReachability;
        } else {
            return mLetterboxPositionForVerticalReachability;
        }
    }

    /**
     * Updates letterboxPositionForVerticalReachability if different from the current value
     */
    void setLetterboxPositionForHorizontalReachability(boolean forBookMode,
            int letterboxPositionForHorizontalReachability) {
        if (forBookMode) {
            if (mLetterboxPositionForBookModeReachability
                    != letterboxPositionForHorizontalReachability) {
                mLetterboxPositionForBookModeReachability =
                        letterboxPositionForHorizontalReachability;
                updateConfiguration();
            }
        } else {
            if (mLetterboxPositionForHorizontalReachability
                    != letterboxPositionForHorizontalReachability) {
                mLetterboxPositionForHorizontalReachability =
                        letterboxPositionForHorizontalReachability;
                updateConfiguration();
            }
        }
    }

    /**
     * Updates letterboxPositionForVerticalReachability if different from the current value
     */
    void setLetterboxPositionForVerticalReachability(boolean forTabletopMode,
            int letterboxPositionForVerticalReachability) {
        if (forTabletopMode) {
            if (mLetterboxPositionForTabletopModeReachability
                    != letterboxPositionForVerticalReachability) {
                mLetterboxPositionForTabletopModeReachability =
                        letterboxPositionForVerticalReachability;
                updateConfiguration();
            }
        } else {
            if (mLetterboxPositionForVerticalReachability
                    != letterboxPositionForVerticalReachability) {
                mLetterboxPositionForVerticalReachability =
                        letterboxPositionForVerticalReachability;
                updateConfiguration();
            }
        }
    }

    @VisibleForTesting
    void useDefaultValue() {
        mLetterboxPositionForHorizontalReachability = mDefaultHorizontalReachabilitySupplier.get();
        mLetterboxPositionForVerticalReachability = mDefaultVerticalReachabilitySupplier.get();
        mLetterboxPositionForBookModeReachability =
                mDefaultBookModeReachabilitySupplier.get();
        mLetterboxPositionForTabletopModeReachability =
                mDefaultTabletopModeReachabilitySupplier.get();
    }

    private void readCurrentConfiguration() {
        if (!mConfigurationFile.exists()) {
            useDefaultValue();
            return;
        }
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
            mLetterboxPositionForBookModeReachability =
                    letterboxData.letterboxPositionForBookModeReachability;
            mLetterboxPositionForTabletopModeReachability =
                    letterboxData.letterboxPositionForTabletopModeReachability;
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
                mLetterboxPositionForBookModeReachability,
                mLetterboxPositionForTabletopModeReachability,
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

    // The LetterboxConfigurationDeviceConfig needs to access the
    // file with the current reachability position once when the
    // device boots. Because DisplayThread uses allowIo=false
    // accessing a file triggers a DiskReadViolation.
    // Here we use StrictMode to allow the current thread to read
    // the AtomicFile once in the current thread restoring the
    // original ThreadPolicy after that.
    private void runWithDiskReadsThreadPolicy(Runnable runnable) {
        final ThreadPolicy currentPolicy = StrictMode.getThreadPolicy();
        setThreadPolicy(new ThreadPolicy.Builder().permitDiskReads().build());
        runnable.run();
        setThreadPolicy(currentPolicy);
    }

    private static class UpdateValuesCommand implements
            PersisterQueue.WriteQueueItem<UpdateValuesCommand> {

        @NonNull
        private final AtomicFile mFileToUpdate;
        @Nullable
        private final Consumer<String> mOnComplete;


        private final int mHorizontalReachability;
        private final int mVerticalReachability;
        private final int mBookModeReachability;
        private final int mTabletopModeReachability;

        UpdateValuesCommand(@NonNull AtomicFile fileToUpdate,
                int horizontalReachability, int verticalReachability,
                int bookModeReachability, int tabletopModeReachability,
                @Nullable Consumer<String> onComplete) {
            mFileToUpdate = fileToUpdate;
            mHorizontalReachability = horizontalReachability;
            mVerticalReachability = verticalReachability;
            mBookModeReachability = bookModeReachability;
            mTabletopModeReachability = tabletopModeReachability;
            mOnComplete = onComplete;
        }

        @Override
        public void process() {
            final WindowManagerProtos.LetterboxProto letterboxData =
                    new WindowManagerProtos.LetterboxProto();
            letterboxData.letterboxPositionForHorizontalReachability = mHorizontalReachability;
            letterboxData.letterboxPositionForVerticalReachability = mVerticalReachability;
            letterboxData.letterboxPositionForBookModeReachability =
                    mBookModeReachability;
            letterboxData.letterboxPositionForTabletopModeReachability =
                    mTabletopModeReachability;
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
