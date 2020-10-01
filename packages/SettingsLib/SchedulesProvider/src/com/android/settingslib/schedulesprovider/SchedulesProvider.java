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
package com.android.settingslib.schedulesprovider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * A bridge for client apps to provide the schedule data. Client provider needs to implement
 * {@link #getScheduleInfoList()} returning a list of {@link ScheduleInfo}.
 */
public abstract class SchedulesProvider extends ContentProvider {
    public static final String METHOD_GENERATE_SCHEDULE_INFO_LIST = "generateScheduleInfoList";
    public static final String BUNDLE_SCHEDULE_INFO_LIST = "scheduleInfoList";
    private static final String TAG = "SchedulesProvider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public final Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Query operation is not supported currently.");
    }

    @Override
    public final String getType(Uri uri) {
        throw new UnsupportedOperationException("GetType operation is not supported currently.");
    }

    @Override
    public final Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Insert operation is not supported currently.");
    }

    @Override
    public final int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Delete operation not supported currently.");
    }

    @Override
    public final int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("Update operation is not supported currently.");
    }

    /**
     * Returns the list of the schedule information.
     */
    public abstract ArrayList<ScheduleInfo> getScheduleInfoList();

    /**
     * Returns a bundle which contains a list of {@link ScheduleInfo}s:
     *
     * <ul>
     *   <li>scheduleInfoList: ArrayList<ScheduleInfo>
     * </ul>
     */
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (!TextUtils.equals(getCallingPackage(),
                getContext().getText(R.string.config_schedules_provider_caller_package))) {
            return null;
        }

        final Bundle bundle = new Bundle();
        if (METHOD_GENERATE_SCHEDULE_INFO_LIST.equals(method)) {
            final ArrayList<ScheduleInfo> scheduleInfoList = filterInvalidData(
                    getScheduleInfoList());
            if (scheduleInfoList != null) {
                bundle.putParcelableArrayList(BUNDLE_SCHEDULE_INFO_LIST, scheduleInfoList);
            }
        }
        return bundle;
    }

    /**
     * Filters our invalid schedule infos from {@code schedulesInfoList}.
     *
     * @return valid {@link SchedulesInfo}s if {@code schedulesInfoList} is not null. Otherwise,
     * null.
     */
    @Nullable
    private ArrayList<ScheduleInfo> filterInvalidData(
            @Nullable ArrayList<ScheduleInfo> scheduleInfoList) {
        if (scheduleInfoList == null) {
            Log.d(TAG, "package : " + getContext().getPackageName() + " has no scheduling data.");
            return null;
        }
        // Dump invalid data in debug mode.
        if (SystemProperties.getInt("ro.debuggable", 0) == 1) {
            dumpInvalidData(scheduleInfoList);
        }
        return scheduleInfoList
                .stream()
                .filter(ScheduleInfo::isValid)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void dumpInvalidData(ArrayList<ScheduleInfo> scheduleInfoList) {
        final boolean hasInvalidData = scheduleInfoList
                .stream()
                .anyMatch(scheduleInfo -> !scheduleInfo.isValid());

        if (hasInvalidData) {
            Log.w(TAG, "package : " + getContext().getPackageName()
                    + " provided some scheduling data that are invalid.");
            scheduleInfoList
                    .stream()
                    .filter(scheduleInfo -> !scheduleInfo.isValid())
                    .forEach(scheduleInfo -> Log.w(TAG, scheduleInfo.toString()));
        }
    }
}
