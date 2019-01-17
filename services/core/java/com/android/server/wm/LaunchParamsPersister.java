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

import android.content.ComponentName;
import android.content.pm.PackageList;
import android.content.pm.PackageManagerInternal;
import android.graphics.Rect;
import android.os.Environment;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.LocalServices;
import com.android.server.wm.LaunchParamsController.LaunchParams;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Persister that saves launch parameters in memory and in storage. It saves the last seen state of
 * tasks key-ed on task's user ID and the activity used to launch the task ({@link
 * TaskRecord#realActivity}) and that's used to determine the launch params when the activity is
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
    private static final char ESCAPED_COMPONENT_SEPARATOR = '_';

    private static final String TAG_LAUNCH_PARAMS = "launch_params";

    private final PersisterQueue mPersisterQueue;
    private final ActivityStackSupervisor mSupervisor;

    /**
     * A function that takes in user ID and returns a folder to store information of that user. Used
     * to differentiate storage location in test environment and production environment.
     */
    private final IntFunction<File> mUserFolderGetter;

    private PackageList mPackageList;

    /**
     * A dual layer map that first maps user ID to a secondary map, which maps component name (the
     * launching activity of tasks) to {@link PersistableLaunchParams} that stores launch metadata
     * that are stable across reboots.
     */
    private final SparseArray<ArrayMap<ComponentName, PersistableLaunchParams>> mMap =
            new SparseArray<>();

    LaunchParamsPersister(PersisterQueue persisterQueue, ActivityStackSupervisor supervisor) {
        this(persisterQueue, supervisor, Environment::getDataSystemCeDirectory);
    }

    @VisibleForTesting
    LaunchParamsPersister(PersisterQueue persisterQueue, ActivityStackSupervisor supervisor,
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
        loadLaunchParams(userId);
    }

    void onCleanupUser(int userId) {
        mMap.remove(userId);
    }

    private void loadLaunchParams(int userId) {
        final List<File> filesToDelete = new ArrayList<>();
        final File launchParamsFolder = getLaunchParamFolder(userId);
        if (!launchParamsFolder.isDirectory()) {
            Slog.i(TAG, "Didn't find launch param folder for user " + userId);
            return;
        }

        final Set<String> packages = new ArraySet<>(mPackageList.getPackageNames());

        final File[] paramsFiles = launchParamsFolder.listFiles();
        final ArrayMap<ComponentName, PersistableLaunchParams> map =
                new ArrayMap<>(paramsFiles.length);
        mMap.put(userId, map);

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
            final String paramsFileName = paramsFile.getName();
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

            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(paramsFile));
                final PersistableLaunchParams params = new PersistableLaunchParams();
                final XmlPullParser parser = Xml.newPullParser();
                parser.setInput(reader);
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

                    params.restoreFromXml(parser);
                }

                map.put(name, params);
            } catch (Exception e) {
                Slog.w(TAG, "Failed to restore launch params for " + name, e);
                filesToDelete.add(paramsFile);
            } finally {
                IoUtils.closeQuietly(reader);
            }
        }

        if (!filesToDelete.isEmpty()) {
            mPersisterQueue.addItem(new CleanUpComponentQueueItem(filesToDelete), true);
        }
    }

    void saveTask(TaskRecord task) {
        final ComponentName name = task.realActivity;
        final int userId = task.userId;
        PersistableLaunchParams params;
        ArrayMap<ComponentName, PersistableLaunchParams> map = mMap.get(userId);
        if (map == null) {
            map = new ArrayMap<>();
            mMap.put(userId, map);
        }

        params = map.get(name);
        if (params == null) {
            params = new PersistableLaunchParams();
            map.put(name, params);
        }
        final boolean changed = saveTaskToLaunchParam(task, params);

        if (changed) {
            mPersisterQueue.updateLastOrAddItem(
                    new LaunchParamsWriteQueueItem(userId, name, params),
                    /* flush */ false);
        }
    }

    private boolean saveTaskToLaunchParam(TaskRecord task, PersistableLaunchParams params) {
        final ActivityStack stack = task.getStack();
        final int displayId = stack.mDisplayId;
        final ActivityDisplay display =
                mSupervisor.mRootActivityContainer.getActivityDisplay(displayId);
        final DisplayInfo info = new DisplayInfo();
        display.mDisplay.getDisplayInfo(info);

        boolean changed = !Objects.equals(params.mDisplayUniqueId, info.uniqueId);
        params.mDisplayUniqueId = info.uniqueId;

        changed |= params.mWindowingMode != stack.getWindowingMode();
        params.mWindowingMode = stack.getWindowingMode();

        if (task.mLastNonFullscreenBounds != null) {
            changed |= !Objects.equals(params.mBounds, task.mLastNonFullscreenBounds);
            params.mBounds.set(task.mLastNonFullscreenBounds);
        } else {
            changed |= !params.mBounds.isEmpty();
            params.mBounds.setEmpty();
        }

        return changed;
    }

    void getLaunchParams(TaskRecord task, ActivityRecord activity, LaunchParams outParams) {
        final ComponentName name = task != null ? task.realActivity : activity.mActivityComponent;
        final int userId = task != null ? task.userId : activity.mUserId;

        outParams.reset();
        Map<ComponentName, PersistableLaunchParams> map = mMap.get(userId);
        if (map == null) {
            return;
        }
        final PersistableLaunchParams persistableParams = map.get(name);

        if (persistableParams == null) {
            return;
        }

        final ActivityDisplay display = mSupervisor.mRootActivityContainer.getActivityDisplay(
                persistableParams.mDisplayUniqueId);
        if (display != null) {
            outParams.mPreferredDisplayId =  display.mDisplayId;
        }
        outParams.mWindowingMode = persistableParams.mWindowingMode;
        outParams.mBounds.set(persistableParams.mBounds);
    }

    void removeRecordForPackage(String packageName) {
        final List<File> fileToDelete = new ArrayList<>();
        for (int i = 0; i < mMap.size(); ++i) {
            int userId = mMap.keyAt(i);
            final File launchParamsFolder = getLaunchParamFolder(userId);
            ArrayMap<ComponentName, PersistableLaunchParams> map = mMap.valueAt(i);
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
        public void onPackageAdded(String packageName) { }

        @Override
        public void onPackageRemoved(String packageName) {
            removeRecordForPackage(packageName);
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

        private StringWriter saveParamsToXml() {
            final StringWriter writer = new StringWriter();
            final XmlSerializer serializer = new FastXmlSerializer();

            try {
                serializer.setOutput(writer);
                serializer.startDocument(/* encoding */ null, /* standalone */ true);
                serializer.startTag(null, TAG_LAUNCH_PARAMS);

                mLaunchParams.saveToXml(serializer);

                serializer.endTag(null, TAG_LAUNCH_PARAMS);
                serializer.endDocument();
                serializer.flush();

                return writer;
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public void process() {
            final StringWriter writer = saveParamsToXml();

            final File launchParamFolder = getLaunchParamFolder(mUserId);
            if (!launchParamFolder.isDirectory() && !launchParamFolder.mkdirs()) {
                Slog.w(TAG, "Failed to create folder for " + mUserId);
                return;
            }

            final File launchParamFile = getParamFile(launchParamFolder, mComponentName);
            final AtomicFile atomicFile = new AtomicFile(launchParamFile);

            FileOutputStream stream = null;
            try {
                stream = atomicFile.startWrite();
                stream.write(writer.toString().getBytes());
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

    private class CleanUpComponentQueueItem implements PersisterQueue.WriteQueueItem {
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

    private class PersistableLaunchParams {
        private static final String ATTR_WINDOWING_MODE = "windowing_mode";
        private static final String ATTR_DISPLAY_UNIQUE_ID = "display_unique_id";
        private static final String ATTR_BOUNDS = "bounds";

        /** The bounds within the parent container. */
        final Rect mBounds = new Rect();

        /** The unique id of the display the {@link TaskRecord} would prefer to be on. */
        String mDisplayUniqueId;

        /** The windowing mode to be in. */
        int mWindowingMode;

        void saveToXml(XmlSerializer serializer) throws IOException {
            serializer.attribute(null, ATTR_DISPLAY_UNIQUE_ID, mDisplayUniqueId);
            serializer.attribute(null, ATTR_WINDOWING_MODE,
                    Integer.toString(mWindowingMode));
            serializer.attribute(null, ATTR_BOUNDS, mBounds.flattenToString());
        }

        void restoreFromXml(XmlPullParser parser) {
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
                }
            }
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder("PersistableLaunchParams{");
            builder.append("windowingMode=" + mWindowingMode);
            builder.append(" displayUniqueId=" + mDisplayUniqueId);
            builder.append(" bounds=" + mBounds);
            builder.append(" }");
            return builder.toString();
        }
    }
}
