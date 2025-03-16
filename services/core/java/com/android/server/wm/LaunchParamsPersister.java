/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManagerInternal;
import android.graphics.Rect;
import android.os.Environment;
import android.os.Process;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;
import com.android.server.pm.PackageList;
import com.android.server.wm.LaunchParamsController.LaunchParams;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.IntFunction;

/**
 * Persister that saves launch parameters in memory and in storage. It saves the last seen state of
 * tasks key-ed on task's user ID and the activity used to launch the task ({@link
 * Task#realActivity}) and that's used to determine the launch params when the activity is
 * being launched again in {@link LaunchParamsController}.
 *
 * Need to hold {@link ActivityTaskManagerService#getGlobalLock()} to access this class.
 */
class LaunchParamsPersister {
    private static final String TAG = "LaunchParamsPersister";
    private static final String LAUNCH_PARAMS_DIRNAME = "launch_params";
    private static final String LAUNCH_PARAMS_FILE_SUFFIX = ".xml";

    // Chars below are used to escape the backslash in component name to underscore.
    private static final char ORIGINAL_COMPONENT_SEPARATOR = '/';
    private static final char ESCAPED_COMPONENT_SEPARATOR = '-';
    private static final char OLD_ESCAPED_COMPONENT_SEPARATOR = '_';

    private static final String TAG_LAUNCH_PARAMS = "launch_params";

    private final PersisterQueue mPersisterQueue;
    private final ActivityTaskSupervisor mSupervisor;

    /**
     * A function that takes in user ID and returns a folder to store information of that user. Used
     * to differentiate storage location in test environment and production environment.
     */
    private final IntFunction<File> mUserFolderGetter;

    private PackageList mPackageList;

    /**
     * A map from user ID to the active {@link LoadingTask} when we're loading the launch params for
     * that user.
     */
    private final SparseArray<LoadingTask> mLoadingTaskMap = new SparseArray<>();

    /**
     * A dual layer map that first maps user ID to a secondary map, which maps component name (the
     * launching activity of tasks) to {@link PersistableLaunchParams} that stores launch metadata
     * that are stable across reboots.
     */
    private final SparseArray<ArrayMap<ComponentName, PersistableLaunchParams>> mLaunchParamsMap =
            new SparseArray<>();

    /**
     * A map from {@link android.content.pm.ActivityInfo.WindowLayout#windowLayoutAffinity} to
     * activity's component name for reverse queries from window layout affinities to activities.
     * Used to decide if we should use another activity's record with the same affinity.
     */
    private final ArrayMap<String, ArraySet<ComponentName>> mWindowLayoutAffinityMap =
            new ArrayMap<>();

    LaunchParamsPersister(PersisterQueue persisterQueue, ActivityTaskSupervisor supervisor) {
        this(persisterQueue, supervisor, Environment::getDataSystemCeDirectory);
    }

    @VisibleForTesting
    LaunchParamsPersister(PersisterQueue persisterQueue, ActivityTaskSupervisor supervisor,
            IntFunction<File> userFolderGetter) {
        mPersisterQueue = persisterQueue;
        mSupervisor = supervisor;
        mUserFolderGetter = userFolderGetter;
    }

    void onSystemReady() {
        PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        mPackageList = pmi.getPackageList(new PackageListObserver());
    }

    void onUnlockUser(int userId) {
        if (mLoadingTaskMap.contains(userId)) {
            Slog.e(TAG, "Duplicated onUnlockUser " + userId);
            return;
        }

        final LoadingTask task = new LoadingTask(userId);
        mLoadingTaskMap.put(userId, task);
        task.execute();
    }

    void onCleanupUser(int userId) {
        // There is no need to abort the task itself. Just let the loading task finish silently
        // without modifying any state.
        mLoadingTaskMap.remove(userId);
        mLaunchParamsMap.remove(userId);
    }

    private void waitAndMoveResultIfLoading(int userId) {
        final LoadingTask task = mLoadingTaskMap.removeReturnOld(userId);
        if (task == null) {
            return;
        }
        final ArrayMap<ComponentName, PersistableLaunchParams> map = task.get();
        if (map == null) {
            return;
        }
        mLaunchParamsMap.put(userId, map);
    }

    void saveTask(Task task) {
        saveTask(task, task.getDisplayContent());
    }

