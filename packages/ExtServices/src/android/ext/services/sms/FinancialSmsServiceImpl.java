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
package android.ext.services.sms;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.database.Cursor;
import android.database.CursorWindow;
import android.net.Uri;
import android.os.Bundle;
import android.service.sms.FinancialSmsService;
import android.util.Log;

import java.util.ArrayList;
/**
 * Service to provide financial apps access to sms messages.
 */
public class FinancialSmsServiceImpl extends FinancialSmsService {

    private static final String TAG = "FinancialSmsServiceImpl";
    private static final String KEY_COLUMN_NAMES = "column_names";

    @Nullable
    @Override
    public CursorWindow onGetSmsMessages(@NonNull Bundle params) {
        ArrayList<String> columnNames = params.getStringArrayList(KEY_COLUMN_NAMES);
        if (columnNames == null || columnNames.size() <= 0) {
            return null;
        }

        Uri inbox = Uri.parse("content://sms/inbox");

        try (Cursor cursor = getContentResolver().query(inbox, null, null, null, null);
                CursorWindow window = new CursorWindow("FinancialSmsMessages")) {
            int messageCount = cursor.getCount();
            if (messageCount > 0 && cursor.moveToFirst()) {
                window.setNumColumns(columnNames.size());
                for (int row = 0; row < messageCount; row++) {
                    if (!window.allocRow()) {
                        Log.e(TAG, "CursorWindow ran out of memory.");
                        return null;
                    }
                    for (int col = 0; col < columnNames.size(); col++) {
                        String columnName = columnNames.get(col);
                        int inboxColumnIndex = cursor.getColumnIndexOrThrow(columnName);
                        String inboxColumnValue = cursor.getString(inboxColumnIndex);
                        boolean addedToCursorWindow = window.putString(inboxColumnValue, row, col);
                        if (!addedToCursorWindow) {
                            Log.e(TAG, "Failed to add:"
                                    + inboxColumnValue
                                    + ";column:"
                                    + columnName);
                            return null;
                        }
                    }
                    cursor.moveToNext();
                }
            } else {
                Log.w(TAG, "No sms messages.");
            }
            return window;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get sms messages.");
            return null;
        }
    }
}
