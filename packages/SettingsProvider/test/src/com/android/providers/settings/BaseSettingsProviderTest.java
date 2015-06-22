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

package com.android.providers.settings;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.UserInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.test.AndroidTestCase;

import java.util.List;

/**
 * Base class for the SettingContentProvider tests.
 */
abstract class BaseSettingsProviderTest extends AndroidTestCase {
    protected static final int SETTING_TYPE_GLOBAL = 1;
    protected static final int SETTING_TYPE_SECURE = 2;
    protected static final int SETTING_TYPE_SYSTEM = 3;

    protected static final String FAKE_SETTING_NAME = "fake_setting_name";
    protected static final String FAKE_SETTING_NAME_1 = "fake_setting_name1";
    protected static final String FAKE_SETTING_NAME_2 = "fake_setting_name2";
    protected static final String FAKE_SETTING_VALUE = "fake_setting_value";
    protected static final String FAKE_SETTING_VALUE_1 = SettingsStateTest.CRAZY_STRING;
    protected static final String FAKE_SETTING_VALUE_2 = null;

    private static final String[] NAME_VALUE_COLUMNS = new String[] {
            Settings.NameValueTable.NAME, Settings.NameValueTable.VALUE
    };

    protected int mSecondaryUserId = UserHandle.USER_OWNER;

    @Override
    public void setContext(Context context) {
        super.setContext(context);

        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        List<UserInfo> users = userManager.getUsers();
        final int userCount = users.size();
        for (int i = 0; i < userCount; i++) {
            UserInfo user = users.get(i);
            if (!user.isPrimary() && !user.isManagedProfile()) {
                mSecondaryUserId = user.id;
                break;
            }
        }
    }

    protected void setStringViaFrontEndApiSetting(int type, String name, String value, int userId) {
        ContentResolver contentResolver = getContext().getContentResolver();

        switch (type) {
            case SETTING_TYPE_GLOBAL: {
                Settings.Global.putStringForUser(contentResolver, name, value, userId);
            } break;

            case SETTING_TYPE_SECURE: {
                Settings.Secure.putStringForUser(contentResolver, name, value, userId);
            } break;

            case SETTING_TYPE_SYSTEM: {
                Settings.System.putStringForUser(contentResolver, name, value, userId);
            } break;

            default: {
                throw new IllegalArgumentException("Invalid type: " + type);
            }
        }
    }

    protected String getStringViaFrontEndApiSetting(int type, String name, int userId) {
        ContentResolver contentResolver = getContext().getContentResolver();

        switch (type) {
            case SETTING_TYPE_GLOBAL: {
                return Settings.Global.getStringForUser(contentResolver, name, userId);
            }

            case SETTING_TYPE_SECURE: {
                return Settings.Secure.getStringForUser(contentResolver, name, userId);
            }

            case SETTING_TYPE_SYSTEM: {
                return Settings.System.getStringForUser(contentResolver, name, userId);
            }

            default: {
                throw new IllegalArgumentException("Invalid type: " + type);
            }
        }
    }

    protected Uri insertStringViaProviderApi(int type, String name, String value,
            boolean withTableRowUri) {
        Uri uri = getBaseUriForType(type);
        if (withTableRowUri) {
            uri = Uri.withAppendedPath(uri, name);
        }
        ContentValues values = new ContentValues();
        values.put(Settings.NameValueTable.NAME, name);
        values.put(Settings.NameValueTable.VALUE, value);

        return getContext().getContentResolver().insert(uri, values);
    }

    protected int deleteStringViaProviderApi(int type, String name) {
        Uri uri = getBaseUriForType(type);
        return getContext().getContentResolver().delete(uri, "name=?", new String[]{name});
    }

    protected int updateStringViaProviderApiSetting(int type, String name, String value) {
        Uri uri = getBaseUriForType(type);
        ContentValues values = new ContentValues();
        values.put(Settings.NameValueTable.NAME, name);
        values.put(Settings.NameValueTable.VALUE, value);
        return getContext().getContentResolver().update(uri, values, "name=?",
                new String[]{name});
    }

    protected String queryStringViaProviderApi(int type, String name) {
        return queryStringViaProviderApi(type, name, false, false);
    }

    protected String queryStringViaProviderApi(int type, String name, boolean queryStringInQuotes,
            boolean appendNameToUri) {
        final Uri uri;
        final String queryString;
        final String[] queryArgs;

        if (appendNameToUri) {
            uri = Uri.withAppendedPath(getBaseUriForType(type), name);
            queryString = null;
            queryArgs = null;
        } else {
            uri = getBaseUriForType(type);
            queryString = queryStringInQuotes ? "(name=?)" : "name=?";
            queryArgs = new String[]{name};
        }

        Cursor cursor = getContext().getContentResolver().query(uri, NAME_VALUE_COLUMNS,
                queryString, queryArgs, null);

        if (cursor == null) {
            return null;
        }

        try {
            if (cursor.moveToFirst()) {
                final int valueColumnIdx = cursor.getColumnIndex(Settings.NameValueTable.VALUE);
                return cursor.getString(valueColumnIdx);
            }
        } finally {
            cursor.close();
        }

        return null;
    }

    protected static Uri getBaseUriForType(int type) {
        switch (type) {
            case SETTING_TYPE_GLOBAL: {
                return Settings.Global.CONTENT_URI;
            }

            case SETTING_TYPE_SECURE: {
                return Settings.Secure.CONTENT_URI;
            }

            case SETTING_TYPE_SYSTEM: {
                return Settings.System.CONTENT_URI;
            }

            default: {
                throw new IllegalArgumentException("Invalid type: " + type);
            }
        }
    }
}
