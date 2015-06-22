/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.tv;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContentRating;
import android.media.tv.TvInputManager;
import android.os.Environment;
import android.os.Handler;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages persistent state recorded by the TV input manager service as an XML file. This class is
 * not thread-safe thus caller must acquire lock on the data store before accessing it. File format:
 * <code>
 * &lt;tv-input-manager-state>
 *   &lt;blocked-ratings>
 *     &lt;rating string="XXXX" />
 *   &lt;/blocked-ratings>
 *   &lt;parental-control enabled="YYYY" />
 * &lt;/tv-input-manager-state>
 * </code>
 */
final class PersistentDataStore {
    private static final String TAG = "TvInputManagerService";

    private final Context mContext;

    private final Handler mHandler = new Handler();

    // The atomic file used to safely read or write the file.
    private final AtomicFile mAtomicFile;

    private final List<TvContentRating> mBlockedRatings =
            Collections.synchronizedList(new ArrayList<TvContentRating>());

    private boolean mBlockedRatingsChanged;

    private boolean mParentalControlsEnabled;

    private boolean mParentalControlsEnabledChanged;

    // True if the data has been loaded.
    private boolean mLoaded;

    public PersistentDataStore(Context context, int userId) {
        mContext = context;
        File userDir = Environment.getUserSystemDirectory(userId);
        if (!userDir.exists()) {
            if (!userDir.mkdirs()) {
                throw new IllegalStateException("User dir cannot be created: " + userDir);
            }
        }
        mAtomicFile = new AtomicFile(new File(userDir, "tv-input-manager-state.xml"));
    }

    public boolean isParentalControlsEnabled() {
        loadIfNeeded();
        return mParentalControlsEnabled;
    }

    public void setParentalControlsEnabled(boolean enabled) {
        loadIfNeeded();
        if (mParentalControlsEnabled != enabled) {
            mParentalControlsEnabled = enabled;
            mParentalControlsEnabledChanged = true;
            postSave();
        }
    }

    public boolean isRatingBlocked(TvContentRating rating) {
        loadIfNeeded();
        synchronized (mBlockedRatings) {
            for (TvContentRating blockedRating : mBlockedRatings) {
                if (rating.contains(blockedRating)) {
                    return true;
                }
            }
        }
        return false;
    }

    public TvContentRating[] getBlockedRatings() {
        loadIfNeeded();
        return mBlockedRatings.toArray(new TvContentRating[mBlockedRatings.size()]);
    }

    public void addBlockedRating(TvContentRating rating) {
        loadIfNeeded();
        if (rating != null && !mBlockedRatings.contains(rating)) {
            mBlockedRatings.add(rating);
            mBlockedRatingsChanged = true;
            postSave();
        }
    }

    public void removeBlockedRating(TvContentRating rating) {
        loadIfNeeded();
        if (rating != null && mBlockedRatings.contains(rating)) {
            mBlockedRatings.remove(rating);
            mBlockedRatingsChanged = true;
            postSave();
        }
    }

    private void loadIfNeeded() {
        if (!mLoaded) {
            load();
            mLoaded = true;
        }
    }

    private void clearState() {
        mBlockedRatings.clear();
        mParentalControlsEnabled = false;
    }

    private void load() {
        clearState();

        final InputStream is;
        try {
            is = mAtomicFile.openRead();
        } catch (FileNotFoundException ex) {
            return;
        }

        XmlPullParser parser;
        try {
            parser = Xml.newPullParser();
            parser.setInput(new BufferedInputStream(is), StandardCharsets.UTF_8.name());
            loadFromXml(parser);
        } catch (IOException | XmlPullParserException ex) {
            Slog.w(TAG, "Failed to load tv input manager persistent store data.", ex);
            clearState();
        } finally {
            IoUtils.closeQuietly(is);
        }
    }

    private void postSave() {
        mHandler.removeCallbacks(mSaveRunnable);
        mHandler.post(mSaveRunnable);
    }

