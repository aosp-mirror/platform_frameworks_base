/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.autofill.ui;

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.autofill.BatchUpdates;
import android.service.autofill.CustomDescription;
import android.service.autofill.InternalTransformation;
import android.service.autofill.InternalValidator;
import android.service.autofill.SaveInfo;
import android.service.autofill.ValueFinder;
import android.text.Html;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.UiThread;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Autofill Save Prompt
 */
final class SaveUi {

    private static final String TAG = "AutofillSaveUi";

    private static final int THEME_ID =
            com.android.internal.R.style.Theme_DeviceDefault_Autofill_Save;

    public interface OnSaveListener {
        void onSave();
        void onCancel(IntentSender listener);
        void onDestroy();
    }

    private class OneTimeListener implements OnSaveListener {

        private final OnSaveListener mRealListener;
        private boolean mDone;

        OneTimeListener(OnSaveListener realListener) {
            mRealListener = realListener;
        }

        @Override
        public void onSave() {
            if (sDebug) Slog.d(TAG, "OneTimeListener.onSave(): " + mDone);
            if (mDone) {
                return;
            }
            mDone = true;
            mRealListener.onSave();
        }

        @Override
        public void onCancel(IntentSender listener) {
            if (sDebug) Slog.d(TAG, "OneTimeListener.onCancel(): " + mDone);
            if (mDone) {
                return;
            }
            mDone = true;
            mRealListener.onCancel(listener);
        }

        @Override
        public void onDestroy() {
            if (sDebug) Slog.d(TAG, "OneTimeListener.onDestroy(): " + mDone);
            if (mDone) {
                return;
            }
            mDone = true;
            mRealListener.onDestroy();
        }
    }

    private final Handler mHandler = UiThread.getHandler();
    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    private final @NonNull Dialog mDialog;

    private final @NonNull OneTimeListener mListener;

    private final @NonNull OverlayControl mOverlayControl;

    private final CharSequence mTitle;
    private final CharSequence mSubTitle;
    private final PendingUi mPendingUi;
    private final String mServicePackageName;
    private final String mPackageName;

    private boolean mDestroyed;

