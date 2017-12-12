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

import static com.android.server.wm.TaskSnapshotPersister.*;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.app.ActivityManager.TaskSnapshot;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.BitmapFactory.Options;
import android.graphics.GraphicBuffer;
import android.graphics.Rect;
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

    /**
     * Loads a task from the disk.
     * <p>
     * Do not hold the window manager lock when calling this method, as we directly read data from
     * disk here, which might be slow.
     *
     * @param taskId The id of the task to load.
     * @param userId The id of the user the task belonged to.
     * @param reducedResolution Whether to load a reduced resolution version of the snapshot.
     * @return The loaded {@link TaskSnapshot} or {@code null} if it couldn't be loaded.
     */
    TaskSnapshot loadTask(int taskId, int userId, boolean reducedResolution) {
        final File protoFile = mPersister.getProtoFile(taskId, userId);
        final File bitmapFile = reducedResolution
                ? mPersister.getReducedResolutionBitmapFile(taskId, userId)
                : mPersister.getBitmapFile(taskId, userId);
        if (bitmapFile == null || !protoFile.exists() || !bitmapFile.exists()) {
            return null;
        }
        try {
            final byte[] bytes = Files.readAllBytes(protoFile.toPath());
            final TaskSnapshotProto proto = TaskSnapshotProto.parseFrom(bytes);
            final Options options = new Options();
            options.inPreferredConfig = Config.HARDWARE;
            final Bitmap bitmap = BitmapFactory.decodeFile(bitmapFile.getPath(), options);
            if (bitmap == null) {
                Slog.w(TAG, "Failed to load bitmap: " + bitmapFile.getPath());
                return null;
            }
            final GraphicBuffer buffer = bitmap.createGraphicBufferHandle();
            if (buffer == null) {
                Slog.w(TAG, "Failed to retrieve gralloc buffer for bitmap: "
                        + bitmapFile.getPath());
                return null;
            }
            return new TaskSnapshot(buffer, proto.orientation,
                    new Rect(proto.insetLeft, proto.insetTop, proto.insetRight, proto.insetBottom),
                    reducedResolution, reducedResolution ? REDUCED_SCALE : 1f);
        } catch (IOException e) {
            Slog.w(TAG, "Unable to load task snapshot data for taskId=" + taskId);
            return null;
        }
    }
}
