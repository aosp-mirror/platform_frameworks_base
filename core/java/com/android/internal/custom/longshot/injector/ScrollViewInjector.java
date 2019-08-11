package com.android.internal.custom.longshot.injector;

import android.content.Context;

import com.android.internal.custom.longshot.LongScreenshotManager;

public class ScrollViewInjector {

    public static class ScrollView {
        private static final String TAG = "ScrollViewInjector";
        public static boolean isInjection = false;

        public static void onOverScrolled(Context context, boolean isOverScroll) {
            if (isInjection) {
                LongScreenshotManager sm = (LongScreenshotManager) context.getSystemService(Context.LONGSCREENSHOT_SERVICE);
                if (sm != null && sm.isLongshotMoveState()) {
                    sm.notifyLongshotScroll(isOverScroll);
                }
            }
        }
    }
}
