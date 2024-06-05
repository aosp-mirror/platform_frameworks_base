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
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.app.GameManagerService.GamePackageConfiguration;
import com.android.server.app.GameManagerService.GamePackageConfiguration.GameModeConfiguration;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Persists all GameService related settings.
 *
 * @hide
 */
public class GameManagerSettings {
    public static final String TAG = "GameManagerService_GameManagerSettings";
    // The XML file follows the below format:
    // <?xml>
    // <packages>
    //     <package name="" gameMode="">
    //       <gameModeConfig gameMode="" fps="" scaling="" useAngle="" loadingBoost="">
    //       </gameModeConfig>
    //       ...
    //     </package>
    //     ...
    // </packages>
    private static final String GAME_SERVICE_FILE_NAME = "game-manager-service.xml";

    private static final String TAG_PACKAGE = "package";
    private static final String TAG_PACKAGES = "packages";
    private static final String TAG_GAME_MODE_CONFIG = "gameModeConfig";

    private static final String ATTR_NAME = "name";
    private static final String ATTR_GAME_MODE = "gameMode";
    private static final String ATTR_SCALING = "scaling";
    private static final String ATTR_FPS = "fps";
    private static final String ATTR_USE_ANGLE = "useAngle";
    private static final String ATTR_LOADING_BOOST_DURATION = "loadingBoost";

    private final File mSystemDir;
    @VisibleForTesting
    final AtomicFile mSettingsFile;

    // PackageName -> GameMode
    private final ArrayMap<String, Integer> mGameModes = new ArrayMap<>();
    // PackageName -> GamePackageConfiguration
    private final ArrayMap<String, GamePackageConfiguration> mConfigOverrides = new ArrayMap<>();

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
     * Returns the game mode of a given package.
     * This operation must be synced with an external lock.
     */
    int getGameModeLocked(String packageName) {
        if (mGameModes.containsKey(packageName)) {
            final int gameMode = mGameModes.get(packageName);
            if (gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
                // force replace cached UNSUPPORTED mode with STANDARD starting in U
                return GameManager.GAME_MODE_STANDARD;
            }
            return gameMode;
        }
        return GameManager.GAME_MODE_STANDARD;
    }

    /**
     * Sets the game mode of a given package.
     * This operation must be synced with an external lock.
     */
    void setGameModeLocked(String packageName, int gameMode) {
        mGameModes.put(packageName, gameMode);
    }

    /**
     * Removes all game settings of a given package.
     * This operation must be synced with an external lock.
     */
    void removeGame(String packageName) {
        mGameModes.remove(packageName);
        mConfigOverrides.remove(packageName);
    }

    /**
     * Returns the game config override of a given package or null if absent.
     * This operation must be synced with an external lock.
     */
    GamePackageConfiguration getConfigOverride(String packageName) {
        return mConfigOverrides.get(packageName);
    }

    /**
     * Sets the game config override of a given package.
     * This operation must be synced with an external lock.
     */
    void setConfigOverride(String packageName, GamePackageConfiguration configOverride) {
        mConfigOverrides.put(packageName, configOverride);
    }

    /**
     * Removes the game mode config override of a given package.
     * This operation must be synced with an external lock.
     */
    void removeConfigOverride(String packageName) {
        mConfigOverrides.remove(packageName);
    }

