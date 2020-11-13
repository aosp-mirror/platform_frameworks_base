/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.apppairs;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_TASK_ORG;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Class that manager pool of {@link AppPair} objects. Helps reduce the need to call system_server
 *  to create a root task for the app-pair when needed since we always have one ready to go.
 */
class AppPairsPool {
    private static final String TAG = AppPairsPool.class.getSimpleName();

    @VisibleForTesting
    final AppPairsController mController;
    // The pool
    private final ArrayList<AppPair> mPool = new ArrayList();

    AppPairsPool(AppPairsController controller) {
        mController = controller;
        incrementPool();
    }

    AppPair acquire() {
        final AppPair entry = mPool.remove(mPool.size() - 1);
        ProtoLog.v(WM_SHELL_TASK_ORG, "acquire entry.taskId=%s listener=%s size=%d",
                entry.getRootTaskId(), entry, mPool.size());
        if (mPool.size() == 0) {
            incrementPool();
        }
        return entry;
    }

    void release(AppPair entry) {
        mPool.add(entry);
        ProtoLog.v(WM_SHELL_TASK_ORG, "release entry.taskId=%s listener=%s size=%d",
                entry.getRootTaskId(), entry, mPool.size());
    }

    @VisibleForTesting
    void incrementPool() {
        ProtoLog.v(WM_SHELL_TASK_ORG, "incrementPool size=%d", mPool.size());
        final AppPair entry = new AppPair(mController);
        // TODO: multi-display...
        mController.getTaskOrganizer().createRootTask(
                DEFAULT_DISPLAY, WINDOWING_MODE_FULLSCREEN, entry);
        mPool.add(entry);
    }

    @VisibleForTesting
    int poolSize() {
        return mPool.size();
    }

    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + this);
        for (int i = mPool.size() - 1; i >= 0; --i) {
            mPool.get(i).dump(pw, childPrefix);
        }
    }

    @Override
    public String toString() {
        return TAG + "#" + mPool.size();
    }
}