    void saveTask(Task task, DisplayContent display) {
        final ComponentName name = task.realActivity;
        if (name == null) {
            return;
        }
        final int userId = task.mUserId;
        waitAndMoveResultIfLoading(userId);
        PersistableLaunchParams params;
        ArrayMap<ComponentName, PersistableLaunchParams> map = mLaunchParamsMap.get(userId);
        if (map == null) {
            map = new ArrayMap<>();
            mLaunchParamsMap.put(userId, map);
        }

        params = map.computeIfAbsent(name, componentName -> new PersistableLaunchParams());
        final boolean changed = saveTaskToLaunchParam(task, display, params);

        addComponentNameToLaunchParamAffinityMapIfNotNull(name, params.mWindowLayoutAffinity);

        if (changed) {
            mPersisterQueue.updateLastOrAddItem(
                    new LaunchParamsWriteQueueItem(userId, name, params),
                    /* flush */ false);
        }
    }

    private boolean saveTaskToLaunchParam(
            Task task, DisplayContent display, PersistableLaunchParams params) {
        final DisplayInfo info = new DisplayInfo();
        display.mDisplay.getDisplayInfo(info);

        boolean changed = !Objects.equals(params.mDisplayUniqueId, info.uniqueId);
        params.mDisplayUniqueId = info.uniqueId;

        changed |= params.mWindowingMode != task.getWindowingMode();
        params.mWindowingMode = task.getWindowingMode();

        if (task.mLastNonFullscreenBounds != null) {
            changed |= !Objects.equals(params.mBounds, task.mLastNonFullscreenBounds);
            params.mBounds.set(task.mLastNonFullscreenBounds);
        } else {
            changed |= !params.mBounds.isEmpty();
            params.mBounds.setEmpty();
        }

        String launchParamAffinity = task.mWindowLayoutAffinity;
        changed |= Objects.equals(launchParamAffinity, params.mWindowLayoutAffinity);
        params.mWindowLayoutAffinity = launchParamAffinity;

        if (changed) {
            params.mTimestamp = System.currentTimeMillis();
        }

        return changed;
    }

    private void addComponentNameToLaunchParamAffinityMapIfNotNull(
            ComponentName name, String launchParamAffinity) {
        if (launchParamAffinity == null) {
            return;
        }
        mWindowLayoutAffinityMap.computeIfAbsent(launchParamAffinity, affinity -> new ArraySet<>())
                .add(name);
    }

    void getLaunchParams(Task task, ActivityRecord activity, LaunchParams outParams) {
        final ComponentName name = task != null ? task.realActivity : activity.mActivityComponent;
        final int userId = task != null ? task.mUserId : activity.mUserId;
        waitAndMoveResultIfLoading(userId);
        final String windowLayoutAffinity;
        if (task != null) {
            windowLayoutAffinity = task.mWindowLayoutAffinity;
        } else {
            ActivityInfo.WindowLayout layout = activity.info.windowLayout;
            windowLayoutAffinity = layout == null ? null : layout.windowLayoutAffinity;
        }

        outParams.reset();
        Map<ComponentName, PersistableLaunchParams> map = mLaunchParamsMap.get(userId);
        if (map == null) {
            return;
        }

        // First use its own record as a reference.
        PersistableLaunchParams persistableParams = map.get(name);
        // Next we'll compare these params against all existing params with the same affinity and
        // use the newest one.
        if (windowLayoutAffinity != null
                && mWindowLayoutAffinityMap.get(windowLayoutAffinity) != null) {
            ArraySet<ComponentName> candidates = mWindowLayoutAffinityMap.get(windowLayoutAffinity);
            for (int i = 0; i < candidates.size(); ++i) {
                ComponentName candidate = candidates.valueAt(i);
                final PersistableLaunchParams candidateParams = map.get(candidate);
                if (candidateParams == null) {
                    continue;
                }

                if (persistableParams == null
                        || candidateParams.mTimestamp > persistableParams.mTimestamp) {
                    persistableParams = candidateParams;
                }
            }
        }

        if (persistableParams == null) {
            return;
        }

        final DisplayContent display = mSupervisor.mRootWindowContainer.getDisplayContent(
                persistableParams.mDisplayUniqueId);
        if (display != null) {
            // TODO(b/153764726): Investigate if task display area needs to be persisted vs
            // always choosing the default one.
            outParams.mPreferredTaskDisplayArea = display.getDefaultTaskDisplayArea();
        }
        outParams.mWindowingMode = persistableParams.mWindowingMode;
        outParams.mBounds.set(persistableParams.mBounds);
    }

