/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.app;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.SparseArray;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * A no-op implementation of {@link WallpaperManager}.
 */
final class DisabledWallpaperManager extends WallpaperManager {

    private static final String TAG = DisabledWallpaperManager.class.getSimpleName();

    // Don't need to worry about synchronization
    private static DisabledWallpaperManager sInstance;

    private static final boolean DEBUG = false;

    @NonNull
    static DisabledWallpaperManager getInstance() {
        if (sInstance == null) {
            sInstance = new DisabledWallpaperManager();
        }
        return sInstance;
    }

    private DisabledWallpaperManager() {
    }

    @UnsupportedAppUsage
    public IWallpaperManager getIWallpaperManager() {
        return unsupported();
    }

    @Override
    public boolean isLockscreenLiveWallpaperEnabled() {
        return unsupportedBoolean();
    }

    @Override
    public boolean shouldEnableWideColorGamut() {
        return unsupportedBoolean();
    }

    @Override
    public Drawable getDrawable() {
        return unsupported();
    }

    @Override
    public Drawable getBuiltInDrawable() {
        return unsupported();
    }

    @Override
    public Drawable getBuiltInDrawable(int which) {
        return unsupported();
    }

    @Override
    public Drawable getBuiltInDrawable(int outWidth, int outHeight, boolean scaleToFit,
            float horizontalAlignment, float verticalAlignment) {
        return unsupported();
    }

    @Override
    public Drawable getBuiltInDrawable(int outWidth, int outHeight, boolean scaleToFit,
            float horizontalAlignment, float verticalAlignment, int which) {
        return unsupported();
    }

    @Override
    public Drawable peekDrawable() {
        return unsupported();
    }

    @Override
    public Drawable getFastDrawable() {
        return unsupported();
    }

    @Override
    public Drawable peekFastDrawable() {
        return unsupported();
    }

    @Override
    public boolean wallpaperSupportsWcg(int which) {
        return unsupportedBoolean();
    }

    @Override
    public Bitmap getBitmap() {
        return unsupported();
    }

    @Override
    public Bitmap getBitmap(boolean hardware) {
        return unsupported();
    }

    @Nullable
    public Bitmap getBitmap(boolean hardware, @SetWallpaperFlags int which) {
        return unsupported();
    }

    @Override
    public Bitmap getBitmapAsUser(int userId, boolean hardware) {
        return unsupported();
    }

    @Override
    public Bitmap getBitmapAsUser(int userId, boolean hardware, @SetWallpaperFlags int which) {
        return unsupported();
    }

    @Override
    public Bitmap getBitmapAsUser(int userId, boolean hardware,
            @SetWallpaperFlags int which, boolean returnDefault) {
        return unsupported();
    }

    @Override
    public Rect peekBitmapDimensions() {
        return unsupported();
    }

    @Override
    public Rect peekBitmapDimensions(@SetWallpaperFlags int which) {
        return unsupported();
    }

    @Nullable
    public Rect peekBitmapDimensions(@SetWallpaperFlags int which, boolean returnDefault) {
        return unsupported();
    }

    @Override
    public List<Rect> getBitmapCrops(@NonNull List<Point> displaySizes,
            @SetWallpaperFlags int which, boolean originalBitmap) {
        return unsupported();
    }

    @Override
    public List<Rect> getBitmapCrops(@NonNull Point bitmapSize, @NonNull List<Point> displaySizes,
            @Nullable Map<Point, Rect> cropHints) {
        return unsupported();
    }

    @Override
    public WallpaperColors getWallpaperColors(@NonNull Bitmap bitmap,
            @Nullable Map<Point, Rect> cropHints) {
        return unsupported();
    }

    @Override
    public ParcelFileDescriptor getWallpaperFile(int which) {
        return unsupported();
    }

    @Override
    public void addOnColorsChangedListener(OnColorsChangedListener listener, Handler handler) {
        unsupported();
    }

    @Override
    public void addOnColorsChangedListener(OnColorsChangedListener listener, Handler handler,
            int userId) {
        unsupported();
    }

    @Override
    public void removeOnColorsChangedListener(OnColorsChangedListener callback) {
        unsupported();
    }

    @Override
    public void removeOnColorsChangedListener(OnColorsChangedListener callback, int userId) {
        unsupported();
    }

    @Override
    public WallpaperColors getWallpaperColors(int which) {
        return unsupported();
    }

    @Override
    public WallpaperColors getWallpaperColors(int which, int userId) {
        return unsupported();
    }

    @Override
    public void addOnColorsChangedListener(@NonNull LocalWallpaperColorConsumer callback,
            List<RectF> regions, int which) throws IllegalArgumentException {
        unsupported();
    }

    @Override
    public void removeOnColorsChangedListener(@NonNull LocalWallpaperColorConsumer callback) {
        unsupported();
    }

    @Override
    public ParcelFileDescriptor getWallpaperFile(int which, int userId) {
        return unsupported();
    }

    @Override
    public ParcelFileDescriptor getWallpaperFile(int which, boolean getCropped) {
        return unsupported();
    }

    @Override
    public void forgetLoadedWallpaper() {
        unsupported();
    }

    @Override
    public WallpaperInfo getWallpaperInfo() {
        return unsupported();
    }

    public WallpaperInfo getWallpaperInfoForUser(int userId) {
        return unsupported();
    }

    @Override
    public WallpaperInfo getWallpaperInfo(@SetWallpaperFlags int which) {
        return unsupported();
    }

    @Override
    public WallpaperInfo getWallpaperInfo(@SetWallpaperFlags int which, int userId) {
        return unsupported();
    }