    /**
     * Runnable posted when the state needs to be saved. This is used to prevent unnecessary file
     * operations when multiple settings change in rapid succession.
     */
    private final Runnable mSaveRunnable = new Runnable() {
        @Override
        public void run() {
            save();
        }
    };

    private void save() {
        final FileOutputStream os;
        try {
            os = mAtomicFile.startWrite();
            boolean success = false;
            try {
                XmlSerializer serializer = new FastXmlSerializer();
                serializer.setOutput(new BufferedOutputStream(os), StandardCharsets.UTF_8.name());
                saveToXml(serializer);
                serializer.flush();
                success = true;
            } finally {
                if (success) {
                    mAtomicFile.finishWrite(os);
                    broadcastChangesIfNeeded();
                } else {
                    mAtomicFile.failWrite(os);
                }
            }
        } catch (IOException ex) {
            Slog.w(TAG, "Failed to save tv input manager persistent store data.", ex);
        }
    }

    private void broadcastChangesIfNeeded() {
        if (mParentalControlsEnabledChanged) {
            mParentalControlsEnabledChanged = false;
            mContext.sendBroadcastAsUser(new Intent(
                    TvInputManager.ACTION_PARENTAL_CONTROLS_ENABLED_CHANGED), UserHandle.ALL);
        }
        if (mBlockedRatingsChanged) {
            mBlockedRatingsChanged = false;
            mContext.sendBroadcastAsUser(new Intent(TvInputManager.ACTION_BLOCKED_RATINGS_CHANGED),
                    UserHandle.ALL);
        }
    }

    private static final String TAG_TV_INPUT_MANAGER_STATE = "tv-input-manager-state";
    private static final String TAG_BLOCKED_RATINGS = "blocked-ratings";
    private static final String TAG_RATING = "rating";
    private static final String TAG_PARENTAL_CONTROLS = "parental-controls";
    private static final String ATTR_STRING = "string";
    private static final String ATTR_ENABLED = "enabled";

    private void loadFromXml(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        XmlUtils.beginDocument(parser, TAG_TV_INPUT_MANAGER_STATE);
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals(TAG_BLOCKED_RATINGS)) {
                loadBlockedRatingsFromXml(parser);
            } else if (parser.getName().equals(TAG_PARENTAL_CONTROLS)) {
                String enabled = parser.getAttributeValue(null, ATTR_ENABLED);
                if (TextUtils.isEmpty(enabled)) {
                    throw new XmlPullParserException(
                            "Missing " + ATTR_ENABLED + " attribute on " + TAG_PARENTAL_CONTROLS);
                }
                mParentalControlsEnabled = Boolean.valueOf(enabled);
            }
        }
    }

    private void loadBlockedRatingsFromXml(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            if (parser.getName().equals(TAG_RATING)) {
                String ratingString = parser.getAttributeValue(null, ATTR_STRING);
                if (TextUtils.isEmpty(ratingString)) {
                    throw new XmlPullParserException(
                            "Missing " + ATTR_STRING + " attribute on " + TAG_RATING);
                }
                mBlockedRatings.add(TvContentRating.unflattenFromString(ratingString));
            }
        }
    }

    private void saveToXml(XmlSerializer serializer) throws IOException {
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startTag(null, TAG_TV_INPUT_MANAGER_STATE);
        serializer.startTag(null, TAG_BLOCKED_RATINGS);
        synchronized (mBlockedRatings) {
            for (TvContentRating rating : mBlockedRatings) {
                serializer.startTag(null, TAG_RATING);
                serializer.attribute(null, ATTR_STRING, rating.flattenToString());
                serializer.endTag(null, TAG_RATING);
            }
        }
        serializer.endTag(null, TAG_BLOCKED_RATINGS);
        serializer.startTag(null, TAG_PARENTAL_CONTROLS);
        serializer.attribute(null, ATTR_ENABLED, Boolean.toString(mParentalControlsEnabled));
        serializer.endTag(null, TAG_PARENTAL_CONTROLS);
        serializer.endTag(null, TAG_TV_INPUT_MANAGER_STATE);
        serializer.endDocument();
    }
}