    void removeRecordForPackage(String packageName) {
        final List<File> fileToDelete = new ArrayList<>();
        for (int i = 0; i < mLaunchParamsMap.size(); ++i) {
            int userId = mLaunchParamsMap.keyAt(i);
            final File launchParamsFolder = getLaunchParamFolder(userId);
            ArrayMap<ComponentName, PersistableLaunchParams> map = mLaunchParamsMap.valueAt(i);
            for (int j = map.size() - 1; j >= 0; --j) {
                final ComponentName name = map.keyAt(j);
                if (name.getPackageName().equals(packageName)) {
                    map.removeAt(j);
                    fileToDelete.add(getParamFile(launchParamsFolder, name));
                }
            }
        }

        synchronized (mPersisterQueue) {
            mPersisterQueue.removeItems(
                    item -> item.mComponentName.getPackageName().equals(packageName),
                    LaunchParamsWriteQueueItem.class);

            mPersisterQueue.addItem(new CleanUpComponentQueueItem(fileToDelete), true);
        }
    }

    private File getParamFile(File launchParamFolder, ComponentName name) {
        final String componentNameString = name.flattenToShortString()
                .replace(ORIGINAL_COMPONENT_SEPARATOR, ESCAPED_COMPONENT_SEPARATOR);
        return new File(launchParamFolder, componentNameString + LAUNCH_PARAMS_FILE_SUFFIX);
    }

    private File getLaunchParamFolder(int userId) {
        final File userFolder = mUserFolderGetter.apply(userId);
        return new File(userFolder, LAUNCH_PARAMS_DIRNAME);
    }

