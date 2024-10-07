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

package com.android.server.wallpaper;

import static android.app.Flags.removeNextWallpaperComponent;
import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;
import static android.app.WallpaperManager.ORIENTATION_UNKNOWN;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.wallpaper.WallpaperDisplayHelper.DisplayData;
import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER;
import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER_CROP;
import static com.android.server.wallpaper.WallpaperUtils.WALLPAPER_INFO;
import static com.android.server.wallpaper.WallpaperUtils.getWallpaperDir;
import static com.android.server.wallpaper.WallpaperUtils.makeWallpaperIdLocked;
import static com.android.window.flags.Flags.multiCrop;

import android.annotation.Nullable;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.app.WallpaperManager.SetWallpaperFlags;
import android.app.backup.WallpaperBackupHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.FileUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.JournaledFile;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.wallpaper.WallpaperData.BindSource;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper for the wallpaper loading / saving / xml parsing
 * Only meant to be used lock held by WallpaperManagerService
 * Only meant to be instantiated once by WallpaperManagerService
 * @hide
 */
public class WallpaperDataParser {

    private static final String TAG = WallpaperDataParser.class.getSimpleName();
    private static final boolean DEBUG = false;
    private final ComponentName mImageWallpaper;
    private final WallpaperDisplayHelper mWallpaperDisplayHelper;
    private final WallpaperCropper mWallpaperCropper;
    private final Context mContext;

    WallpaperDataParser(Context context, WallpaperDisplayHelper wallpaperDisplayHelper,
            WallpaperCropper wallpaperCropper) {
        mContext = context;
        mWallpaperDisplayHelper = wallpaperDisplayHelper;
        mWallpaperCropper = wallpaperCropper;
        mImageWallpaper = ComponentName.unflattenFromString(
                context.getResources().getString(R.string.image_wallpaper_component));
    }

    private JournaledFile makeJournaledFile(int userId) {
        final String base = new File(getWallpaperDir(userId), WALLPAPER_INFO).getAbsolutePath();
        return new JournaledFile(new File(base), new File(base + ".tmp"));
    }

    static class WallpaperLoadingResult {

        private final WallpaperData mSystemWallpaperData;

        @Nullable
        private final WallpaperData mLockWallpaperData;

        private final boolean mSuccess;

        private WallpaperLoadingResult(
                WallpaperData systemWallpaperData,
                WallpaperData lockWallpaperData,
                boolean success) {
            mSystemWallpaperData = systemWallpaperData;
            mLockWallpaperData = lockWallpaperData;
            mSuccess = success;
        }

        public WallpaperData getSystemWallpaperData() {
            return mSystemWallpaperData;
        }

        public WallpaperData getLockWallpaperData() {
            return mLockWallpaperData;
        }

        public boolean success() {
            return mSuccess;
        }
    }

    /**
     * Load the system wallpaper (and the lock wallpaper, if it exists) from disk
     * @param userId the id of the user for which the wallpaper should be loaded
     * @param keepDimensionHints if false, parse and set the
     *                      {@link DisplayData} width and height for the specified userId
     * @param migrateFromOld whether the current wallpaper is pre-N and needs migration
     * @param which The wallpaper(s) to load.
     * @return a {@link WallpaperLoadingResult} object containing the wallpaper data.
     */
    public WallpaperLoadingResult loadSettingsLocked(int userId, boolean keepDimensionHints,
            boolean migrateFromOld, @SetWallpaperFlags int which) {
        // TODO(b/270726737) remove the "keepDimensionHints" arg when removing the multi crop flag
        JournaledFile journal = makeJournaledFile(userId);
        FileInputStream stream = null;
        File file = journal.chooseForRead();

        boolean loadSystem = (which & FLAG_SYSTEM) != 0;
        boolean loadLock = (which & FLAG_LOCK) != 0;
        WallpaperData wallpaper = null;
        WallpaperData lockWallpaper = null;

        if (loadSystem) {
            // Do this once per boot
            if (migrateFromOld) migrateFromOld();
            wallpaper = new WallpaperData(userId, FLAG_SYSTEM);
            wallpaper.allowBackup = true;
            if (!wallpaper.cropExists()) {
                if (wallpaper.sourceExists()) {
                    mWallpaperCropper.generateCrop(wallpaper);
                } else {
                    Slog.i(TAG, "No static wallpaper imagery; defaults will be shown");
                }
            }
        }

        final DisplayData wpdData = mWallpaperDisplayHelper.getDisplayDataOrCreate(DEFAULT_DISPLAY);
        boolean success = false;

        try {
            stream = new FileInputStream(file);
            TypedXmlPullParser parser = Xml.resolvePullParser(stream);

            lockWallpaper = loadSettingsFromSerializer(parser, wallpaper, userId, loadSystem,
                    loadLock, keepDimensionHints, wpdData);

            success = true;
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "no current wallpaper -- first boot?");
        } catch (NullPointerException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        } catch (IOException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        } catch (IndexOutOfBoundsException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        }
        IoUtils.closeQuietly(stream);

