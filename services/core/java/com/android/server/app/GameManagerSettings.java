/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.app;

import android.app.GameManager;
import android.os.FileUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Persists all GameService related settings.
 * @hide
 */
public class GameManagerSettings {

    // The XML file follows the below format:
    // <?xml>
    // <packages>
    //     <package></package>
    //     ...
    // </packages>
    private static final String GAME_SERVICE_FILE_NAME = "game-manager-service.xml";

    private static final String TAG_PACKAGE = "package";
    private static final String TAG_PACKAGES = "packages";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_GAME_MODE = "gameMode";

    private final File mSystemDir;
    @VisibleForTesting
    final AtomicFile mSettingsFile;

    // PackageName -> GameMode
    private final ArrayMap<String, Integer> mGameModes = new ArrayMap<>();

    GameManagerSettings(File dataDir) {
        mSystemDir = new File(dataDir, "system");
        mSystemDir.mkdirs();
        FileUtils.setPermissions(mSystemDir.toString(),
                FileUtils.S_IRWXU | FileUtils.S_IRWXG
                        | FileUtils.S_IROTH | FileUtils.S_IXOTH,
                -1, -1);
        mSettingsFile = new AtomicFile(new File(mSystemDir, GAME_SERVICE_FILE_NAME));
    }

    /**
     * Return the game mode of a given package.
     * This operation must be synced with an external lock.
     */
    int getGameModeLocked(String packageName) {
        if (mGameModes.containsKey(packageName)) {
            return mGameModes.get(packageName);
        }
        return GameManager.GAME_MODE_UNSUPPORTED;
    }

    /**
     * Set the game mode of a given package.
     * This operation must be synced with an external lock.
     */
    void setGameModeLocked(String packageName, int gameMode) {
        mGameModes.put(packageName, gameMode);
    }

    /**
     * Write all current game service settings into disk.
     * This operation must be synced with an external lock.
     */
    void writePersistentDataLocked() {
        FileOutputStream fstr = null;
        try {
            fstr = mSettingsFile.startWrite();

            final TypedXmlSerializer serializer = Xml.resolveSerializer(fstr);
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_PACKAGES);
            for (Map.Entry<String, Integer> entry : mGameModes.entrySet()) {
                serializer.startTag(null, TAG_PACKAGE);
                serializer.attribute(null, ATTR_NAME, entry.getKey());
                serializer.attributeInt(null, ATTR_GAME_MODE, entry.getValue());
                serializer.endTag(null, TAG_PACKAGE);
            }
            serializer.endTag(null, TAG_PACKAGES);

            serializer.endDocument();

            mSettingsFile.finishWrite(fstr);

            FileUtils.setPermissions(mSettingsFile.toString(),
                    FileUtils.S_IRUSR | FileUtils.S_IWUSR
                            | FileUtils.S_IRGRP | FileUtils.S_IWGRP,
                    -1, -1);
            return;
        } catch (java.io.IOException e) {
            mSettingsFile.failWrite(fstr);
            Slog.wtf(GameManagerService.TAG, "Unable to write game manager service settings, "
                    + "current changes will be lost at reboot", e);
        }
    }

    /**
     * Read game service settings from the disk.
     * This operation must be synced with an external lock.
     */
    boolean readPersistentDataLocked() {
        mGameModes.clear();

        if (!mSettingsFile.exists()) {
            Slog.v(GameManagerService.TAG, "Settings file doesn't exists, skip reading");
            return false;
        }

        try {
            final FileInputStream str = mSettingsFile.openRead();

            final TypedXmlPullParser parser = Xml.resolvePullParser(str);
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                // Do nothing
            }
            if (type != XmlPullParser.START_TAG) {
                Slog.wtf(GameManagerService.TAG,
                        "No start tag found in package manager settings");
                return false;
            }

            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals(TAG_PACKAGE)) {
                    readPackage(parser);
                } else {
                    Slog.w(GameManagerService.TAG, "Unknown element: " + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        } catch (XmlPullParserException | java.io.IOException e) {
            Slog.wtf(GameManagerService.TAG, "Error reading package manager settings", e);
            return false;
        }

        return true;
    }

    private void readPackage(TypedXmlPullParser parser) throws XmlPullParserException,
            IOException {
        String name = null;
        int gameMode = GameManager.GAME_MODE_UNSUPPORTED;
        try {
            name = parser.getAttributeValue(null, ATTR_NAME);
            gameMode = parser.getAttributeInt(null, ATTR_GAME_MODE);
        } catch (XmlPullParserException e) {
            Slog.wtf(GameManagerService.TAG, "Error reading game mode", e);
        }
        if (name != null) {
            mGameModes.put(name, gameMode);
        } else {
            XmlUtils.skipCurrentTag(parser);
        }
    }
}
