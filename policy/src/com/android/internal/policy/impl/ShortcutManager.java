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

package com.android.internal.policy.impl;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.net.URISyntaxException;

/**
 * Manages quick launch shortcuts by:
 * <li> Keeping the local copy in sync with the database (this is an observer)
 * <li> Returning a shortcut-matching intent to clients
 */
class ShortcutManager extends ContentObserver {
    
    private static final String TAG = "ShortcutManager";
    
    private static final int COLUMN_SHORTCUT = 0;
    private static final int COLUMN_INTENT = 1;
    private static final String[] sProjection = new String[] {
        Settings.Bookmarks.SHORTCUT, Settings.Bookmarks.INTENT
    };

    private Context mContext;
    private Cursor mCursor;
    /** Map of a shortcut to its intent. */
    private SparseArray<Intent> mShortcutIntents;
    
    public ShortcutManager(Context context, Handler handler) {
        super(handler);
        
        mContext = context;
        mShortcutIntents = new SparseArray<Intent>();
    }

    /** Observes the provider of shortcut+intents */
    public void observe() {
        mCursor = mContext.getContentResolver().query(
                Settings.Bookmarks.CONTENT_URI, sProjection, null, null, null);
        mCursor.registerContentObserver(this);
        updateShortcuts();
    }

    @Override
    public void onChange(boolean selfChange) {
        updateShortcuts();
    }
    
    private void updateShortcuts() {
        Cursor c = mCursor;
        if (!c.requery()) {
            Log.e(TAG, "ShortcutObserver could not re-query shortcuts.");
            return;
        }

        mShortcutIntents.clear();
        while (c.moveToNext()) {
            int shortcut = c.getInt(COLUMN_SHORTCUT);
            if (shortcut == 0) continue;
            String intentURI = c.getString(COLUMN_INTENT);
            Intent intent = null;
            try {
                intent = Intent.getIntent(intentURI);
            } catch (URISyntaxException e) {
                Log.w(TAG, "Intent URI for shortcut invalid.", e);
            }
            if (intent == null) continue;
            mShortcutIntents.put(shortcut, intent);
        }
    }
    
    /**
     * Gets the shortcut intent for a given keycode+modifier. Make sure you
     * strip whatever modifier is used for invoking shortcuts (for example,
     * if 'Sym+A' should invoke a shortcut on 'A', you should strip the
     * 'Sym' bit from the modifiers before calling this method.
     * <p>
     * This will first try an exact match (with modifiers), and then try a
     * match without modifiers (primary character on a key).
     * 
     * @param kcm The key character map of the device on which the key was pressed.
     * @param keyCode The key code.
     * @param metaState The meta state, omitting any modifiers that were used
     * to invoke the shortcut.
     * @return The intent that matches the shortcut, or null if not found.
     */
    public Intent getIntent(KeyCharacterMap kcm, int keyCode, int metaState) {
        Intent intent = null;

        // First try the exact keycode (with modifiers).
        int shortcut = kcm.get(keyCode, metaState);
        if (shortcut != 0) {
            intent = mShortcutIntents.get(shortcut);
        }

        // Next try the primary character on that key.
        if (intent == null) {
            shortcut = Character.toLowerCase(kcm.getDisplayLabel(keyCode));
            if (shortcut != 0) {
                intent = mShortcutIntents.get(shortcut);
            }
        }

        return intent;
    }

}