    SaveUi(@NonNull Context context, @NonNull PendingUi pendingUi,
           @NonNull CharSequence serviceLabel, @NonNull Drawable serviceIcon,
           @Nullable String servicePackageName, @NonNull String packageName,
           @NonNull SaveInfo info, @NonNull ValueFinder valueFinder,
           @NonNull OverlayControl overlayControl, @NonNull OnSaveListener listener) {
        mPendingUi= pendingUi;
        mListener = new OneTimeListener(listener);
        mOverlayControl = overlayControl;
        mServicePackageName = servicePackageName;
        mPackageName = packageName;

        context = new ContextThemeWrapper(context, THEME_ID);
        final LayoutInflater inflater = LayoutInflater.from(context);
        final View view = inflater.inflate(R.layout.autofill_save, null);

        final TextView titleView = view.findViewById(R.id.autofill_save_title);

        final ArraySet<String> types = new ArraySet<>(3);
        final int type = info.getType();

        if ((type & SaveInfo.SAVE_DATA_TYPE_PASSWORD) != 0) {
            types.add(context.getString(R.string.autofill_save_type_password));
        }
        if ((type & SaveInfo.SAVE_DATA_TYPE_ADDRESS) != 0) {
            types.add(context.getString(R.string.autofill_save_type_address));
        }
        if ((type & SaveInfo.SAVE_DATA_TYPE_CREDIT_CARD) != 0) {
            types.add(context.getString(R.string.autofill_save_type_credit_card));
        }
        if ((type & SaveInfo.SAVE_DATA_TYPE_USERNAME) != 0) {
            types.add(context.getString(R.string.autofill_save_type_username));
        }
        if ((type & SaveInfo.SAVE_DATA_TYPE_EMAIL_ADDRESS) != 0) {
            types.add(context.getString(R.string.autofill_save_type_email_address));
        }

        switch (types.size()) {
            case 1:
                mTitle = Html.fromHtml(context.getString(R.string.autofill_save_title_with_type,
                        types.valueAt(0), serviceLabel), 0);
                break;
            case 2:
                mTitle = Html.fromHtml(context.getString(R.string.autofill_save_title_with_2types,
                        types.valueAt(0), types.valueAt(1), serviceLabel), 0);
                break;
            case 3:
                mTitle = Html.fromHtml(context.getString(R.string.autofill_save_title_with_3types,
                        types.valueAt(0), types.valueAt(1), types.valueAt(2), serviceLabel), 0);
                break;
            default:
                // Use generic if more than 3 or invalid type (size 0).
                mTitle = Html.fromHtml(
                        context.getString(R.string.autofill_save_title, serviceLabel), 0);
        }
        titleView.setText(mTitle);

        setServiceIcon(context, view, serviceIcon);

        final boolean hasCustomDescription =
                applyCustomDescription(context, view, valueFinder, info);
        if (hasCustomDescription) {
            mSubTitle = null;
            if (sDebug) Slog.d(TAG, "on constructor: applied custom description");
        } else {
            mSubTitle = info.getDescription();
            if (mSubTitle != null) {
                writeLog(MetricsEvent.AUTOFILL_SAVE_CUSTOM_SUBTITLE, type);
                final ScrollView subtitleContainer =
                        view.findViewById(R.id.autofill_save_custom_subtitle);
                final TextView subtitleView = new TextView(context);
                subtitleView.setText(mSubTitle);
                subtitleContainer.addView(subtitleView,
                        new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
                subtitleContainer.setVisibility(View.VISIBLE);
            }
            if (sDebug) Slog.d(TAG, "on constructor: title=" + mTitle + ", subTitle=" + mSubTitle);
        }

        final TextView noButton = view.findViewById(R.id.autofill_save_no);
        if (info.getNegativeActionStyle() == SaveInfo.NEGATIVE_BUTTON_STYLE_REJECT) {
            noButton.setText(R.string.save_password_notnow);
        } else {
            noButton.setText(R.string.autofill_save_no);
        }
        noButton.setOnClickListener((v) -> mListener.onCancel(info.getNegativeActionListener()));

        final View yesButton = view.findViewById(R.id.autofill_save_yes);
        yesButton.setOnClickListener((v) -> mListener.onSave());

        mDialog = new Dialog(context, THEME_ID);
        mDialog.setContentView(view);

        // Dialog can be dismissed when touched outside, but the negative listener should not be
        // notified (hence the null argument).
        mDialog.setOnDismissListener((d) -> mListener.onCancel(null));

        final Window window = mDialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER);
        window.setCloseOnTouchOutside(true);
        final WindowManager.LayoutParams params = window.getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.accessibilityTitle = context.getString(R.string.autofill_save_accessibility_title);
        params.windowAnimations = R.style.AutofillSaveAnimation;

        show();
    }

