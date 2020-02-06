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
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This provider is a bridge for client apps to provide the schedule data.
 * Client provider needs to implement their {@link #getScheduleInfoList()} and returns a list of
 * {@link ScheduleInfo}.
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
    public final Cursor query(
            Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
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
     * Return the list of the schedule information.
     *
     * @return a list of the {@link ScheduleInfo}.
     */
    public abstract ArrayList<ScheduleInfo> getScheduleInfoList();

    /**
     * Returns a bundle which contains a list of {@link ScheduleInfo} and data types:
     * scheduleInfoList : ArrayList<ScheduleInfo>
     */
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
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
     * To filter the invalid schedule info.
     *
     * @param scheduleInfoList The list of the {@link ScheduleInfo}.
     * @return The valid list of the {@link ScheduleInfo}.
     */
    private ArrayList<ScheduleInfo> filterInvalidData(ArrayList<ScheduleInfo> scheduleInfoList) {
        if (scheduleInfoList == null) {
            Log.d(TAG, "package : " + getContext().getPackageName() + " has no scheduling data.");
            return null;
        }
        // Dump invalid data in debug mode.
        if (SystemProperties.getInt("ro.debuggable", 0) == 1) {
            new Thread(() -> {
                dumpInvalidData(scheduleInfoList);
            }).start();
        }
        final List<ScheduleInfo> filteredList = scheduleInfoList
                .stream()
                .filter(scheduleInfo -> scheduleInfo.isValid())
                .collect(Collectors.toList());

        return new ArrayList<>(filteredList);
    }

    private void dumpInvalidData(ArrayList<ScheduleInfo> scheduleInfoList) {
        Log.d(TAG, "package : " + getContext().getPackageName()
                + " provided some scheduling data are invalid.");
        scheduleInfoList
                .stream()
                .filter(scheduleInfo -> !scheduleInfo.isValid())
                .forEach(scheduleInfo -> Log.d(TAG, scheduleInfo.toString()));
    }
}