    private class PackageListObserver implements PackageManagerInternal.PackageListObserver {
        @Override
        public void onPackageAdded(String packageName, int uid) {}

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            synchronized (mSupervisor.mService.getGlobalLock()) {
                removeRecordForPackage(packageName);
            }
        }
    }

    private class LoadingTask
            implements Callable<ArrayMap<ComponentName, PersistableLaunchParams>> {
        private final int mUserId;
        private final FutureTask<ArrayMap<ComponentName, PersistableLaunchParams>> mFutureTask;

        private LoadingTask(int userId) {
            mUserId = userId;
            mFutureTask = new FutureTask<>(this);
        }

        private void execute() {
            new Thread(mFutureTask).start();
        }

        private ArrayMap<ComponentName, PersistableLaunchParams> get() {
            try {
                return mFutureTask.get();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                Slog.e(TAG, "Failed to load launch params for user#" + mUserId, e);
                return null;
            }
        }

        @Override
        public ArrayMap<ComponentName, PersistableLaunchParams> call() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            final List<File> filesToDelete = new ArrayList<>();
            final File launchParamsFolder = getLaunchParamFolder(mUserId);
            if (!launchParamsFolder.isDirectory()) {
                Slog.i(TAG, "Didn't find launch param folder for user " + mUserId);
                return null;
            }

            final Set<String> packages = new ArraySet<>(mPackageList.getPackageNames());

            final File[] paramsFiles = launchParamsFolder.listFiles();
            final ArrayMap<ComponentName, PersistableLaunchParams> map =
                    new ArrayMap<>(paramsFiles.length);

            for (File paramsFile : paramsFiles) {
                if (!paramsFile.isFile()) {
                    Slog.w(TAG, paramsFile.getAbsolutePath() + " is not a file.");
                    continue;
                }
                if (!paramsFile.getName().endsWith(LAUNCH_PARAMS_FILE_SUFFIX)) {
                    Slog.w(TAG, "Unexpected params file name: " + paramsFile.getName());
                    filesToDelete.add(paramsFile);
                    continue;
                }
                String paramsFileName = paramsFile.getName();
                // Migrate all records from old separator to new separator.
                final int oldSeparatorIndex =
                        paramsFileName.indexOf(OLD_ESCAPED_COMPONENT_SEPARATOR);
                if (oldSeparatorIndex != -1) {
                    if (paramsFileName.indexOf(
                            OLD_ESCAPED_COMPONENT_SEPARATOR, oldSeparatorIndex + 1) != -1) {
                        // Rare case. We have more than one old escaped component separator probably
                        // because this app uses underscore in their package name. We can't
                        // distinguish which one is the real separator so let's skip it.
                        filesToDelete.add(paramsFile);
                        continue;
                    }
                    paramsFileName = paramsFileName.replace(
                            OLD_ESCAPED_COMPONENT_SEPARATOR, ESCAPED_COMPONENT_SEPARATOR);
                    final File newFile = new File(launchParamsFolder, paramsFileName);
                    if (paramsFile.renameTo(newFile)) {
                        paramsFile = newFile;
                    } else {
                        // Rare case. For some reason we can't rename the file. Let's drop this
                        // record instead.
                        filesToDelete.add(paramsFile);
                        continue;
                    }
                }
                final String componentNameString = paramsFileName.substring(
                                0 /* beginIndex */,
                                paramsFileName.length() - LAUNCH_PARAMS_FILE_SUFFIX.length())
                        .replace(ESCAPED_COMPONENT_SEPARATOR, ORIGINAL_COMPONENT_SEPARATOR);
                final ComponentName name = ComponentName.unflattenFromString(
                        componentNameString);
                if (name == null) {
                    Slog.w(TAG, "Unexpected file name: " + paramsFileName);
                    filesToDelete.add(paramsFile);
                    continue;
                }

                if (!packages.contains(name.getPackageName())) {
                    // Rare case. PersisterQueue doesn't have a chance to remove files for removed
                    // packages last time.
                    filesToDelete.add(paramsFile);
                    continue;
                }

                try (InputStream in = new FileInputStream(paramsFile)) {
                    final PersistableLaunchParams params = new PersistableLaunchParams();
                    final TypedXmlPullParser parser = Xml.resolvePullParser(in);
                    int event;
                    while ((event = parser.next()) != XmlPullParser.END_DOCUMENT
                            && event != XmlPullParser.END_TAG) {
                        if (event != XmlPullParser.START_TAG) {
                            continue;
                        }

                        final String tagName = parser.getName();
                        if (!TAG_LAUNCH_PARAMS.equals(tagName)) {
                            Slog.w(TAG, "Unexpected tag name: " + tagName);
                            continue;
                        }

                        params.restore(paramsFile, parser);
                    }

                    map.put(name, params);
                    addComponentNameToLaunchParamAffinityMapIfNotNull(
                            name, params.mWindowLayoutAffinity);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to restore launch params for " + name, e);
                    filesToDelete.add(paramsFile);
                }
            }

            if (!filesToDelete.isEmpty()) {
                mPersisterQueue.addItem(new CleanUpComponentQueueItem(filesToDelete), true);
            }
            return map;
        }
    }

    private class LaunchParamsWriteQueueItem
            implements PersisterQueue.WriteQueueItem<LaunchParamsWriteQueueItem> {
        private final int mUserId;
        private final ComponentName mComponentName;

        private PersistableLaunchParams mLaunchParams;

        private LaunchParamsWriteQueueItem(int userId, ComponentName componentName,
                PersistableLaunchParams launchParams) {
            mUserId = userId;
            mComponentName = componentName;
            mLaunchParams = launchParams;
        }

        private byte[] saveParamsToXml() {
            try {
                final ByteArrayOutputStream os = new ByteArrayOutputStream();
                final TypedXmlSerializer serializer = Xml.resolveSerializer(os);

                serializer.startDocument(/* encoding */ null, /* standalone */ true);
                serializer.startTag(null, TAG_LAUNCH_PARAMS);

                mLaunchParams.saveToXml(serializer);

                serializer.endTag(null, TAG_LAUNCH_PARAMS);
                serializer.endDocument();
                serializer.flush();

                return os.toByteArray();
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public void process() {
            final byte[] data = saveParamsToXml();

            final File launchParamFolder = getLaunchParamFolder(mUserId);
            if (!launchParamFolder.isDirectory() && !launchParamFolder.mkdir()) {
                Slog.w(TAG, "Failed to create folder for " + mUserId);
                return;
            }

            final File launchParamFile = getParamFile(launchParamFolder, mComponentName);
            final AtomicFile atomicFile = new AtomicFile(launchParamFile);

            FileOutputStream stream = null;
            try {
                stream = atomicFile.startWrite();
                stream.write(data);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to write param file for " + mComponentName, e);
                if (stream != null) {
                    atomicFile.failWrite(stream);
                }
                return;
            }
            atomicFile.finishWrite(stream);
        }

        @Override
        public boolean matches(LaunchParamsWriteQueueItem item) {
            return mUserId == item.mUserId && mComponentName.equals(item.mComponentName);
        }

        @Override
        public void updateFrom(LaunchParamsWriteQueueItem item) {
            mLaunchParams = item.mLaunchParams;
        }
    }

    private static class CleanUpComponentQueueItem implements PersisterQueue.WriteQueueItem {
        private final List<File> mComponentFiles;

        private CleanUpComponentQueueItem(List<File> componentFiles) {
            mComponentFiles = componentFiles;
        }

        @Override
        public void process() {
            for (File file : mComponentFiles) {
                if (!file.delete()) {
                    Slog.w(TAG, "Failed to delete " + file.getAbsolutePath());
                }
            }
        }
    }

    private static class PersistableLaunchParams {
        private static final String ATTR_WINDOWING_MODE = "windowing_mode";
        private static final String ATTR_DISPLAY_UNIQUE_ID = "display_unique_id";
        private static final String ATTR_BOUNDS = "bounds";
        private static final String ATTR_WINDOW_LAYOUT_AFFINITY = "window_layout_affinity";

        /** The bounds within the parent container. */
        final Rect mBounds = new Rect();

        /** The unique id of the display the {@link Task} would prefer to be on. */
        String mDisplayUniqueId;

        /** The windowing mode to be in. */
        int mWindowingMode;

        /**
         * Last {@link android.content.pm.ActivityInfo.WindowLayout#windowLayoutAffinity} of the
         * window.
         */
        @Nullable String mWindowLayoutAffinity;

        /**
         * Timestamp from {@link System#currentTimeMillis()} when this record is captured, or last
         * modified time when the record is restored from storage.
         */
        long mTimestamp;

        void saveToXml(TypedXmlSerializer serializer) throws IOException {
            serializer.attribute(null, ATTR_DISPLAY_UNIQUE_ID, mDisplayUniqueId);
            serializer.attributeInt(null, ATTR_WINDOWING_MODE, mWindowingMode);
            serializer.attribute(null, ATTR_BOUNDS, mBounds.flattenToString());
            if (mWindowLayoutAffinity != null) {
                serializer.attribute(null, ATTR_WINDOW_LAYOUT_AFFINITY, mWindowLayoutAffinity);
            }
        }

        void restore(File xmlFile, TypedXmlPullParser parser) {
            for (int i = 0; i < parser.getAttributeCount(); ++i) {
                final String attrValue = parser.getAttributeValue(i);
                switch (parser.getAttributeName(i)) {
                    case ATTR_DISPLAY_UNIQUE_ID:
                        mDisplayUniqueId = attrValue;
                        break;
                    case ATTR_WINDOWING_MODE:
                        mWindowingMode = Integer.parseInt(attrValue);
                        break;
                    case ATTR_BOUNDS: {
                        final Rect bounds = Rect.unflattenFromString(attrValue);
                        if (bounds != null) {
                            mBounds.set(bounds);
                        }
                        break;
                    }
                    case ATTR_WINDOW_LAYOUT_AFFINITY:
                        mWindowLayoutAffinity = attrValue;
                        break;
                }
            }

            // The modified time could be a few seconds later than the timestamp when the record is
            // captured, which is a good enough estimate to the capture time after a reboot or a
            // user switch.
            mTimestamp = xmlFile.lastModified();
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder("PersistableLaunchParams{");
            builder.append(" windowingMode=" + mWindowingMode);
            builder.append(" displayUniqueId=" + mDisplayUniqueId);
            builder.append(" bounds=" + mBounds);
            if (mWindowLayoutAffinity != null) {
                builder.append(" launchParamsAffinity=" + mWindowLayoutAffinity);
            }
            builder.append(" timestamp=" + mTimestamp);
            builder.append(" }");
            return builder.toString();
        }
    }
}