    private boolean applyCustomDescription(@NonNull Context context, @NonNull View saveUiView,
            @NonNull ValueFinder valueFinder, @NonNull SaveInfo info) {
        final CustomDescription customDescription = info.getCustomDescription();
        if (customDescription == null) {
            return false;
        }
        final int type = info.getType();
        writeLog(MetricsEvent.AUTOFILL_SAVE_CUSTOM_DESCRIPTION, type);

        final RemoteViews template = customDescription.getPresentation();
        if (template == null) {
            Slog.w(TAG, "No remote view on custom description");
            return false;
        }

        // First apply the unconditional transformations (if any) to the templates.
        final ArrayList<Pair<Integer, InternalTransformation>> transformations =
                customDescription.getTransformations();
        if (transformations != null) {
            if (!InternalTransformation.batchApply(valueFinder, template, transformations)) {
                Slog.w(TAG, "could not apply main transformations on custom description");
                return false;
            }
        }

        final RemoteViews.OnClickHandler handler = new RemoteViews.OnClickHandler() {
            @Override
            public boolean onClickHandler(View view, PendingIntent pendingIntent,
                    Intent intent) {
                final LogMaker log =
                        newLogMaker(MetricsEvent.AUTOFILL_SAVE_LINK_TAPPED, type);
                // We need to hide the Save UI before launching the pending intent, and
                // restore back it once the activity is finished, and that's achieved by
                // adding a custom extra in the activity intent.
                final boolean isValid = isValidLink(pendingIntent, intent);
                if (!isValid) {
                    log.setType(MetricsEvent.TYPE_UNKNOWN);
                    mMetricsLogger.write(log);
                    return false;
                }
                if (sVerbose) Slog.v(TAG, "Intercepting custom description intent");
                final IBinder token = mPendingUi.getToken();
                intent.putExtra(AutofillManager.EXTRA_RESTORE_SESSION_TOKEN, token);
                try {
                    mPendingUi.client.startIntentSender(pendingIntent.getIntentSender(),
                            intent);
                    mPendingUi.setState(PendingUi.STATE_PENDING);
                    if (sDebug) Slog.d(TAG, "hiding UI until restored with token " + token);
                    hide();
                    log.setType(MetricsEvent.TYPE_OPEN);
                    mMetricsLogger.write(log);
                    return true;
                } catch (RemoteException e) {
                    Slog.w(TAG, "error triggering pending intent: " + intent);
                    log.setType(MetricsEvent.TYPE_FAILURE);
                    mMetricsLogger.write(log);
                    return false;
                }
            }
        };

        try {
            // Create the remote view peer.
            template.setApplyTheme(THEME_ID);
            final View customSubtitleView = template.apply(context, null, handler);

            // And apply batch updates (if any).
            final ArrayList<Pair<InternalValidator, BatchUpdates>> updates =
                    customDescription.getUpdates();
            if (updates != null) {
                final int size = updates.size();
                if (sDebug) Slog.d(TAG, "custom description has " + size + " batch updates");
                for (int i = 0; i < size; i++) {
                    final Pair<InternalValidator, BatchUpdates> pair = updates.get(i);
                    final InternalValidator condition = pair.first;
                    if (condition == null || !condition.isValid(valueFinder)) {
                        if (sDebug) Slog.d(TAG, "Skipping batch update #" + i );
                        continue;
                    }
                    final BatchUpdates batchUpdates = pair.second;
                    // First apply the updates...
                    final RemoteViews templateUpdates = batchUpdates.getUpdates();
                    if (templateUpdates != null) {
                        if (sDebug) Slog.d(TAG, "Applying template updates for batch update #" + i);
                        templateUpdates.reapply(context, customSubtitleView);
                    }
                    // Then the transformations...
                    final ArrayList<Pair<Integer, InternalTransformation>> batchTransformations =
                            batchUpdates.getTransformations();
                    if (batchTransformations != null) {
                        if (sDebug) {
                            Slog.d(TAG, "Applying child transformation for batch update #" + i
                                    + ": " + batchTransformations);
                        }
                        if (!InternalTransformation.batchApply(valueFinder, template,
                                batchTransformations)) {
                            Slog.w(TAG, "Could not apply child transformation for batch update "
                                    + "#" + i + ": " + batchTransformations);
                            return false;
                        }
                        template.reapply(context, customSubtitleView);
                    }
                }
            }

            // Finally, add the custom description to the save UI.
            final ScrollView subtitleContainer =
                    saveUiView.findViewById(R.id.autofill_save_custom_subtitle);
            subtitleContainer.addView(customSubtitleView);
            subtitleContainer.setVisibility(View.VISIBLE);
            return true;
        } catch (Exception e) {
            Slog.e(TAG, "Error applying custom description. ", e);
        }
        return false;
    }

    private void setServiceIcon(Context context, View view, Drawable serviceIcon) {
        final ImageView iconView = view.findViewById(R.id.autofill_save_icon);
        final Resources res = context.getResources();

        final int maxWidth = res.getDimensionPixelSize(R.dimen.autofill_save_icon_max_size);
        final int maxHeight = maxWidth;
        final int actualWidth = serviceIcon.getMinimumWidth();
        final int actualHeight = serviceIcon.getMinimumHeight();

        if (actualWidth <= maxWidth && actualHeight <= maxHeight) {
            if (sDebug) {
                Slog.d(TAG, "Adding service icon "
                        + "(" + actualWidth + "x" + actualHeight + ") as it's less than maximum "
                        + "(" + maxWidth + "x" + maxHeight + ").");
            }
            iconView.setImageDrawable(serviceIcon);
        } else {
            Slog.w(TAG, "Not adding service icon of size "
                    + "(" + actualWidth + "x" + actualHeight + ") because maximum is "
                    + "(" + maxWidth + "x" + maxHeight + ").");
            ((ViewGroup)iconView.getParent()).removeView(iconView);
        }
    }

