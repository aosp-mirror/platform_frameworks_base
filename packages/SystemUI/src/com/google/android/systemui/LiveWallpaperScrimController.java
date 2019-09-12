package com.google.android.systemui;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.IWallpaperManager;
import android.app.IWallpaperManager.Stub;
import android.app.WallpaperInfo;
import android.content.ComponentName;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArraySet;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.util.function.TriConsumer;
import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.LockscreenWallpaper;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ScrimState;
import com.google.android.collect.Sets;
import java.util.function.Consumer;

public class LiveWallpaperScrimController extends ScrimController {
    private static ArraySet<ComponentName> REDUCED_SCRIM_WALLPAPERS;
    private int mCurrentUser = ActivityManager.getCurrentUser();
    private final LockscreenWallpaper mLockscreenWallpaper;
    private final IWallpaperManager mWallpaperManager = Stub.asInterface(ServiceManager.getService("wallpaper"));

    static {
        String str = "com.breel.wallpapers18";
        REDUCED_SCRIM_WALLPAPERS = Sets.newArraySet(new ComponentName[]{new ComponentName("com.breel.geswallpapers", "com.breel.geswallpapers.wallpapers.EarthWallpaperService"), new ComponentName(str, "com.breel.wallpapers18.delight.wallpapers.DelightWallpaperV1"), new ComponentName(str, "com.breel.wallpapers18.delight.wallpapers.DelightWallpaperV2"), new ComponentName(str, "com.breel.wallpapers18.delight.wallpapers.DelightWallpaperV3"), new ComponentName(str, "com.breel.wallpapers18.surfandturf.wallpapers.variations.SurfAndTurfWallpaperV2"), new ComponentName(str, "com.breel.wallpapers18.cities.wallpapers.variations.SanFranciscoWallpaper"), new ComponentName(str, "com.breel.wallpapers18.cities.wallpapers.variations.NewYorkWallpaper")});
    }

    public LiveWallpaperScrimController(ScrimView scrimView, ScrimView scrimView2, LockscreenWallpaper lockscreenWallpaper, TriConsumer<ScrimState, Float, GradientColors> triConsumer, Consumer<Integer> consumer, DozeParameters dozeParameters, AlarmManager alarmManager) {
        super(scrimView, scrimView2, triConsumer, consumer, dozeParameters, alarmManager);
        mLockscreenWallpaper = lockscreenWallpaper;
    }

    @Override
    public void transitionTo(ScrimState scrimState) {
        if (scrimState == ScrimState.KEYGUARD) {
            updateScrimValues();
        }
        super.transitionTo(scrimState);
    }

    private void updateScrimValues() {
        if (isReducedScrimWallpaperSet()) {
            setScrimBehindValues(0.25f);
        } else {
            setScrimBehindValues(0.2f);
        }
    }

    @Override
    public void setCurrentUser(int i) {
        mCurrentUser = i;
        updateScrimValues();
    }

    private boolean isReducedScrimWallpaperSet() {
        try {
            WallpaperInfo wallpaperInfo = mWallpaperManager.getWallpaperInfo(mCurrentUser);
            if (wallpaperInfo == null || !REDUCED_SCRIM_WALLPAPERS.contains(wallpaperInfo.getComponent()) || mLockscreenWallpaper.getBitmap() != null) {
                return false;
            }
            return true;
        } catch (RemoteException unused) {
            return false;
        }
    }
}