    @Override
    public ParcelFileDescriptor getWallpaperInfoFile() {
        return unsupported();
    }

    @Override
    public int getWallpaperId(int which) {
        return unsupportedInt();
    }

    @Override
    public int getWallpaperIdForUser(int which, int userId) {
        return unsupportedInt();
    }

    @Override
    public Intent getCropAndSetWallpaperIntent(Uri imageUri) {
        return unsupported();
    }

    @Override
    public void setResource(int resid) throws IOException {
        unsupported();
    }

    @Override
    public int setResource(int resid, int which) throws IOException {
        unsupported();
        return 0;
    }

    @Override
    public void setBitmap(Bitmap bitmap) throws IOException {
        unsupported();
    }

    @Override
    public int setBitmap(Bitmap fullImage, Rect visibleCropHint, boolean allowBackup)
            throws IOException {
        unsupported();
        return 0;
    }

    @Override
    public int setBitmap(Bitmap fullImage, Rect visibleCropHint, boolean allowBackup, int which)
            throws IOException {
        unsupported();
        return 0;
    }

    @Override
    public int setBitmap(Bitmap fullImage, Rect visibleCropHint, boolean allowBackup, int which,
            int userId) throws IOException {
        unsupported();
        return 0;
    }

    public int setBitmapWithCrops(@Nullable Bitmap fullImage, @NonNull Map<Point, Rect> cropHints,
            boolean allowBackup, @SetWallpaperFlags int which) throws IOException {
        return unsupportedInt();
    }

    @Override
    public void setStream(InputStream bitmapData) throws IOException {
        unsupported();
    }

    @Override
    public int setStream(InputStream bitmapData, Rect visibleCropHint, boolean allowBackup)
            throws IOException {
        unsupported();
        return 0;
    }

    @Override
    public int setStream(InputStream bitmapData, Rect visibleCropHint, boolean allowBackup,
            int which) throws IOException {
        unsupported();
        return 0;
    }

    @Override
    public int setStreamWithCrops(InputStream bitmapData, @NonNull Map<Point, Rect> cropHints,
            boolean allowBackup, @SetWallpaperFlags int which) throws IOException {
        return unsupportedInt();
    }


    @Override
    public int setStreamWithCrops(InputStream bitmapData, @NonNull SparseArray<Rect> cropHints,
            boolean allowBackup, @SetWallpaperFlags int which) throws IOException {
        return unsupportedInt();
    }

    @Override
    public boolean hasResourceWallpaper(int resid) {
        return unsupportedBoolean();
    }

    @Override
    public int getDesiredMinimumWidth() {
        return unsupportedInt();
    }

    @Override
    public int getDesiredMinimumHeight() {
        return unsupportedInt();
    }

    @Override
    public void suggestDesiredDimensions(int minimumWidth, int minimumHeight) {
        unsupported();
    }

    @Override
    public void setDisplayPadding(Rect padding) {
        unsupported();
    }

    @Override
    public void setDisplayOffset(IBinder windowToken, int x, int y) {
        unsupported();
    }

    @Override
    public void clearWallpaper() {
        unsupported();
    }

    @Override
    public void clearWallpaper(int which, int userId) {
        unsupported();
    }

    @Override
    public boolean setWallpaperComponent(ComponentName name) {
        return unsupportedBoolean();
    }


    @Override
    public void setWallpaperDimAmount(@FloatRange(from = 0f, to = 1f) float dimAmount) {
        unsupported();
    }

    @Override
    public @FloatRange(from = 0f, to = 1f) float getWallpaperDimAmount() {
        return unsupportedInt();
    }

    @Override
    public boolean lockScreenWallpaperExists() {
        return unsupportedBoolean();
    }

    @Override
    public boolean setWallpaperComponent(ComponentName name, int userId) {
        return unsupportedBoolean();
    }

    @Override
    public boolean setWallpaperComponentWithFlags(@NonNull ComponentName name,
            @SetWallpaperFlags int which) {
        return unsupportedBoolean();
    }

    @Override
    public boolean setWallpaperComponentWithFlags(@NonNull ComponentName name,
            @SetWallpaperFlags int which, int userId) {
        return unsupportedBoolean();
    }

    @Override
    public void setWallpaperOffsets(IBinder windowToken, float xOffset, float yOffset) {
        unsupported();
    }

    @Override
    public void setWallpaperOffsetSteps(float xStep, float yStep) {
        unsupported();
    }

    @Override
    public void sendWallpaperCommand(IBinder windowToken, String action, int x, int y, int z,
            Bundle extras) {
        unsupported();
    }

    @Override
    public void setWallpaperZoomOut(@NonNull IBinder windowToken, float zoom) {
        unsupported();
    }

    @Override
    public boolean isWallpaperSupported() {
        return false;
    }

    @Override
    public boolean isSetWallpaperAllowed() {
        return false;
    }

    @Override
    public void clearWallpaperOffsets(IBinder windowToken) {
        unsupported();
    }

    @Override
    public void clear() throws IOException {
        unsupported();
    }

    @Override
    public void clear(int which) throws IOException {
        unsupported();
    }

    @Override
    public boolean isWallpaperBackupEligible(int which) {
        return unsupportedBoolean();
    }

    private static <T> T unsupported() {
        if (DEBUG) Log.w(TAG, "unsupported method called; returning null", new Exception());
        return null;
    }

    private static boolean unsupportedBoolean() {
        if (DEBUG) Log.w(TAG, "unsupported method called; returning false", new Exception());
        return false;
    }

    private static int unsupportedInt() {
        if (DEBUG) Log.w(TAG, "unsupported method called; returning -1", new Exception());
        return -1;
    }
}
