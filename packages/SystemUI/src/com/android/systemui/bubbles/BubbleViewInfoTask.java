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

package com.android.systemui.bubbles;

import static com.android.systemui.bubbles.BadgedImageView.DEFAULT_PATH_SIZE;
import static com.android.systemui.bubbles.BadgedImageView.WHITE_SCRIM_ALPHA;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.PathParser;
import android.view.LayoutInflater;

import androidx.annotation.Nullable;

import com.android.internal.graphics.ColorUtils;
import com.android.launcher3.icons.BitmapInfo;
import com.android.systemui.R;

import java.lang.ref.WeakReference;

/**
 * Simple task to inflate views & load necessary info to display a bubble.
 */
public class BubbleViewInfoTask extends AsyncTask<Void, Void, BubbleViewInfoTask.BubbleViewInfo> {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleViewInfoTask" : TAG_BUBBLES;


    /**
     * Callback to find out when the bubble has been inflated & necessary data loaded.
     */
    public interface Callback {
        /**
         * Called when data has been loaded for the bubble.
         */
        void onBubbleViewsReady(Bubble bubble);
    }

    private Bubble mBubble;
    private WeakReference<Context> mContext;
    private WeakReference<BubbleStackView> mStackView;
    private BubbleIconFactory mIconFactory;
    private Callback mCallback;

    /**
     * Creates a task to load information for the provided {@link Bubble}. Once all info
     * is loaded, {@link Callback} is notified.
     */
    BubbleViewInfoTask(Bubble b,
            Context context,
            BubbleStackView stackView,
            BubbleIconFactory factory,
            Callback c) {
        mBubble = b;
        mContext = new WeakReference<>(context);
        mStackView = new WeakReference<>(stackView);
        mIconFactory = factory;
        mCallback = c;
    }

    @Override
    protected BubbleViewInfo doInBackground(Void... voids) {
        return BubbleViewInfo.populate(mContext.get(), mStackView.get(), mIconFactory, mBubble);
    }

    @Override
    protected void onPostExecute(BubbleViewInfo viewInfo) {
        if (viewInfo != null) {
            mBubble.setViewInfo(viewInfo);
            if (mCallback != null && !isCancelled()) {
                mCallback.onBubbleViewsReady(mBubble);
            }
        }
    }

    static class BubbleViewInfo {
        BadgedImageView imageView;
        BubbleExpandedView expandedView;
        ShortcutInfo shortcutInfo;
        String appName;
        Bitmap badgedBubbleImage;
        int dotColor;
        Path dotPath;

        @Nullable
        static BubbleViewInfo populate(Context c, BubbleStackView stackView,
                BubbleIconFactory iconFactory, Bubble b) {
            BubbleViewInfo info = new BubbleViewInfo();

            // View inflation: only should do this once per bubble
            if (!b.isInflated()) {
                LayoutInflater inflater = LayoutInflater.from(c);
                info.imageView = (BadgedImageView) inflater.inflate(
                        R.layout.bubble_view, stackView, false /* attachToRoot */);

                info.expandedView = (BubbleExpandedView) inflater.inflate(
                        R.layout.bubble_expanded_view, stackView, false /* attachToRoot */);
                info.expandedView.setStackView(stackView);
            }

            StatusBarNotification sbn = b.getEntry().getSbn();
            String packageName = sbn.getPackageName();

            // Shortcut info for this bubble
            String shortcutId = sbn.getNotification().getShortcutId();
            if (BubbleExperimentConfig.useShortcutInfoToBubble(c)
                    && shortcutId != null) {
                info.shortcutInfo = BubbleExperimentConfig.getShortcutInfo(c,
                        packageName,
                        sbn.getUser(), shortcutId);
            }

            // App name & app icon
            PackageManager pm = c.getPackageManager();
            ApplicationInfo appInfo;
            Drawable badgedIcon;
            try {
                appInfo = pm.getApplicationInfo(
                        packageName,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES
                                | PackageManager.MATCH_DISABLED_COMPONENTS
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                                | PackageManager.MATCH_DIRECT_BOOT_AWARE);
                if (appInfo != null) {
                    info.appName = String.valueOf(pm.getApplicationLabel(appInfo));
                }
                Drawable appIcon = pm.getApplicationIcon(packageName);
                badgedIcon = pm.getUserBadgedIcon(appIcon, sbn.getUser());
            } catch (PackageManager.NameNotFoundException exception) {
                // If we can't find package... don't think we should show the bubble.
                Log.w(TAG, "Unable to find package: " + packageName);
                return null;
            }

            // Badged bubble image
            Drawable bubbleDrawable = iconFactory.getBubbleDrawable(b, c);
            BitmapInfo badgeBitmapInfo = iconFactory.getBadgeBitmap(badgedIcon);
            info.badgedBubbleImage = iconFactory.getBubbleBitmap(bubbleDrawable,
                    badgeBitmapInfo).icon;

            // Dot color & placement
            Path iconPath = PathParser.createPathFromPathData(
                    c.getResources().getString(com.android.internal.R.string.config_icon_mask));
            Matrix matrix = new Matrix();
            float scale = iconFactory.getNormalizer().getScale(bubbleDrawable,
                    null /* outBounds */, null /* path */, null /* outMaskShape */);
            float radius = DEFAULT_PATH_SIZE / 2f;
            matrix.setScale(scale /* x scale */, scale /* y scale */, radius /* pivot x */,
                    radius /* pivot y */);
            iconPath.transform(matrix);
            info.dotPath = iconPath;
            info.dotColor = ColorUtils.blendARGB(badgeBitmapInfo.color,
                    Color.WHITE, WHITE_SCRIM_ALPHA);
            return info;
        }
    }
}
