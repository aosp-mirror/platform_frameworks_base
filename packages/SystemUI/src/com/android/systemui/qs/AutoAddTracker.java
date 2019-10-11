/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static com.android.systemui.statusbar.phone.AutoTileManager.HOTSPOT;
import static com.android.systemui.statusbar.phone.AutoTileManager.INVERSION;
import static com.android.systemui.statusbar.phone.AutoTileManager.NIGHT;
import static com.android.systemui.statusbar.phone.AutoTileManager.SAVER;
import static com.android.systemui.statusbar.phone.AutoTileManager.WORK;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Prefs;
import com.android.systemui.Prefs.Key;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import javax.inject.Inject;

public class AutoAddTracker {

    private static final String[][] CONVERT_PREFS = {
            {Key.QS_HOTSPOT_ADDED, HOTSPOT},
            {Key.QS_DATA_SAVER_ADDED, SAVER},
            {Key.QS_INVERT_COLORS_ADDED, INVERSION},
            {Key.QS_WORK_ADDED, WORK},
            {Key.QS_NIGHTDISPLAY_ADDED, NIGHT},
    };

    private final ArraySet<String> mAutoAdded;
    private final Context mContext;

    @Inject
    public AutoAddTracker(Context context) {
        mContext = context;
        mAutoAdded = new ArraySet<>(getAdded());
        // TODO: remove migration code and shared preferences keys after P release
        for (String[] convertPref : CONVERT_PREFS) {
            if (Prefs.getBoolean(context, convertPref[0], false)) {
                setTileAdded(convertPref[1]);
                Prefs.remove(context, convertPref[0]);
            }
        }
        mContext.getContentResolver().registerContentObserver(
                Secure.getUriFor(Secure.QS_AUTO_ADDED_TILES), false, mObserver);
    }

    public boolean isAdded(String tile) {
        return mAutoAdded.contains(tile);
    }

    public void setTileAdded(String tile) {
        if (mAutoAdded.add(tile)) {
            saveTiles();
        }
    }

    public void setTileRemoved(String tile) {
        if (mAutoAdded.remove(tile)) {
            saveTiles();
        }
    }

    public void destroy() {
        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    private void saveTiles() {
        Secure.putString(mContext.getContentResolver(), Secure.QS_AUTO_ADDED_TILES,
                TextUtils.join(",", mAutoAdded));
    }

    private Collection<String> getAdded() {
        String current = Secure.getString(mContext.getContentResolver(), Secure.QS_AUTO_ADDED_TILES);
        if (current == null) {
            return Collections.emptyList();
        }
        return Arrays.asList(current.split(","));
    }

    @VisibleForTesting
    protected final ContentObserver mObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            mAutoAdded.addAll(getAdded());
        }
    };
}