        mWallpaperDisplayHelper.ensureSaneWallpaperDisplaySize(wpdData, DEFAULT_DISPLAY);

        if (loadSystem) {
            if (!success) {
                wallpaper.cropHint.set(0, 0, 0, 0);
                wpdData.mPadding.set(0, 0, 0, 0);
                wallpaper.name = "";
            } else {
                if (wallpaper.wallpaperId <= 0) {
                    wallpaper.wallpaperId = makeWallpaperIdLocked();
                    if (DEBUG) {
                        Slog.w(TAG, "Didn't set wallpaper id in loadSettingsLocked(" + userId
                                + "); now " + wallpaper.wallpaperId);
                    }
                }
            }
            ensureSaneWallpaperData(wallpaper);
            wallpaper.mWhich = lockWallpaper != null ? FLAG_SYSTEM : FLAG_SYSTEM | FLAG_LOCK;
        }

        if (loadLock) {
            if (!success) lockWallpaper = null;
            if (lockWallpaper != null) {
                ensureSaneWallpaperData(lockWallpaper);
                lockWallpaper.mWhich = FLAG_LOCK;
            }
        }

        return new WallpaperLoadingResult(wallpaper, lockWallpaper, success);
    }

    // This method updates `wallpaper` in place, but returns `lockWallpaper`. This is because
    // `wallpaper` already exists if it's being read per `loadSystem`, but `lockWallpaper` is
    // created conditionally if there is lock screen wallpaper data to read.
    @VisibleForTesting
    WallpaperData loadSettingsFromSerializer(TypedXmlPullParser parser, WallpaperData wallpaper,
            int userId, boolean loadSystem, boolean loadLock, boolean keepDimensionHints,
            DisplayData wpdData) throws IOException, XmlPullParserException {
        WallpaperData lockWallpaper = null;
        int type;
        do {
            type = parser.next();
            if (type == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if (("wp".equals(tag) && loadSystem) || ("kwp".equals(tag) && loadLock)) {
                    if ("kwp".equals(tag)) {
                        lockWallpaper = new WallpaperData(userId, FLAG_LOCK);
                    }
                    WallpaperData wallpaperToParse =
                            "wp".equals(tag) ? wallpaper : lockWallpaper;

                    if (!multiCrop()) {
                        parseWallpaperAttributes(parser, wallpaperToParse, keepDimensionHints);
                    }

                    String comp = parser.getAttributeValue(null, "component");
                    if (removeNextWallpaperComponent()) {
                        wallpaperToParse.setComponent(comp != null
                                ? ComponentName.unflattenFromString(comp)
                                : null);
                        if (wallpaperToParse.getComponent() == null
                                || "android".equals(wallpaperToParse.getComponent()
                                .getPackageName())) {
                            wallpaperToParse.setComponent(mImageWallpaper);
                        }
                    } else {
                        wallpaperToParse.nextWallpaperComponent = comp != null
                                ? ComponentName.unflattenFromString(comp)
                                : null;
                        if (wallpaperToParse.nextWallpaperComponent == null
                                || "android".equals(wallpaperToParse.nextWallpaperComponent
                                .getPackageName())) {
                            wallpaperToParse.nextWallpaperComponent = mImageWallpaper;
                        }
                    }

                    if (multiCrop()) {
                        parseWallpaperAttributes(parser, wallpaperToParse, keepDimensionHints);
                    }

                    if (DEBUG) {
                        Slog.v(TAG, "mWidth:" + wpdData.mWidth);
                        Slog.v(TAG, "mHeight:" + wpdData.mHeight);
                        Slog.v(TAG, "cropRect:" + wallpaper.cropHint);
                        Slog.v(TAG, "primaryColors:" + wallpaper.primaryColors);
                        Slog.v(TAG, "mName:" + wallpaper.name);
                        if (removeNextWallpaperComponent()) {
                            Slog.v(TAG, "mWallpaperComponent:" + wallpaper.getComponent());
                        } else {
                            Slog.v(TAG, "mNextWallpaperComponent:"
                                    + wallpaper.nextWallpaperComponent);
                        }
                    }
                }
            }
        } while (type != XmlPullParser.END_DOCUMENT);

        return lockWallpaper;
    }

    private void ensureSaneWallpaperData(WallpaperData wallpaper) {
        // Only overwrite cropHint if the rectangle is invalid.
        if (wallpaper.cropHint.width() < 0
                || wallpaper.cropHint.height() < 0) {
            wallpaper.cropHint.set(0, 0, 0, 0);
        }
    }


    private void migrateFromOld() {
        // Pre-N, what existed is the one we're now using as the display crop
        File preNWallpaper = new File(getWallpaperDir(0), WALLPAPER_CROP);
        // In the very-long-ago, imagery lived with the settings app
        File originalWallpaper = new File(WallpaperBackupHelper.WALLPAPER_IMAGE_KEY);
        File newWallpaper = new File(getWallpaperDir(0), WALLPAPER);

        // Migrations from earlier wallpaper image storage schemas
        if (preNWallpaper.exists()) {
            if (!newWallpaper.exists()) {
                // we've got the 'wallpaper' crop file but not the nominal source image,
                // so do the simple "just take everything" straight copy of legacy data
                if (DEBUG) {
                    Slog.i(TAG, "Migrating wallpaper schema");
                }
                FileUtils.copyFile(preNWallpaper, newWallpaper);
            } // else we're in the usual modern case: both source & crop exist
        } else if (originalWallpaper.exists()) {
            // VERY old schema; make sure things exist and are in the right place
            if (DEBUG) {
                Slog.i(TAG, "Migrating antique wallpaper schema");
            }
            File oldInfo = new File(WallpaperBackupHelper.WALLPAPER_INFO_KEY);
            if (oldInfo.exists()) {
                File newInfo = new File(getWallpaperDir(0), WALLPAPER_INFO);
                oldInfo.renameTo(newInfo);
            }

            FileUtils.copyFile(originalWallpaper, preNWallpaper);
            originalWallpaper.renameTo(newWallpaper);
        }
    }

    @VisibleForTesting
    void parseWallpaperAttributes(TypedXmlPullParser parser, WallpaperData wallpaper,
            boolean keepDimensionHints) throws XmlPullParserException {
        final int id = parser.getAttributeInt(null, "id", -1);
        if (id != -1) {
            wallpaper.wallpaperId = id;
            if (id > WallpaperUtils.getCurrentWallpaperId()) {
                WallpaperUtils.setCurrentWallpaperId(id);
            }
        } else {
            wallpaper.wallpaperId = makeWallpaperIdLocked();
        }

        Rect legacyCropHint = new Rect(
                getAttributeInt(parser, "cropLeft", 0),
                getAttributeInt(parser, "cropTop", 0),
                getAttributeInt(parser, "cropRight", 0),
                getAttributeInt(parser, "cropBottom", 0));
        Rect totalCropHint = new Rect(
                getAttributeInt(parser, "totalCropLeft", 0),
                getAttributeInt(parser, "totalCropTop", 0),
                getAttributeInt(parser, "totalCropRight", 0),
                getAttributeInt(parser, "totalCropBottom", 0));
        ComponentName componentName = removeNextWallpaperComponent() ? wallpaper.getComponent()
                : wallpaper.nextWallpaperComponent;
        if (multiCrop() && mImageWallpaper.equals(componentName)) {
            wallpaper.mCropHints = new SparseArray<>();
            for (Pair<Integer, String> pair: screenDimensionPairs()) {
                Rect cropHint = new Rect(
                        parser.getAttributeInt(null, "cropLeft" + pair.second, 0),
                        parser.getAttributeInt(null, "cropTop" + pair.second, 0),
                        parser.getAttributeInt(null, "cropRight" + pair.second, 0),
                        parser.getAttributeInt(null, "cropBottom" + pair.second, 0));
                if (!cropHint.isEmpty()) wallpaper.mCropHints.put(pair.first, cropHint);
                if (!cropHint.isEmpty() && cropHint.equals(legacyCropHint)) {
                    wallpaper.mOrientationWhenSet = pair.first;
                }
            }
            if (wallpaper.mCropHints.size() == 0 && totalCropHint.isEmpty()) {
                // migration case: the crops per screen orientation are not specified.
                if (!legacyCropHint.isEmpty()) {
                    wallpaper.cropHint.set(legacyCropHint);
                }
            } else {
                wallpaper.cropHint.set(totalCropHint);
            }
            wallpaper.mSampleSize = parser.getAttributeFloat(null, "sampleSize", 1f);
        } else if (!multiCrop()) {
            wallpaper.cropHint.set(legacyCropHint);
        }
        final DisplayData wpData = mWallpaperDisplayHelper
                .getDisplayDataOrCreate(DEFAULT_DISPLAY);
        if (!keepDimensionHints && !multiCrop()) {
            wpData.mWidth = parser.getAttributeInt(null, "width", 0);
            wpData.mHeight = parser.getAttributeInt(null, "height", 0);
        }
        if (!multiCrop()) {
            wpData.mPadding.left = getAttributeInt(parser, "paddingLeft", 0);
            wpData.mPadding.top = getAttributeInt(parser, "paddingTop", 0);
            wpData.mPadding.right = getAttributeInt(parser, "paddingRight", 0);
            wpData.mPadding.bottom = getAttributeInt(parser, "paddingBottom", 0);
        }
        wallpaper.mWallpaperDimAmount = getAttributeFloat(parser, "dimAmount", 0f);
        BindSource bindSource;
        try {
            bindSource = Enum.valueOf(BindSource.class,
                    getAttributeString(parser, "bindSource", BindSource.UNKNOWN.name()));
        } catch (IllegalArgumentException | NullPointerException e) {
            bindSource = BindSource.UNKNOWN;
        }
        wallpaper.mBindSource = bindSource;
        int dimAmountsCount = getAttributeInt(parser, "dimAmountsCount", 0);
        if (dimAmountsCount > 0) {
            SparseArray<Float> allDimAmounts = new SparseArray<>(dimAmountsCount);
            for (int i = 0; i < dimAmountsCount; i++) {
                int uid = getAttributeInt(parser, "dimUID" + i, 0);
                float dimValue = getAttributeFloat(parser, "dimValue" + i, 0f);
                allDimAmounts.put(uid, dimValue);
            }
            wallpaper.mUidToDimAmount = allDimAmounts;
        }
        int colorsCount = getAttributeInt(parser, "colorsCount", 0);
        int allColorsCount =  getAttributeInt(parser, "allColorsCount", 0);
        if (allColorsCount > 0) {
            Map<Integer, Integer> allColors = new HashMap<>(allColorsCount);
            for (int i = 0; i < allColorsCount; i++) {
                int colorInt = getAttributeInt(parser, "allColorsValue" + i, 0);
                int population = getAttributeInt(parser, "allColorsPopulation" + i, 0);
                allColors.put(colorInt, population);
            }
            int colorHints = getAttributeInt(parser, "colorHints", 0);
            wallpaper.primaryColors = new WallpaperColors(allColors, colorHints);
        } else if (colorsCount > 0) {
            Color primary = null, secondary = null, tertiary = null;
            for (int i = 0; i < colorsCount; i++) {
                Color color = Color.valueOf(getAttributeInt(parser, "colorValue" + i, 0));
                if (i == 0) {
                    primary = color;
                } else if (i == 1) {
                    secondary = color;
                } else if (i == 2) {
                    tertiary = color;
                } else {
                    break;
                }
            }
            int colorHints = getAttributeInt(parser, "colorHints", 0);
            wallpaper.primaryColors = new WallpaperColors(primary, secondary, tertiary, colorHints);
        }
        wallpaper.name = parser.getAttributeValue(null, "name");
        wallpaper.allowBackup = parser.getAttributeBoolean(null, "backup", false);
    }

    private static int getAttributeInt(TypedXmlPullParser parser, String name, int defValue) {
        return parser.getAttributeInt(null, name, defValue);
    }

    private static float getAttributeFloat(TypedXmlPullParser parser, String name, float defValue) {
        return parser.getAttributeFloat(null, name, defValue);
    }

    private String getAttributeString(XmlPullParser parser, String name, String defValue) {
        String s = parser.getAttributeValue(null, name);
        return (s != null) ? s : defValue;
    }

    void saveSettingsLocked(int userId, WallpaperData wallpaper, WallpaperData lockWallpaper) {
        JournaledFile journal = makeJournaledFile(userId);
        FileOutputStream fstream = null;
        try {
            fstream = new FileOutputStream(journal.chooseForWrite(), false);
            TypedXmlSerializer out = Xml.resolveSerializer(fstream);
            saveSettingsToSerializer(out, wallpaper, lockWallpaper);
            fstream.flush();
            FileUtils.sync(fstream);
            fstream.close();
            journal.commit();
        } catch (IOException e) {
            IoUtils.closeQuietly(fstream);
            journal.rollback();
        }
    }

    @VisibleForTesting
    void saveSettingsToSerializer(TypedXmlSerializer out, WallpaperData wallpaper,
            WallpaperData lockWallpaper) throws IOException {
        out.startDocument(null, true);

        if (wallpaper != null) {
            writeWallpaperAttributes(out, "wp", wallpaper);
        }

        if (lockWallpaper != null) {
            writeWallpaperAttributes(out, "kwp", lockWallpaper);
        }

        out.endDocument();
    }

    @VisibleForTesting
    void writeWallpaperAttributes(TypedXmlSerializer out, String tag, WallpaperData wallpaper)
            throws IllegalArgumentException, IllegalStateException, IOException {
        if (DEBUG) {
            Slog.v(TAG, "writeWallpaperAttributes id=" + wallpaper.wallpaperId);
        }
        out.startTag(null, tag);
        out.attributeInt(null, "id", wallpaper.wallpaperId);

        if (multiCrop() && mImageWallpaper.equals(wallpaper.getComponent())) {
            if (wallpaper.mCropHints == null) {
                Slog.e(TAG, "cropHints should not be null when saved");
                wallpaper.mCropHints = new SparseArray<>();
            }
            Rect rectToPutInLegacyCrop = new Rect(wallpaper.cropHint);
            for (Pair<Integer, String> pair : screenDimensionPairs()) {
                Rect cropHint = wallpaper.mCropHints.get(pair.first);
                if (cropHint == null) continue;
                out.attributeInt(null, "cropLeft" + pair.second, cropHint.left);
                out.attributeInt(null, "cropTop" + pair.second, cropHint.top);
                out.attributeInt(null, "cropRight" + pair.second, cropHint.right);
                out.attributeInt(null, "cropBottom" + pair.second, cropHint.bottom);

                // to support back compatibility in B&R, save the crops for one orientation in the
                // legacy "cropLeft", "cropTop", "cropRight", "cropBottom" entries
                int orientationToPutInLegacyCrop = wallpaper.mOrientationWhenSet;
                if (mWallpaperDisplayHelper.isFoldable()) {
                    int unfoldedOrientation = mWallpaperDisplayHelper
                            .getUnfoldedOrientation(orientationToPutInLegacyCrop);
                    if (unfoldedOrientation != ORIENTATION_UNKNOWN) {
                        orientationToPutInLegacyCrop = unfoldedOrientation;
                    }
                }
                if (pair.first == orientationToPutInLegacyCrop) {
                    rectToPutInLegacyCrop.set(cropHint);
                }
            }
            out.attributeInt(null, "cropLeft", rectToPutInLegacyCrop.left);
            out.attributeInt(null, "cropTop", rectToPutInLegacyCrop.top);
            out.attributeInt(null, "cropRight", rectToPutInLegacyCrop.right);
            out.attributeInt(null, "cropBottom", rectToPutInLegacyCrop.bottom);

            out.attributeInt(null, "totalCropLeft", wallpaper.cropHint.left);
            out.attributeInt(null, "totalCropTop", wallpaper.cropHint.top);
            out.attributeInt(null, "totalCropRight", wallpaper.cropHint.right);
            out.attributeInt(null, "totalCropBottom", wallpaper.cropHint.bottom);
            out.attributeFloat(null, "sampleSize", wallpaper.mSampleSize);
        } else if (!multiCrop()) {
            final DisplayData wpdData =
                    mWallpaperDisplayHelper.getDisplayDataOrCreate(DEFAULT_DISPLAY);
            out.attributeInt(null, "width", wpdData.mWidth);
            out.attributeInt(null, "height", wpdData.mHeight);
            out.attributeInt(null, "cropLeft", wallpaper.cropHint.left);
            out.attributeInt(null, "cropTop", wallpaper.cropHint.top);
            out.attributeInt(null, "cropRight", wallpaper.cropHint.right);
            out.attributeInt(null, "cropBottom", wallpaper.cropHint.bottom);
            if (wpdData.mPadding.left != 0) {
                out.attributeInt(null, "paddingLeft", wpdData.mPadding.left);
            }
            if (wpdData.mPadding.top != 0) {
                out.attributeInt(null, "paddingTop", wpdData.mPadding.top);
            }
            if (wpdData.mPadding.right != 0) {
                out.attributeInt(null, "paddingRight", wpdData.mPadding.right);
            }
            if (wpdData.mPadding.bottom != 0) {
                out.attributeInt(null, "paddingBottom", wpdData.mPadding.bottom);
            }
        }

        out.attributeFloat(null, "dimAmount", wallpaper.mWallpaperDimAmount);
        out.attribute(null, "bindSource", wallpaper.mBindSource.name());
        int dimAmountsCount = wallpaper.mUidToDimAmount.size();
        out.attributeInt(null, "dimAmountsCount", dimAmountsCount);
        if (dimAmountsCount > 0) {
            int index = 0;
            for (int i = 0; i < wallpaper.mUidToDimAmount.size(); i++) {
                out.attributeInt(null, "dimUID" + index, wallpaper.mUidToDimAmount.keyAt(i));
                out.attributeFloat(null, "dimValue" + index, wallpaper.mUidToDimAmount.valueAt(i));
                index++;
            }
        }

        if (wallpaper.primaryColors != null) {
            int colorsCount = wallpaper.primaryColors.getMainColors().size();
            out.attributeInt(null, "colorsCount", colorsCount);
            if (colorsCount > 0) {
                for (int i = 0; i < colorsCount; i++) {
                    final Color wc = wallpaper.primaryColors.getMainColors().get(i);
                    out.attributeInt(null, "colorValue" + i, wc.toArgb());
                }
            }

            int allColorsCount = wallpaper.primaryColors.getAllColors().size();
            out.attributeInt(null, "allColorsCount", allColorsCount);
            if (allColorsCount > 0) {
                int index = 0;
                for (Map.Entry<Integer, Integer> entry : wallpaper.primaryColors.getAllColors()
                        .entrySet()) {
                    out.attributeInt(null, "allColorsValue" + index, entry.getKey());
                    out.attributeInt(null, "allColorsPopulation" + index, entry.getValue());
                    index++;
                }
            }

            out.attributeInt(null, "colorHints", wallpaper.primaryColors.getColorHints());
        }

        out.attribute(null, "name", wallpaper.name);
        if (wallpaper.getComponent() != null
                && !wallpaper.getComponent().equals(mImageWallpaper)) {
            out.attribute(null, "component",
                    wallpaper.getComponent().flattenToShortString());
        }

        if (wallpaper.allowBackup) {
            out.attributeBoolean(null, "backup", true);
        }

        out.endTag(null, tag);
    }

    // Restore the named resource bitmap to both source + crop files
    boolean restoreNamedResourceLocked(WallpaperData wallpaper) {
        if (wallpaper.name.length() > 4 && "res:".equals(wallpaper.name.substring(0, 4))) {
            String resName = wallpaper.name.substring(4);

            String pkg = null;
            int colon = resName.indexOf(':');
            if (colon > 0) {
                pkg = resName.substring(0, colon);
            }

            String ident = null;
            int slash = resName.lastIndexOf('/');
            if (slash > 0) {
                ident = resName.substring(slash + 1);
            }

            String type = null;
            if (colon > 0 && slash > 0 && (slash - colon) > 1) {
                type = resName.substring(colon + 1, slash);
            }

            if (pkg != null && ident != null && type != null) {
                int resId = -1;
                InputStream res = null;
                FileOutputStream fos = null;
                FileOutputStream cos = null;
                try {
                    Context c = mContext.createPackageContext(pkg, Context.CONTEXT_RESTRICTED);
                    Resources r = c.getResources();
                    resId = r.getIdentifier(resName, null, null);
                    if (resId == 0) {
                        Slog.e(TAG, "couldn't resolve identifier pkg=" + pkg + " type=" + type
                                + " ident=" + ident);
                        return false;
                    }

                    res = r.openRawResource(resId);
                    if (wallpaper.getWallpaperFile().exists()) {
                        wallpaper.getWallpaperFile().delete();
                        wallpaper.getCropFile().delete();
                    }
                    fos = new FileOutputStream(wallpaper.getWallpaperFile());
                    cos = new FileOutputStream(wallpaper.getCropFile());

                    byte[] buffer = new byte[32768];
                    int amt;
                    while ((amt = res.read(buffer)) > 0) {
                        fos.write(buffer, 0, amt);
                        cos.write(buffer, 0, amt);
                    }
                    // mWallpaperObserver will notice the close and send the change broadcast

                    Slog.v(TAG, "Restored wallpaper: " + resName);
                    return true;
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.e(TAG, "Package name " + pkg + " not found");
                } catch (Resources.NotFoundException e) {
                    Slog.e(TAG, "Resource not found: " + resId);
                } catch (IOException e) {
                    Slog.e(TAG, "IOException while restoring wallpaper ", e);
                } finally {
                    IoUtils.closeQuietly(res);
                    if (fos != null) {
                        FileUtils.sync(fos);
                    }
                    if (cos != null) {
                        FileUtils.sync(cos);
                    }
                    IoUtils.closeQuietly(fos);
                    IoUtils.closeQuietly(cos);
                }
            }
        }
        return false;
    }

    private static List<Pair<Integer, String>> screenDimensionPairs() {
        return List.of(
                new Pair<>(WallpaperManager.PORTRAIT, "Portrait"),
                new Pair<>(WallpaperManager.LANDSCAPE, "Landscape"),
                new Pair<>(WallpaperManager.SQUARE_PORTRAIT, "SquarePortrait"),
                new Pair<>(WallpaperManager.SQUARE_LANDSCAPE, "SquareLandscape"));
    }
}