    /**
     * Writes all current game service settings into disk.
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
            final ArraySet<String> packageNames = new ArraySet<>(mGameModes.keySet());
            packageNames.addAll(mConfigOverrides.keySet());
            for (String packageName : packageNames) {
                serializer.startTag(null, TAG_PACKAGE);
                serializer.attribute(null, ATTR_NAME, packageName);
                if (mGameModes.containsKey(packageName)) {
                    serializer.attributeInt(null, ATTR_GAME_MODE, mGameModes.get(packageName));
                }
                writeGameModeConfigTags(serializer, mConfigOverrides.get(packageName));
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
            Slog.wtf(TAG, "Unable to write game manager service settings, "
                    + "current changes will be lost at reboot", e);
        }
    }

    private void writeGameModeConfigTags(TypedXmlSerializer serializer,
            GamePackageConfiguration config) throws IOException {
        if (config == null) {
            return;
        }
        final int[] gameModes = config.getAvailableGameModes();
        for (final int mode : gameModes) {
            final GameModeConfiguration modeConfig = config.getGameModeConfiguration(mode);
            if (modeConfig != null) {
                serializer.startTag(null, TAG_GAME_MODE_CONFIG);
                serializer.attributeInt(null, ATTR_GAME_MODE, mode);
                serializer.attributeBoolean(null, ATTR_USE_ANGLE, modeConfig.getUseAngle());
                serializer.attribute(null, ATTR_FPS, modeConfig.getFpsStr());
                serializer.attributeFloat(null, ATTR_SCALING, modeConfig.getScaling());
                serializer.attributeInt(null, ATTR_LOADING_BOOST_DURATION,
                        modeConfig.getLoadingBoostDuration());
                serializer.endTag(null, TAG_GAME_MODE_CONFIG);
            }
        }
    }

    /**
     * Reads game service settings from the disk.
     * This operation must be synced with an external lock.
     */
    boolean readPersistentDataLocked() {
        mGameModes.clear();

        if (!mSettingsFile.exists()) {
            Slog.v(TAG, "Settings file doesn't exist, skip reading");
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
                Slog.wtf(TAG, "No start tag found in game manager settings");
                return false;
            }

            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (type == XmlPullParser.START_TAG && TAG_PACKAGE.equals(tagName)) {
                    readPackage(parser);
                } else {
                    XmlUtils.skipCurrentTag(parser);
                    Slog.w(TAG, "Unknown element under packages tag: " + tagName + " with type: "
                            + type);
                }
            }
            str.close();
        } catch (XmlPullParserException | java.io.IOException e) {
            Slog.wtf(TAG, "Error reading game manager settings", e);
            return false;
        }
        return true;
    }

    // this must be called on tag of type START_TAG.
    private void readPackage(TypedXmlPullParser parser) throws XmlPullParserException,
            IOException {
        final String name = parser.getAttributeValue(null, ATTR_NAME);
        if (name == null) {
            Slog.wtf(TAG, "No package name found in package tag");
            XmlUtils.skipCurrentTag(parser);
            return;
        }
        try {
            final int gameMode = parser.getAttributeInt(null, ATTR_GAME_MODE);
            mGameModes.put(name, gameMode);
        } catch (XmlPullParserException e) {
            Slog.v(TAG, "No game mode selected by user for package" + name);
        }
        final int packageTagDepth = parser.getDepth();
        int type;
        final GamePackageConfiguration config = new GamePackageConfiguration(name);
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > packageTagDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            final String tagName = parser.getName();
            if (type == XmlPullParser.START_TAG && TAG_GAME_MODE_CONFIG.equals(tagName)) {
                readGameModeConfig(parser, config);
            } else {
                XmlUtils.skipCurrentTag(parser);
                Slog.w(TAG, "Unknown element under package tag: " + tagName + " with type: "
                        + type);
            }
        }
        if (config.hasActiveGameModeConfig()) {
            mConfigOverrides.put(name, config);
        }
    }

    // this must be called on tag of type START_TAG.
    private void readGameModeConfig(TypedXmlPullParser parser, GamePackageConfiguration config) {
        final int gameMode;
        try {
            gameMode = parser.getAttributeInt(null, ATTR_GAME_MODE);
        } catch (XmlPullParserException e) {
            Slog.wtf(TAG, "Invalid game mode value in config tag: " + parser.getAttributeValue(null,
                    ATTR_GAME_MODE), e);
            return;
        }

        final GameModeConfiguration modeConfig = config.getOrAddDefaultGameModeConfiguration(
                gameMode);
        try {
            final float scaling = parser.getAttributeFloat(null, ATTR_SCALING);
            modeConfig.setScaling(scaling);
        } catch (XmlPullParserException e) {
            final String rawScaling = parser.getAttributeValue(null, ATTR_SCALING);
            if (rawScaling != null) {
                Slog.wtf(TAG, "Invalid scaling value in config tag: " + rawScaling, e);
            }
        }

        final String fps = parser.getAttributeValue(null, ATTR_FPS);
        modeConfig.setFpsStr(fps != null ? fps : GameModeConfiguration.DEFAULT_FPS);

        try {
            final boolean useAngle = parser.getAttributeBoolean(null, ATTR_USE_ANGLE);
            modeConfig.setUseAngle(useAngle);
        } catch (XmlPullParserException e) {
            final String rawUseAngle = parser.getAttributeValue(null, ATTR_USE_ANGLE);
            if (rawUseAngle != null) {
                Slog.wtf(TAG, "Invalid useAngle value in config tag: " + rawUseAngle, e);
            }
        }
        try {
            final int loadingBoostDuration = parser.getAttributeInt(null,
                    ATTR_LOADING_BOOST_DURATION);
            modeConfig.setLoadingBoostDuration(loadingBoostDuration);
        } catch (XmlPullParserException e) {
            final String rawLoadingBoost = parser.getAttributeValue(null,
                    ATTR_LOADING_BOOST_DURATION);
            if (rawLoadingBoost != null) {
                Slog.wtf(TAG, "Invalid loading boost in config tag: " + rawLoadingBoost, e);
            }
        }
    }
}
