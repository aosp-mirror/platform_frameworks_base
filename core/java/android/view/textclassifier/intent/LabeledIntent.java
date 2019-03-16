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
package android.view.textclassifier.intent;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.textclassifier.ExtrasUtils;
import android.view.textclassifier.Log;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassifier;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

/**
 * Helper class to store the information from which RemoteActions are built.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class LabeledIntent {
    private static final String TAG = "LabeledIntent";
    public static final int DEFAULT_REQUEST_CODE = 0;
    private static final TitleChooser DEFAULT_TITLE_CHOOSER =
            (labeledIntent, resolveInfo) -> {
                if (!TextUtils.isEmpty(labeledIntent.titleWithEntity)) {
                    return labeledIntent.titleWithEntity;
                }
                return labeledIntent.titleWithoutEntity;
            };

    @Nullable
    public final String titleWithoutEntity;
    @Nullable
    public final String titleWithEntity;
    public final String description;
    // Do not update this intent.
    public final Intent intent;
    public final int requestCode;

    /**
     * Initializes a LabeledIntent.
     *
     * <p>NOTE: {@code requestCode} is required to not be {@link #DEFAULT_REQUEST_CODE}
     * if distinguishing info (e.g. the classified text) is represented in intent extras only.
     * In such circumstances, the request code should represent the distinguishing info
     * (e.g. by generating a hashcode) so that the generated PendingIntent is (somewhat)
     * unique. To be correct, the PendingIntent should be definitely unique but we try a
     * best effort approach that avoids spamming the system with PendingIntents.
     */
    // TODO: Fix the issue mentioned above so the behaviour is correct.
    public LabeledIntent(
            @Nullable String titleWithoutEntity,
            @Nullable String titleWithEntity,
            String description,
            Intent intent,
            int requestCode) {
        if (TextUtils.isEmpty(titleWithEntity) && TextUtils.isEmpty(titleWithoutEntity)) {
            throw new IllegalArgumentException(
                    "titleWithEntity and titleWithoutEntity should not be both null");
        }
        this.titleWithoutEntity = titleWithoutEntity;
        this.titleWithEntity = titleWithEntity;
        this.description = Preconditions.checkNotNull(description);
        this.intent = Preconditions.checkNotNull(intent);
        this.requestCode = requestCode;
    }

    /**
     * Return the resolved result.
     *
     * @param context the context to resolve the result's intent and action
     * @param titleChooser for choosing an action title
     * @param textLanguagesBundle containing language detection information
     */
    @Nullable
    public Result resolve(
            Context context,
            @Nullable TitleChooser titleChooser,
            @Nullable Bundle textLanguagesBundle) {
        final PackageManager pm = context.getPackageManager();
        final ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);

        if (resolveInfo == null || resolveInfo.activityInfo == null) {
            Log.w(TAG, "resolveInfo or activityInfo is null");
            return null;
        }
        final String packageName = resolveInfo.activityInfo.packageName;
        final String className = resolveInfo.activityInfo.name;
        if (packageName == null || className == null) {
            Log.w(TAG, "packageName or className is null");
            return null;
        }
        Intent resolvedIntent = new Intent(intent);
        resolvedIntent.setComponent(new ComponentName(packageName, className));
        resolvedIntent.putExtra(
                TextClassifier.EXTRA_FROM_TEXT_CLASSIFIER,
                getFromTextClassifierExtra(textLanguagesBundle));

        boolean shouldShowIcon = false;
        Icon icon = null;
        if (!"android".equals(packageName)) {
            if (resolveInfo.activityInfo.getIconResource() != 0) {
                icon = Icon.createWithResource(
                        packageName, resolveInfo.activityInfo.getIconResource());
                shouldShowIcon = true;
            }
        }
        if (icon == null) {
            // RemoteAction requires that there be an icon.
            icon = Icon.createWithResource(
                    "android", com.android.internal.R.drawable.ic_more_items);
        }
        final PendingIntent pendingIntent =
                TextClassification.createPendingIntent(context, resolvedIntent, requestCode);
        titleChooser = titleChooser == null ? DEFAULT_TITLE_CHOOSER : titleChooser;
        CharSequence title = titleChooser.chooseTitle(this, resolveInfo);
        if (TextUtils.isEmpty(title)) {
            Log.w(TAG, "Custom titleChooser return null, fallback to the default titleChooser");
            title = DEFAULT_TITLE_CHOOSER.chooseTitle(this, resolveInfo);
        }
        final RemoteAction action = new RemoteAction(icon, title, description, pendingIntent);
        action.setShouldShowIcon(shouldShowIcon);
        return new Result(resolvedIntent, action);
    }

    private Bundle getFromTextClassifierExtra(@Nullable Bundle textLanguagesBundle) {
        if (textLanguagesBundle != null) {
            final Bundle bundle = new Bundle();
            ExtrasUtils.putTextLanguagesExtra(bundle, textLanguagesBundle);
            return bundle;
        } else {
            return Bundle.EMPTY;
        }
    }

    /**
     * Data class that holds the result.
     */
    public static final class Result {
        public final Intent resolvedIntent;
        public final RemoteAction remoteAction;

        public Result(Intent resolvedIntent, RemoteAction remoteAction) {
            this.resolvedIntent = Preconditions.checkNotNull(resolvedIntent);
            this.remoteAction = Preconditions.checkNotNull(remoteAction);
        }
    }

    /**
     * An object to choose a title from resolved info.  If {@code null} is returned,
     * {@link #titleWithEntity} will be used if it exists, {@link #titleWithoutEntity} otherwise.
     */
    public interface TitleChooser {
        /**
         * Picks a title from a {@link LabeledIntent} by looking into resolved info.
         * {@code resolveInfo} is guaranteed to have a non-null {@code activityInfo}.
         */
        @Nullable
        CharSequence chooseTitle(LabeledIntent labeledIntent, ResolveInfo resolveInfo);
    }
}
