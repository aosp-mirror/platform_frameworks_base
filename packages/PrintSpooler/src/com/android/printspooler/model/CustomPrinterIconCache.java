/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.printspooler.model;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.drawable.Icon;
import android.print.PrinterId;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A fixed size cache for custom printer icons. Old icons get removed with a last recently used
 * policy.
 */
public class CustomPrinterIconCache {

    private final static String LOG_TAG = "CustomPrinterIconCache";

    /** Maximum number of icons in the cache */
    private final static int MAX_SIZE = 1024;

    /** Directory used to persist state and icons */
    private final File mCacheDirectory;

    /**
     * Create a new icon cache.
     */
    public CustomPrinterIconCache(@NonNull File cacheDirectory) {
        mCacheDirectory = new File(cacheDirectory, "icons");
        if (!mCacheDirectory.exists()) {
            mCacheDirectory.mkdir();
        }
    }

    /**
     * Return the file name to be used for the icon of a printer
     *
     * @param printerId the id of the printer
     *
     * @return The file to be used for the icon of the printer
     */
    private @Nullable File getIconFileName(@NonNull PrinterId printerId) {
        StringBuffer sb = new StringBuffer(printerId.getServiceName().getPackageName());
        sb.append("-");

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(
                    (printerId.getServiceName().getClassName() + ":" + printerId.getLocalId())
                            .getBytes("UTF-16"));
            sb.append(String.format("%#040x", new java.math.BigInteger(1, md.digest())));
        } catch (UnsupportedEncodingException|NoSuchAlgorithmException e) {
            Log.e(LOG_TAG, "Could not compute custom printer icon file name", e);
            return null;
        }

        return new File(mCacheDirectory, sb.toString());
    }

    /**
     * Get the {@link Icon} to be used as a custom icon for the printer. If not available request
     * the icon to be loaded.
     *
     * @param printerId the printer the icon belongs to
     * @return the {@link Icon} if already available or null if icon is not loaded yet
     */
    public synchronized @Nullable Icon getIcon(@NonNull PrinterId printerId) {
        Icon icon;

        File iconFile = getIconFileName(printerId);
        if (iconFile != null && iconFile.exists()) {
            try (FileInputStream is = new FileInputStream(iconFile)) {
                icon = Icon.createFromStream(is);
            } catch (IOException e) {
                icon = null;
                Log.e(LOG_TAG, "Could not read icon from " + iconFile, e);
            }

            // Touch file so that it is the not likely to be removed
            iconFile.setLastModified(System.currentTimeMillis());
        } else {
            icon = null;
        }

        return icon;
    }

    /**
     * Remove old icons so that only between numFilesToKeep and twice as many icons are left.
     *
     * @param numFilesToKeep the number of icons to keep
     */
    public void removeOldFiles(int numFilesToKeep) {
        File files[] = mCacheDirectory.listFiles();

        // To reduce the number of shrink operations, let the cache grow to twice the max size
        if (files.length > numFilesToKeep * 2) {
            SortedMap<Long, File> sortedFiles = new TreeMap<>();

            for (File f : files) {
                sortedFiles.put(f.lastModified(), f);
            }

            while (sortedFiles.size() > numFilesToKeep) {
                sortedFiles.remove(sortedFiles.firstKey());
            }
        }
    }

    /**
     * Handle that a custom icon for a printer was loaded
     *
     * @param printerId the id of the printer the icon belongs to
     * @param icon the icon that was loaded
     */
    public synchronized void onCustomPrinterIconLoaded(@NonNull PrinterId printerId,
            @Nullable Icon icon) {
        File iconFile = getIconFileName(printerId);

        if (iconFile == null) {
            return;
        }

        try (FileOutputStream os = new FileOutputStream(iconFile)) {
            icon.writeToStream(os);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Could not write icon for " + printerId + " to storage", e);
        }

        removeOldFiles(MAX_SIZE);
    }

    /**
     * Clear all persisted and non-persisted state from this cache.
     */
    public synchronized void clear() {
        for (File f : mCacheDirectory.listFiles()) {
            f.delete();
        }
    }
}
