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

package com.android.server.am;

import android.annotation.NonNull;
import android.content.pm.VersionedPackage;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.procstats.ProcessStats;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * List of packages running in the process, self locked.
 */
final class PackageList {
    private final ProcessRecord mProcess;

    private final ArrayMap<String, ProcessStats.ProcessStateHolder> mPkgList = new ArrayMap<>();

    PackageList(final ProcessRecord app) {
        mProcess = app;
    }

    ProcessStats.ProcessStateHolder put(String key, ProcessStats.ProcessStateHolder value) {
        synchronized (this) {
            mProcess.getWindowProcessController().addPackage(key);
            return mPkgList.put(key, value);
        }
    }

    void clear() {
        synchronized (this) {
            mPkgList.clear();
            mProcess.getWindowProcessController().clearPackageList();
        }
    }

    int size() {
        synchronized (this) {
            return mPkgList.size();
        }
    }

    boolean containsKey(Object key) {
        synchronized (this) {
            return mPkgList.containsKey(key);
        }
    }

    ProcessStats.ProcessStateHolder get(String pkgName) {
        synchronized (this) {
            return mPkgList.get(pkgName);
        }
    }

    void forEachPackage(@NonNull Consumer<String> callback) {
        synchronized (this) {
            for (int i = 0, size = mPkgList.size(); i < size; i++) {
                callback.accept(mPkgList.keyAt(i));
            }
        }
    }

    void forEachPackage(@NonNull BiConsumer<String, ProcessStats.ProcessStateHolder> callback) {
        synchronized (this) {
            for (int i = 0, size = mPkgList.size(); i < size; i++) {
                callback.accept(mPkgList.keyAt(i), mPkgList.valueAt(i));
            }
        }
    }

    /**
     * Search in the package list, invoke the given {@code callback} with each of the package names
     * in that list; if the callback returns a non-null object, halt the search, return that
     * object as the return value of this search function.
     *
     * @param callback The callback interface to accept the current package name; if it returns
     *                 a non-null object, the search will be halted and this object will be used
     *                 as the return value of this search function.
     */
    <R> R searchEachPackage(@NonNull Function<String, R> callback) {
        synchronized (this) {
            for (int i = 0, size = mPkgList.size(); i < size; i++) {
                R r = callback.apply(mPkgList.keyAt(i));
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    void forEachPackageProcessStats(@NonNull Consumer<ProcessStats.ProcessStateHolder> callback) {
        synchronized (this) {
            for (int i = 0, size = mPkgList.size(); i < size; i++) {
                callback.accept(mPkgList.valueAt(i));
            }
        }
    }

    @GuardedBy("this")
    ArrayMap<String, ProcessStats.ProcessStateHolder> getPackageListLocked() {
        return mPkgList;
    }

    String[] getPackageList() {
        synchronized (this) {
            int size = mPkgList.size();
            if (size == 0) {
                return null;
            }
            final String[] list = new String[size];
            for (int i = 0; i < size; i++) {
                list[i] = mPkgList.keyAt(i);
            }
            return list;
        }
    }

    List<VersionedPackage> getPackageListWithVersionCode() {
        synchronized (this) {
            int size = mPkgList.size();
            if (size == 0) {
                return null;
            }
            List<VersionedPackage> list = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                list.add(new VersionedPackage(mPkgList.keyAt(i), mPkgList.valueAt(i).appVersion));
            }
            return list;
        }
    }

    void dump(PrintWriter pw, String prefix) {
        synchronized (this) {
            pw.print(prefix); pw.print("packageList={");
            for (int i = 0, size = mPkgList.size(); i < size; i++) {
                if (i > 0) pw.print(", ");
                pw.print(mPkgList.keyAt(i));
            }
            pw.println("}");
        }
    }
}
