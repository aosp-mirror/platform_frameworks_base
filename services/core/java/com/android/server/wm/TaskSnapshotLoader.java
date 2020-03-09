/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.ActivityManager;
import android.app.ActivityManager.TaskSnapshot;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.util.Slog;

import com.android.server.wm.nano.WindowManagerProtos.TaskSnapshotProto;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Loads a persisted {@link TaskSnapshot} from disk.
 * <p>
 * Do not hold the window manager lock when accessing this class.
 * <p>
 * Test class: {@link TaskSnapshotPersisterLoaderTest}
 */
class TaskSnapshotLoader {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "TaskSnapshotLoader" : TAG_WM;

    private final TaskSnapshotPersister mPersister;

    TaskSnapshotLoader(TaskSnapshotPersister persister) {
        mPersister = persister;
    }

    static class PreRLegacySnapshotConfig {
        /**
         * If isPreRLegacy is {@code true}, specifies the scale the snapshot was taken at
         */
        final float mScale;

        /**
         * If {@code true}, always load *_reduced.jpg file, no matter what was requested
         */
        final boolean mForceLoadReducedJpeg;

        PreRLegacySnapshotConfig(float scale, boolean forceLoadReducedJpeg) {
            mScale = scale;
            mForceLoadReducedJpeg = forceLoadReducedJpeg;
        }
    }

    /**
     * When device is upgraded, we might be loading a legacy snapshot. In those cases,
     * restore the scale based on how it was configured historically. See history of
     * TaskSnapshotPersister for more information.
     *
     *   | low_ram=false                      | low_ram=true
     *   +------------------------------------------------------------------------------+
     * O | *.jpg = 100%, *_reduced.jpg = 50%                                            |
     *   |                                    +-----------------------------------------|
     * P |                                    | *.jpg = NONE, *_reduced.jpg = 60%       |
     *   +------------------------------------+-----------------------------------------+
     * Q | *.jpg = proto.scale,               | *.jpg = NONE,                           |
     *   | *_reduced.jpg = 50% * proto.scale  | *_reduced.jpg = proto.scale             |
     *   +------------------------------------+-----------------------------------------+
     *
     * @return null if Android R, otherwise a PreRLegacySnapshotConfig object
     */
    PreRLegacySnapshotConfig getLegacySnapshotConfig(int taskWidth, float legacyScale,
            boolean highResFileExists, boolean loadLowResolutionBitmap) {
        float preRLegacyScale = 0;
        boolean forceLoadReducedJpeg = false;
        boolean isPreRLegacySnapshot = (taskWidth == 0);
        if (!isPreRLegacySnapshot) {
            return null;
        }
        final boolean isPreQLegacyProto = isPreRLegacySnapshot
                && (Float.compare(legacyScale, 0f) == 0);

        if (isPreQLegacyProto) {
            // Android O or Android P
            if (ActivityManager.isLowRamDeviceStatic() && !highResFileExists) {
                // Android P w/ low_ram=true
                preRLegacyScale = 0.6f;
                // Force bitmapFile to always be *_reduced.jpg
                forceLoadReducedJpeg = true;
            } else {
                // Android O, OR Android P w/ low_ram=false
                preRLegacyScale = loadLowResolutionBitmap ? 0.5f : 1.0f;
            }
        } else if (isPreRLegacySnapshot) {
            // If not pre-Q but is pre-R, then it must be Android Q
            if (ActivityManager.isLowRamDeviceStatic()) {
                preRLegacyScale = legacyScale;
                // Force bitmapFile to always be *_reduced.jpg
                forceLoadReducedJpeg = true;
            } else {
                preRLegacyScale =
                        loadLowResolutionBitmap ? 0.5f * legacyScale : legacyScale;
            }
        }
        return new PreRLegacySnapshotConfig(preRLegacyScale, forceLoadReducedJpeg);
    }

    /**
     * Loads a task from the disk.
     * <p>
     * Do not hold the window manager lock when calling this method, as we directly read data from
     * disk here, which might be slow.
     *
     * @param taskId                  The id of the task to load.
     * @param userId                  The id of the user the task belonged to.
     * @param loadLowResolutionBitmap Whether to load a low resolution resolution version of the
     *                                snapshot.
     * @return The loaded {@link TaskSnapshot} or {@code null} if it couldn't be loaded.
     */
    TaskSnapshot loadTask(int taskId, int userId, boolean loadLowResolutionBitmap) {
        final File protoFile = mPersister.getProtoFile(taskId, userId);
        if (!protoFile.exists()) {
            return null;
        }
        try {
            final byte[] bytes = Files.readAllBytes(protoFile.toPath());
            final TaskSnapshotProto proto = TaskSnapshotProto.parseFrom(bytes);
            final File highResBitmap = mPersister.getHighResolutionBitmapFile(taskId, userId);

            PreRLegacySnapshotConfig legacyConfig = getLegacySnapshotConfig(proto.taskWidth,
                    proto.legacyScale, highResBitmap.exists(), loadLowResolutionBitmap);

            boolean forceLoadReducedJpeg =
                    legacyConfig != null && legacyConfig.mForceLoadReducedJpeg;
            File bitmapFile = (loadLowResolutionBitmap || forceLoadReducedJpeg)
                    ? mPersister.getLowResolutionBitmapFile(taskId, userId) : highResBitmap;

            if (!bitmapFile.exists()) {
                return null;
            }

            final Options options = new Options();
            options.inPreferredConfig = mPersister.use16BitFormat() && !proto.isTranslucent
                    ? Config.RGB_565
                    : Config.ARGB_8888;
            final Bitmap bitmap = BitmapFactory.decodeFile(bitmapFile.getPath(), options);
            if (bitmap == null) {
                Slog.w(TAG, "Failed to load bitmap: " + bitmapFile.getPath());
                return null;
            }

            final Bitmap hwBitmap = bitmap.copy(Config.HARDWARE, false);
            bitmap.recycle();
            if (hwBitmap == null) {
                Slog.w(TAG, "Failed to create hardware bitmap: " + bitmapFile.getPath());
                return null;
            }
            final HardwareBuffer buffer = hwBitmap.getHardwareBuffer();
            if (buffer == null) {
                Slog.w(TAG, "Failed to retrieve gralloc buffer for bitmap: "
                        + bitmapFile.getPath());
                return null;
            }

            final ComponentName topActivityComponent = ComponentName.unflattenFromString(
                    proto.topActivityComponent);

            Point taskSize;
            if (legacyConfig != null) {
                int taskWidth = (int) ((float) hwBitmap.getWidth() / legacyConfig.mScale);
                int taskHeight = (int) ((float) hwBitmap.getHeight() / legacyConfig.mScale);
                taskSize = new Point(taskWidth, taskHeight);
            } else {
                taskSize = new Point(proto.taskWidth, proto.taskHeight);
            }

            return new TaskSnapshot(proto.id, topActivityComponent, buffer,
                    hwBitmap.getColorSpace(), proto.orientation, proto.rotation, taskSize,
                    new Rect(proto.insetLeft, proto.insetTop, proto.insetRight, proto.insetBottom),
                    loadLowResolutionBitmap, proto.isRealSnapshot, proto.windowingMode,
                    proto.systemUiVisibility, proto.isTranslucent);
        } catch (IOException e) {
            Slog.w(TAG, "Unable to load task snapshot data for taskId=" + taskId);
            return null;
        }
    }
}
