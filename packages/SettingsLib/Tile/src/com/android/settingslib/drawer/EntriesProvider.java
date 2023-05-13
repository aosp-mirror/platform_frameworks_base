/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.drawer;

import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_KEYHINT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_TITLE;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An abstract class for injecting entries to Settings.
 */
public abstract class EntriesProvider extends ContentProvider {
    private static final String TAG = "EntriesProvider";

    public static final String METHOD_GET_ENTRY_DATA = "getEntryData";
    public static final String METHOD_GET_PROVIDER_ICON = "getProviderIcon";
    public static final String METHOD_GET_DYNAMIC_TITLE = "getDynamicTitle";
    public static final String METHOD_GET_DYNAMIC_SUMMARY = "getDynamicSummary";
    public static final String METHOD_IS_CHECKED = "isChecked";
    public static final String METHOD_ON_CHECKED_CHANGED = "onCheckedChanged";

    /**
     * @deprecated use {@link #METHOD_GET_ENTRY_DATA} instead.
     */
    @Deprecated
    public static final String METHOD_GET_SWITCH_DATA = "getSwitchData";

    public static final String EXTRA_ENTRY_DATA = "entry_data";
    public static final String EXTRA_SWITCH_CHECKED_STATE = "checked_state";
    public static final String EXTRA_SWITCH_SET_CHECKED_ERROR = "set_checked_error";
    public static final String EXTRA_SWITCH_SET_CHECKED_ERROR_MESSAGE = "set_checked_error_message";

    /**
     * @deprecated use {@link #EXTRA_ENTRY_DATA} instead.
     */
    @Deprecated
    public static final String EXTRA_SWITCH_DATA = "switch_data";

    private String mAuthority;
    private final Map<String, EntryController> mControllerMap = new LinkedHashMap<>();
    private final List<Bundle> mEntryDataList = new ArrayList<>();

    /**
     * Get a list of {@link EntryController} for this provider.
     */
    protected abstract List<? extends EntryController> createEntryControllers();

    protected EntryController getController(String key) {
        return mControllerMap.get(key);
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        mAuthority = info.authority;
        Log.i(TAG, mAuthority);
        super.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        final List<? extends EntryController> controllers = createEntryControllers();
        if (controllers == null || controllers.isEmpty()) {
            throw new IllegalArgumentException();
        }

        for (EntryController controller : controllers) {
            final String key = controller.getKey();
            if (TextUtils.isEmpty(key)) {
                throw new NullPointerException("Entry key cannot be null: "
                        + controller.getClass().getSimpleName());
            } else if (mControllerMap.containsKey(key)) {
                throw new IllegalArgumentException("Entry key " + key + " is duplicated by: "
                        + controller.getClass().getSimpleName());
            }

            controller.setAuthority(mAuthority);
            mControllerMap.put(key, controller);
            if (!(controller instanceof PrimarySwitchController)) {
                mEntryDataList.add(controller.getBundle());
            }
        }
        return true;
    }

    @Override
    public Bundle call(String method, String uriString, Bundle extras) {
        final Bundle bundle = new Bundle();
        final String key = extras != null
                ? extras.getString(META_DATA_PREFERENCE_KEYHINT)
                : null;
        if (TextUtils.isEmpty(key)) {
            switch (method) {
                case METHOD_GET_ENTRY_DATA:
                    bundle.putParcelableList(EXTRA_ENTRY_DATA, mEntryDataList);
                    return bundle;
                case METHOD_GET_SWITCH_DATA:
                    bundle.putParcelableList(EXTRA_SWITCH_DATA, mEntryDataList);
                    return bundle;
                default:
                    return null;
            }
        }

        final EntryController controller = mControllerMap.get(key);
        if (controller == null) {
            return null;
        }

        switch (method) {
            case METHOD_GET_ENTRY_DATA:
            case METHOD_GET_SWITCH_DATA:
                if (!(controller instanceof PrimarySwitchController)) {
                    return controller.getBundle();
                }
                break;
            case METHOD_GET_PROVIDER_ICON:
                if (controller instanceof ProviderIcon) {
                    return ((ProviderIcon) controller).getProviderIcon();
                }
                break;
            case METHOD_GET_DYNAMIC_TITLE:
                if (controller instanceof DynamicTitle) {
                    bundle.putString(META_DATA_PREFERENCE_TITLE,
                            ((DynamicTitle) controller).getDynamicTitle());
                    return bundle;
                }
                break;
            case METHOD_GET_DYNAMIC_SUMMARY:
                if (controller instanceof DynamicSummary) {
                    bundle.putString(META_DATA_PREFERENCE_SUMMARY,
                            ((DynamicSummary) controller).getDynamicSummary());
                    return bundle;
                }
                break;
            case METHOD_IS_CHECKED:
                if (controller instanceof ProviderSwitch) {
                    bundle.putBoolean(EXTRA_SWITCH_CHECKED_STATE,
                            ((ProviderSwitch) controller).isSwitchChecked());
                    return bundle;
                }
                break;
            case METHOD_ON_CHECKED_CHANGED:
                if (controller instanceof ProviderSwitch) {
                    return onSwitchCheckedChanged(extras.getBoolean(EXTRA_SWITCH_CHECKED_STATE),
                            (ProviderSwitch) controller);
                }
                break;
        }
        return null;
    }

    private Bundle onSwitchCheckedChanged(boolean checked, ProviderSwitch controller) {
        final boolean success = controller.onSwitchCheckedChanged(checked);
        final Bundle bundle = new Bundle();
        bundle.putBoolean(EXTRA_SWITCH_SET_CHECKED_ERROR, !success);
        if (success) {
            if (controller instanceof DynamicSummary) {
                ((EntryController) controller).notifySummaryChanged(getContext());
            }
        } else {
            bundle.putString(EXTRA_SWITCH_SET_CHECKED_ERROR_MESSAGE,
                    controller.getSwitchErrorMessage(checked));
        }
        return bundle;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}