    private static boolean isValidLink(PendingIntent pendingIntent, Intent intent) {
        if (pendingIntent == null) {
            Slog.w(TAG, "isValidLink(): custom description without pending intent");
            return false;
        }
        if (!pendingIntent.isActivity()) {
            Slog.w(TAG, "isValidLink(): pending intent not for activity");
            return false;
        }
        if (intent == null) {
            Slog.w(TAG, "isValidLink(): no intent");
            return false;
        }
        return true;
    }

    private LogMaker newLogMaker(int category, int saveType) {
        return newLogMaker(category)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SAVE_TYPE, saveType);
    }

    private LogMaker newLogMaker(int category) {
        return new LogMaker(category)
                .setPackageName(mPackageName)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE, mServicePackageName);
    }

    private void writeLog(int category, int saveType) {
        mMetricsLogger.write(newLogMaker(category, saveType));
    }

    /**
     * Update the pending UI, if any.
     *
     * @param operation how to update it.
     * @param token token associated with the pending UI - if it doesn't match the pending token,
     * the operation will be ignored.
     */
    void onPendingUi(int operation, @NonNull IBinder token) {
        if (!mPendingUi.matches(token)) {
            Slog.w(TAG, "restore(" + operation + "): got token " + token + " instead of "
                    + mPendingUi.getToken());
            return;
        }
        final LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_PENDING_SAVE_UI_OPERATION);
        try {
            switch (operation) {
                case AutofillManager.PENDING_UI_OPERATION_RESTORE:
                    if (sDebug) Slog.d(TAG, "Restoring save dialog for " + token);
                    log.setType(MetricsEvent.TYPE_OPEN);
                    show();
                    break;
                case AutofillManager.PENDING_UI_OPERATION_CANCEL:
                    log.setType(MetricsEvent.TYPE_DISMISS);
                    if (sDebug) Slog.d(TAG, "Cancelling pending save dialog for " + token);
                    hide();
                    break;
                default:
                    log.setType(MetricsEvent.TYPE_FAILURE);
                    Slog.w(TAG, "restore(): invalid operation " + operation);
            }
        } finally {
            mMetricsLogger.write(log);
        }
        mPendingUi.setState(PendingUi.STATE_FINISHED);
    }

    private void show() {
        Slog.i(TAG, "Showing save dialog: " + mTitle);
        mDialog.show();
        mOverlayControl.hideOverlays();
   }

    PendingUi hide() {
        if (sVerbose) Slog.v(TAG, "Hiding save dialog.");
        try {
            mDialog.hide();
        } finally {
            mOverlayControl.showOverlays();
        }
        return mPendingUi;
    }

    void destroy() {
        try {
            if (sDebug) Slog.d(TAG, "destroy()");
            throwIfDestroyed();
            mListener.onDestroy();
            mHandler.removeCallbacksAndMessages(mListener);
            mDialog.dismiss();
            mDestroyed = true;
        } finally {
            mOverlayControl.showOverlays();
        }
    }

    private void throwIfDestroyed() {
        if (mDestroyed) {
            throw new IllegalStateException("cannot interact with a destroyed instance");
        }
    }

    @Override
    public String toString() {
        return mTitle == null ? "NO TITLE" : mTitle.toString();
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("title: "); pw.println(mTitle);
        pw.print(prefix); pw.print("subtitle: "); pw.println(mSubTitle);
        pw.print(prefix); pw.print("pendingUi: "); pw.println(mPendingUi);
        pw.print(prefix); pw.print("service: "); pw.println(mServicePackageName);
        pw.print(prefix); pw.print("app: "); pw.println(mPackageName);

        final View view = mDialog.getWindow().getDecorView();
        final int[] loc = view.getLocationOnScreen();
        pw.print(prefix); pw.print("coordinates: ");
            pw.print('('); pw.print(loc[0]); pw.print(','); pw.print(loc[1]);pw.print(')');
            pw.print('(');
                pw.print(loc[0] + view.getWidth()); pw.print(',');
                pw.print(loc[1] + view.getHeight());pw.println(')');
        pw.print(prefix); pw.print("destroyed: "); pw.println(mDestroyed);
    }
}
