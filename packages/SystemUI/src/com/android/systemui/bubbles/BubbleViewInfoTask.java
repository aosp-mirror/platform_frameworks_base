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

import android.annotation.NonNull;
import android.app.Notification;
import android.app.Person;
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
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.util.PathParser;
import android.view.LayoutInflater;

import androidx.annotation.Nullable;

import com.android.internal.graphics.ColorUtils;
import com.android.launcher3.icons.BitmapInfo;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.phone.StatusBar;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;

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
    private boolean mSkipInflation;
    private Callback mCallback;

    /**
     * Creates a task to load information for the provided {@link Bubble}. Once all info
     * is loaded, {@link Callback} is notified.
     */
    BubbleViewInfoTask(Bubble b,
            Context context,
            BubbleStackView stackView,
            BubbleIconFactory factory,
            boolean skipInflation,
            Callback c) {
        mBubble = b;
        mContext = new WeakReference<>(context);
        mStackView = new WeakReference<>(stackView);
        mIconFactory = factory;
        mSkipInflation = skipInflation;
        mCallback = c;
    }

    @Override
    protected BubbleViewInfo doInBackground(Void... voids) {
        return BubbleViewInfo.populate(mContext.get(), mStackView.get(), mIconFactory, mBubble,
                mSkipInflation);
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

    /**
     * Info necessary to render a bubble.
     */
    static class BubbleViewInfo {
        BadgedImageView imageView;
        BubbleExpandedView expandedView;
        ShortcutInfo shortcutInfo;
        String appName;
        Bitmap badgedBubbleImage;
        Drawable badgedAppIcon;
        int dotColor;
        Path dotPath;
        Bubble.FlyoutMessage flyoutMessage;

        @Nullable
        static BubbleViewInfo populate(Context c, BubbleStackView stackView,
                BubbleIconFactory iconFactory, Bubble b, boolean skipInflation) {
            BubbleViewInfo info = new BubbleViewInfo();

            // View inflation: only should do this once per bubble
            if (!skipInflation && !b.isInflated()) {
                LayoutInflater inflater = LayoutInflater.from(c);
                info.imageView = (BadgedImageView) inflater.inflate(
                        R.layout.bubble_view, stackView, false /* attachToRoot */);

                info.expandedView = (BubbleExpandedView) inflater.inflate(
                        R.layout.bubble_expanded_view, stackView, false /* attachToRoot */);
                info.expandedView.setStackView(stackView);
            }

            if (b.getShortcutInfo() != null) {
                info.shortcutInfo = b.getShortcutInfo();
            }

            // App name & app icon
            PackageManager pm = StatusBar.getPackageManagerForUser(
                    c, b.getUser().getIdentifier());
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
            info.badgedAppIcon = badgedIcon;
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

            // Flyout
            info.flyoutMessage = b.getFlyoutMessage();
            if (info.flyoutMessage != null) {
                info.flyoutMessage.senderAvatar =
                        loadSenderAvatar(c, info.flyoutMessage.senderIcon);
            }
            return info;
        }
    }


    /**
     * Returns our best guess for the most relevant text summary of the latest update to this
     * notification, based on its type. Returns null if there should not be an update message.
     */
    @NonNull
    static Bubble.FlyoutMessage extractFlyoutMessage(NotificationEntry entry) {
        Objects.requireNonNull(entry);
        final Notification underlyingNotif = entry.getSbn().getNotification();
        final Class<? extends Notification.Style> style = underlyingNotif.getNotificationStyle();

        Bubble.FlyoutMessage bubbleMessage = new Bubble.FlyoutMessage();
        bubbleMessage.isGroupChat = underlyingNotif.extras.getBoolean(
                Notification.EXTRA_IS_GROUP_CONVERSATION);
        try {
            if (Notification.BigTextStyle.class.equals(style)) {
                // Return the big text, it is big so probably important. If it's not there use the
                // normal text.
                CharSequence bigText =
                        underlyingNotif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                bubbleMessage.message = !TextUtils.isEmpty(bigText)
                        ? bigText
                        : underlyingNotif.extras.getCharSequence(Notification.EXTRA_TEXT);
                return bubbleMessage;
            } else if (Notification.MessagingStyle.class.equals(style)) {
                final List<Notification.MessagingStyle.Message> messages =
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                                (Parcelable[]) underlyingNotif.extras.get(
                                        Notification.EXTRA_MESSAGES));

                final Notification.MessagingStyle.Message latestMessage =
                        Notification.MessagingStyle.findLatestIncomingMessage(messages);
                if (latestMessage != null) {
                    bubbleMessage.message = latestMessage.getText();
                    Person sender = latestMessage.getSenderPerson();
                    bubbleMessage.senderName = sender != null ? sender.getName() : null;
                    bubbleMessage.senderAvatar = null;
                    bubbleMessage.senderIcon = sender != null ? sender.getIcon() : null;
                    return bubbleMessage;
                }
            } else if (Notification.InboxStyle.class.equals(style)) {
                CharSequence[] lines =
                        underlyingNotif.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);

                // Return the last line since it should be the most recent.
                if (lines != null && lines.length > 0) {
                    bubbleMessage.message = lines[lines.length - 1];
                    return bubbleMessage;
                }
            } else if (Notification.MediaStyle.class.equals(style)) {
                // Return nothing, media updates aren't typically useful as a text update.
                return bubbleMessage;
            } else {
                // Default to text extra.
                bubbleMessage.message =
                        underlyingNotif.extras.getCharSequence(Notification.EXTRA_TEXT);
                return bubbleMessage;
            }
        } catch (ClassCastException | NullPointerException | ArrayIndexOutOfBoundsException e) {
            // No use crashing, we'll just return null and the caller will assume there's no update
            // message.
            e.printStackTrace();
        }

        return bubbleMessage;
    }

    @Nullable
    static Drawable loadSenderAvatar(@NonNull final Context context, @Nullable final Icon icon) {
        Objects.requireNonNull(context);
        if (icon == null) return null;
        if (icon.getType() == Icon.TYPE_URI || icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP) {
            context.grantUriPermission(context.getPackageName(),
                    icon.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return icon.loadDrawable(context);
    }
}
