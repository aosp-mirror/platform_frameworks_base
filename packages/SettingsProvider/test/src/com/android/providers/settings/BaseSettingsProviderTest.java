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
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import libcore.io.Streams;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Base class for the SettingContentProvider tests.
 */
@RunWith(AndroidJUnit4.class)
abstract class BaseSettingsProviderTest {
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

    private int mSecondaryUserId = Integer.MIN_VALUE;

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

    protected static void resetSettingsViaShell(int type, int resetMode) throws IOException {
        final String modeString;
        switch (resetMode) {
            case Settings.RESET_MODE_UNTRUSTED_DEFAULTS: {
                modeString = "untrusted_defaults";
            } break;

            case Settings.RESET_MODE_UNTRUSTED_CHANGES: {
                modeString = "untrusted_clear";
            } break;

            case Settings.RESET_MODE_TRUSTED_DEFAULTS: {
                modeString = "trusted_defaults";
            } break;

            default: {
                throw new IllegalArgumentException("Invalid reset mode: " + resetMode);
            }
        }

        switch (type) {
            case SETTING_TYPE_GLOBAL: {
                executeShellCommand("settings reset global " + modeString);
            } break;

            case SETTING_TYPE_SECURE: {
                executeShellCommand("settings reset secure " + modeString);
            } break;

            default: {
                throw new IllegalArgumentException("Invalid type: " + type);
            }
        }
    }

    protected static void resetToDefaultsViaShell(int type, String packageName) throws IOException {
        resetToDefaultsViaShell(type, packageName, null);
    }

    protected static void resetToDefaultsViaShell(int type, String packageName, String tag)
            throws IOException {
        switch (type) {
            case SETTING_TYPE_GLOBAL: {
                executeShellCommand("settings reset global " + packageName + " "
                        + (tag != null ? tag : ""));
            } break;

            case SETTING_TYPE_SECURE: {
                executeShellCommand("settings reset secure " + packageName + " "
                        + (tag != null ? tag : ""));
            } break;

            case SETTING_TYPE_SYSTEM: {
                executeShellCommand("settings reset system " + packageName + " "
                        + (tag != null ? tag : ""));
            } break;

            default: {
                throw new IllegalArgumentException("Invalid type: " + type);
            }
        }
    }

    protected String getSetting(int type, String name) {
        switch (type) {
            case SETTING_TYPE_GLOBAL: {
                return Settings.Global.getString(getContext().getContentResolver(), name);
            }

            case SETTING_TYPE_SECURE: {
                return Settings.Secure.getString(getContext().getContentResolver(), name);
            }

            case SETTING_TYPE_SYSTEM: {
                return Settings.System.getString(getContext().getContentResolver(), name);
            }

            default: {
                throw new IllegalArgumentException("Invalid type: " + type);
            }
        }
    }

    protected void putSetting(int type, String name, String value) {
        switch (type) {
            case SETTING_TYPE_GLOBAL: {
                Settings.Global.putString(getContext().getContentResolver(), name, value);
            } break;

            case SETTING_TYPE_SECURE: {
                Settings.Secure.putString(getContext().getContentResolver(), name, value);
            } break;

            case SETTING_TYPE_SYSTEM: {
                Settings.System.putString(getContext().getContentResolver(), name, value);
            } break;

            default: {
                throw new IllegalArgumentException("Invalid type: " + type);
            }
        }
    }

    protected static void setSettingViaShell(int type, String name, String value,
            boolean makeDefault) throws IOException {
        setSettingViaShell(type, name, value, null, makeDefault);
    }

    protected static void setSettingViaShell(int type, String name, String value,
            String token, boolean makeDefault) throws IOException {
        switch (type) {
            case SETTING_TYPE_GLOBAL: {
                executeShellCommand("settings put global " + name + " "
                        + value + (token != null ? " " + token : "")
                        + (makeDefault ? " default" : ""));

            } break;

            case SETTING_TYPE_SECURE: {
                executeShellCommand("settings put secure " + name + " "
                        + value + (token != null ? " " + token : "")
                        + (makeDefault ? " default" : ""));
            } break;

            case SETTING_TYPE_SYSTEM: {
                executeShellCommand("settings put system " + name + " "
                        + value + (token != null ? " " + token : "")
                        + (makeDefault ? " default" : ""));
            } break;

            default: {
                throw new IllegalArgumentException("Invalid type: " + type);
            }
        }
    }

    protected Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    protected int getSecondaryUserId() {
        if (mSecondaryUserId == Integer.MIN_VALUE) {
            UserManager userManager = (UserManager) getContext()
                    .getSystemService(Context.USER_SERVICE);
            List<UserInfo> users = userManager.getUsers();
            final int userCount = users.size();
            for (int i = 0; i < userCount; i++) {
                UserInfo user = users.get(i);
                if (!user.isPrimary() && !user.isManagedProfile()) {
                    mSecondaryUserId = user.id;
                    return mSecondaryUserId;
                }
            }
        }
        if (mSecondaryUserId == Integer.MIN_VALUE) {
            mSecondaryUserId =  UserHandle.USER_SYSTEM;
        }
        return mSecondaryUserId;
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

    protected static void executeShellCommand(String command) throws IOException {
        InputStream is = new FileInputStream(InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command).getFileDescriptor());
        Streams.readFully(is);
    }
}
