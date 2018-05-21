/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.test;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.ContentProvider;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.test.mock.MockContentProvider;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * This is a class which delegates to the given context, but performs database
 * and file operations with a renamed database/file name (prefixes default
 * names with a given prefix).
 *
 * @deprecated New tests should be written using the
 * <a href="{@docRoot}tools/testing-support-library/index.html">Android Testing Support Library</a>.
 */
@Deprecated
public class RenamingDelegatingContext extends ContextWrapper {

    private Context mFileContext;
    private String mFilePrefix = null;
    private File mCacheDir;
    private final Object mSync = new Object();

    private Set<String> mDatabaseNames = new HashSet<>();
    private Set<String> mFileNames = new HashSet<>();

    public static <T extends ContentProvider> T providerWithRenamedContext(
            Class<T> contentProvider, Context c, String filePrefix)
            throws IllegalAccessException, InstantiationException {
        return providerWithRenamedContext(contentProvider, c, filePrefix, false);
    }

    public static <T extends ContentProvider> T providerWithRenamedContext(
            Class<T> contentProvider, Context c, String filePrefix,
            boolean allowAccessToExistingFilesAndDbs)
            throws IllegalAccessException, InstantiationException {
        Class<T> mProviderClass = contentProvider;
        T mProvider = mProviderClass.newInstance();
        RenamingDelegatingContext mContext = new RenamingDelegatingContext(c, filePrefix);
        if (allowAccessToExistingFilesAndDbs) {
            mContext.makeExistingFilesAndDbsAccessible();
        }
        MockContentProvider.attachInfoForTesting(mProvider, mContext, null);
        return mProvider;
    }

    /**
     * Makes accessible all files and databases whose names match the filePrefix that was passed to
     * the constructor. Normally only files and databases that were created through this context are
     * accessible.
     */
    public void makeExistingFilesAndDbsAccessible() {
        String[] databaseList = mFileContext.databaseList();
        for (String diskName : databaseList) {
            if (shouldDiskNameBeVisible(diskName)) {
                mDatabaseNames.add(publicNameFromDiskName(diskName));
            }
        }
        String[] fileList = mFileContext.fileList();
        for (String diskName : fileList) {
            if (shouldDiskNameBeVisible(diskName)) {
                mFileNames.add(publicNameFromDiskName(diskName));
            }
        }
    }

    /**
     * Returns if the given diskName starts with the given prefix or not.
     * @param diskName name of the database/file.
     */
    boolean shouldDiskNameBeVisible(String diskName) {
        return diskName.startsWith(mFilePrefix);
    }

    /**
     * Returns the public name (everything following the prefix) of the given diskName.
     * @param diskName name of the database/file.
     */
    String publicNameFromDiskName(String diskName) {
        if (!shouldDiskNameBeVisible(diskName)) {
            throw new IllegalArgumentException("disk file should not be visible: " + diskName);
        }
        return diskName.substring(mFilePrefix.length(), diskName.length());
    }

    /**
     * @param context : the context that will be delegated.
     * @param filePrefix : a prefix with which database and file names will be
     * prefixed.
     */
    public RenamingDelegatingContext(Context context, String filePrefix) {
        super(context);
        mFileContext = context;
        mFilePrefix = filePrefix;
    }

    /**
     * @param context : the context that will be delegated.
     * @param fileContext : the context that file and db methods will be delegated to
     * @param filePrefix : a prefix with which database and file names will be
     * prefixed.
     */
    public RenamingDelegatingContext(Context context, Context fileContext, String filePrefix) {
        super(context);
        mFileContext = fileContext;
        mFilePrefix = filePrefix;
    }

    public String getDatabasePrefix() {
        return mFilePrefix;
    }

    private String renamedFileName(String name) {
        return mFilePrefix + name;
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name,
            int mode, SQLiteDatabase.CursorFactory factory) {
        final String internalName = renamedFileName(name);
        if (!mDatabaseNames.contains(name)) {
            mDatabaseNames.add(name);
            mFileContext.deleteDatabase(internalName);
        }
        return mFileContext.openOrCreateDatabase(internalName, mode, factory);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name,
            int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
        final String internalName = renamedFileName(name);
        if (!mDatabaseNames.contains(name)) {
            mDatabaseNames.add(name);
            mFileContext.deleteDatabase(internalName);
        }
        return mFileContext.openOrCreateDatabase(internalName, mode, factory, errorHandler);
    }

    @Override
    public boolean deleteDatabase(String name) {
        if (mDatabaseNames.contains(name)) {
            mDatabaseNames.remove(name);
            return mFileContext.deleteDatabase(renamedFileName(name));
        } else {
            return false;
        }
    }

    @Override
    public File getDatabasePath(String name) {
        return mFileContext.getDatabasePath(renamedFileName(name));
    }

    @Override
    public String[] databaseList() {
        return mDatabaseNames.toArray(new String[]{});
    }

    @Override
    public FileInputStream openFileInput(String name)
            throws FileNotFoundException {
        final String internalName = renamedFileName(name);
        if (mFileNames.contains(name)) {
            return mFileContext.openFileInput(internalName);
        } else {
            throw new FileNotFoundException(internalName);
        }
    }

    @Override
    public FileOutputStream openFileOutput(String name, int mode)
            throws FileNotFoundException {
        mFileNames.add(name);
        return mFileContext.openFileOutput(renamedFileName(name), mode);
    }

    @Override
    public File getFileStreamPath(String name) {
        return mFileContext.getFileStreamPath(renamedFileName(name));
    }

    @Override
    public boolean deleteFile(String name) {
        if (mFileNames.contains(name)) {
            mFileNames.remove(name);
            return mFileContext.deleteFile(renamedFileName(name));
        } else {
            return false;
        }
    }

    @Override
    public String[] fileList() {
        return mFileNames.toArray(new String[]{});
    }

    /**
     * In order to support calls to getCacheDir(), we create a temp cache dir (inside the real
     * one) and return it instead.  This code is basically getCacheDir(), except it uses the real
     * cache dir as the parent directory and creates a test cache dir inside that.
     */
    @Override
    public File getCacheDir() {
        synchronized (mSync) {
            if (mCacheDir == null) {
                mCacheDir = new File(mFileContext.getCacheDir(), renamedFileName("cache"));
            }
            if (!mCacheDir.exists()) {
                if(!mCacheDir.mkdirs()) {
                    Log.w("RenamingDelegatingContext", "Unable to create cache directory");
                    return null;
                }
                try {
                    // Give the directory all possible permissions.
                    Files.setPosixFilePermissions(mCacheDir.toPath(),
                            EnumSet.allOf(PosixFilePermission.class));
                } catch (IOException e) {
                    Log.e("RenamingDelegatingContext",
                            "Could not set permissions of test cacheDir", e);
                }
            }
        }
        return mCacheDir;
    }
}
