/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.bubbles;

import static com.android.wm.shell.bubbles.BadgedImageView.DEFAULT_PATH_SIZE;
import static com.android.wm.shell.bubbles.BadgedImageView.WHITE_SCRIM_ALPHA;
import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.AsyncTask;
import android.util.Log;
import android.util.PathParser;
import android.view.LayoutInflater;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.graphics.ColorUtils;
import com.android.launcher3.icons.BitmapInfo;
import com.android.wm.shell.R;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.Executor;

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
    private WeakReference<BubbleController> mController;
    private WeakReference<BubbleStackView> mStackView;
    private BubbleIconFactory mIconFactory;
    private boolean mSkipInflation;
    private Callback mCallback;
    private Executor mMainExecutor;

    /**
     * Creates a task to load information for the provided {@link Bubble}. Once all info
     * is loaded, {@link Callback} is notified.
     */
    BubbleViewInfoTask(Bubble b,
            Context context,
            BubbleController controller,
            BubbleStackView stackView,
            BubbleIconFactory factory,
            boolean skipInflation,
            Callback c,
            Executor mainExecutor) {
        mBubble = b;
        mContext = new WeakReference<>(context);
        mController = new WeakReference<>(controller);
        mStackView = new WeakReference<>(stackView);
        mIconFactory = factory;
        mSkipInflation = skipInflation;
        mCallback = c;
        mMainExecutor = mainExecutor;
    }

    @Override
    protected BubbleViewInfo doInBackground(Void... voids) {
        return BubbleViewInfo.populate(mContext.get(), mController.get(), mStackView.get(),
                mIconFactory, mBubble, mSkipInflation);
    }

    @Override
    protected void onPostExecute(BubbleViewInfo viewInfo) {
        if (isCancelled() || viewInfo == null) {
            return;
        }
        mMainExecutor.execute(() -> {
            mBubble.setViewInfo(viewInfo);
            if (mCallback != null) {
                mCallback.onBubbleViewsReady(mBubble);
            }
        });
    }

    /**
     * Info necessary to render a bubble.
     */
    @VisibleForTesting
    public static class BubbleViewInfo {
        BadgedImageView imageView;
        BubbleExpandedView expandedView;
        ShortcutInfo shortcutInfo;
        String appName;
        Bitmap bubbleBitmap;
        Bitmap badgeBitmap;
        int dotColor;
        Path dotPath;
        Bubble.FlyoutMessage flyoutMessage;

        @VisibleForTesting
        @Nullable
        public static BubbleViewInfo populate(Context c, BubbleController controller,
                BubbleStackView stackView, BubbleIconFactory iconFactory, Bubble b,
                boolean skipInflation) {
            BubbleViewInfo info = new BubbleViewInfo();

            // View inflation: only should do this once per bubble
            if (!skipInflation && !b.isInflated()) {
                LayoutInflater inflater = LayoutInflater.from(c);
                info.imageView = (BadgedImageView) inflater.inflate(
                        R.layout.bubble_view, stackView, false /* attachToRoot */);
                info.imageView.initialize(controller.getPositioner());

                info.expandedView = (BubbleExpandedView) inflater.inflate(
                        R.layout.bubble_expanded_view, stackView, false /* attachToRoot */);
                info.expandedView.initialize(controller, stackView, false /* isOverflow */);
            }

            if (b.getShortcutInfo() != null) {
                info.shortcutInfo = b.getShortcutInfo();
            }

            // App name & app icon
            PackageManager pm = BubbleController.getPackageManagerForUser(c,
                    b.getUser().getIdentifier());
            ApplicationInfo appInfo;
            Drawable badgedIcon;
            Drawable appIcon;
            try {
                appInfo = pm.getApplicationInfo(
                        b.getPackageName(),
                        PackageManager.MATCH_UNINSTALLED_PACKAGES
                                | PackageManager.MATCH_DISABLED_COMPONENTS
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                                | PackageManager.MATCH_DIRECT_BOOT_AWARE);
                if (appInfo != null) {
                    info.appName = String.valueOf(pm.getApplicationLabel(appInfo));
                }
                appIcon = pm.getApplicationIcon(b.getPackageName());
                badgedIcon = pm.getUserBadgedIcon(appIcon, b.getUser());
            } catch (PackageManager.NameNotFoundException exception) {
                // If we can't find package... don't think we should show the bubble.
                Log.w(TAG, "Unable to find package: " + b.getPackageName());
                return null;
            }

            // Badged bubble image
            Drawable bubbleDrawable = iconFactory.getBubbleDrawable(c, info.shortcutInfo,
                    b.getIcon());
            if (bubbleDrawable == null) {
                // Default to app icon
                bubbleDrawable = appIcon;
            }

            BitmapInfo badgeBitmapInfo = iconFactory.getBadgeBitmap(badgedIcon,
                    b.isImportantConversation());
            info.badgeBitmap = badgeBitmapInfo.icon;
            info.bubbleBitmap = iconFactory.createBadgedIconBitmap(bubbleDrawable,
                    null /* user */,
                    true /* shrinkNonAdaptiveIcons */).icon;

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

            // Flyout
            info.flyoutMessage = b.getFlyoutMessage();
            if (info.flyoutMessage != null) {
                info.flyoutMessage.senderAvatar =
                        loadSenderAvatar(c, info.flyoutMessage.senderIcon);
            }
            return info;
        }
    }

    @Nullable
    static Drawable loadSenderAvatar(@NonNull final Context context, @Nullable final Icon icon) {
        Objects.requireNonNull(context);
        if (icon == null) return null;
        try {
            if (icon.getType() == Icon.TYPE_URI
                    || icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP) {
                context.grantUriPermission(context.getPackageName(),
                        icon.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            return icon.loadDrawable(context);
        } catch (Exception e) {
            Log.w(TAG, "loadSenderAvatar failed: " + e.getMessage());
            return null;
        }
    }
}
