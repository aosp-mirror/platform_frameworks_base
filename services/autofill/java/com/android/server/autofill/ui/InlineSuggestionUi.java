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

package com.android.server.autofill.ui;

import static android.app.slice.SliceItem.FORMAT_IMAGE;
import static android.app.slice.SliceItem.FORMAT_TEXT;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.slice.Slice;
import android.app.slice.SliceItem;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.graphics.fonts.SystemFonts;
import android.os.IBinder;
import android.service.autofill.InlinePresentation;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This is a temporary inline suggestion UI inflater which will be replaced by the ExtServices
 * implementation.
 *
 * TODO(b/146453086): remove this class once autofill ext service is implemented.
 *
 * @hide
 */
public class InlineSuggestionUi {

    private static final String TAG = "InlineSuggestionUi";

    // The pattern to match the value can be obtained by calling {@code Resources#getResourceName
    // (int)}. This name is a single string of the form "package:type/entry".
    private static final Pattern RESOURCE_NAME_PATTERN = Pattern.compile("([^:]+):([^/]+)/(\\S+)");

    private final Context mContext;

    public InlineSuggestionUi(Context context) {
        this.mContext = context;
    }

    /**
     * Returns a {@link SurfaceControl} with the inflated content embedded in it.
     */
    @MainThread
    @Nullable
    public SurfaceControl inflate(@NonNull InlinePresentation inlinePresentation, int width,
            int height, @Nullable View.OnClickListener onClickListener) {
        Log.d(TAG, "Inflating the inline suggestion UI");

        //TODO(b/137800469): Pass in inputToken from IME.
        final SurfaceControlViewHost wvr = new SurfaceControlViewHost(mContext,
                mContext.getDisplay(), (IBinder) null);
        final SurfaceControl sc = wvr.getSurfacePackage().getSurfaceControl();

        Context contextThemeWrapper = getContextThemeWrapper(mContext,
                inlinePresentation.getInlinePresentationSpec().getStyle());
        if (contextThemeWrapper == null) {
            contextThemeWrapper = getDefaultContextThemeWrapper(mContext);
        }
        final View suggestionView = renderSlice(inlinePresentation.getSlice(),
                contextThemeWrapper);
        if (onClickListener != null) {
            suggestionView.setOnClickListener(onClickListener);
        }

        WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION, 0,
                        PixelFormat.TRANSPARENT);
        wvr.addView(suggestionView, lp);
        return sc;
    }

    private static View renderSlice(Slice slice, Context context) {
        final LayoutInflater inflater = LayoutInflater.from(context);
        final ViewGroup suggestionView =
                (ViewGroup) inflater.inflate(R.layout.autofill_inline_suggestion, null);

        final ImageView startIconView =
                suggestionView.findViewById(R.id.autofill_inline_suggestion_start_icon);
        final TextView titleView =
                suggestionView.findViewById(R.id.autofill_inline_suggestion_title);
        final TextView subtitleView =
                suggestionView.findViewById(R.id.autofill_inline_suggestion_subtitle);
        final ImageView endIconView =
                suggestionView.findViewById(R.id.autofill_inline_suggestion_end_icon);

        boolean hasStartIcon = false;
        boolean hasEndIcon = false;
        boolean hasSubtitle = false;
        final List<SliceItem> sliceItems = slice.getItems();
        for (int i = 0; i < sliceItems.size(); i++) {
            final SliceItem sliceItem = sliceItems.get(i);
            if (sliceItem.getFormat().equals(FORMAT_IMAGE)) {
                final Icon sliceIcon = sliceItem.getIcon();
                if (i == 0) { // start icon
                    startIconView.setImageIcon(sliceIcon);
                    hasStartIcon = true;
                } else { // end icon
                    endIconView.setImageIcon(sliceIcon);
                    hasEndIcon = true;
                }
            } else if (sliceItem.getFormat().equals(FORMAT_TEXT)) {
                final List<String> sliceHints = sliceItem.getHints();
                final String sliceText = sliceItem.getText().toString();
                if (sliceHints.contains("inline_title")) { // title
                    titleView.setText(sliceText);
                } else { // subtitle
                    subtitleView.setText(sliceText);
                    hasSubtitle = true;
                }
            }
        }
        if (!hasStartIcon) {
            startIconView.setVisibility(View.GONE);
        }
        if (!hasEndIcon) {
            endIconView.setVisibility(View.GONE);
        }
        if (!hasSubtitle) {
            subtitleView.setVisibility(View.GONE);
        }

        return suggestionView;
    }

    private Context getDefaultContextThemeWrapper(@NonNull Context context) {
        Resources.Theme theme = context.getResources().newTheme();
        theme.applyStyle(android.R.style.Theme_AutofillInlineSuggestion, true);
        return new ContextThemeWrapper(context, theme);
    }

    /**
     * Returns a context wrapping the theme in the provided {@code style}, or null if {@code
     * style} doesn't pass validation.
     */
    @Nullable
    private static Context getContextThemeWrapper(@NonNull Context context,
            @Nullable String style) {
        if (style == null) {
            return null;
        }
        Matcher matcher = RESOURCE_NAME_PATTERN.matcher(style);
        if (!matcher.matches()) {
            Log.d(TAG, "Can not parse the style=" + style);
            return null;
        }
        String packageName = matcher.group(1);
        String type = matcher.group(2);
        String entry = matcher.group(3);
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(type) || TextUtils.isEmpty(entry)) {
            Log.d(TAG, "Can not proceed with empty field values in the style=" + style);
            return null;
        }
        Resources resources = null;
        try {
            resources = context.getPackageManager().getResourcesForApplication(
                    packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        int resId = resources.getIdentifier(entry, type, packageName);
        if (resId == Resources.ID_NULL) {
            return null;
        }
        Resources.Theme theme = resources.newTheme();
        theme.applyStyle(resId, true);
        if (!validateBaseTheme(theme, resId)) {
            Log.d(TAG, "Provided theme is not a child of Theme.InlineSuggestion, ignoring it.");
            return null;
        }
        if (!validateFontFamilyForTextViewStyles(theme)) {
            Log.d(TAG,
                    "Provided theme specifies a font family that is not system font, ignoring it.");
            return null;
        }
        return new ContextThemeWrapper(context, theme);
    }

    private static boolean validateFontFamilyForTextViewStyles(Resources.Theme theme) {
        return validateFontFamily(theme, android.R.attr.autofillInlineSuggestionTitle)
                && validateFontFamily(theme, android.R.attr.autofillInlineSuggestionSubtitle);
    }

    private static boolean validateFontFamily(Resources.Theme theme, int styleAttr) {
        TypedArray ta = null;
        try {
            ta = theme.obtainStyledAttributes(null, new int[]{android.R.attr.fontFamily},
                    styleAttr,
                    0);
            if (ta.getIndexCount() == 0) {
                return true;
            }
            String fontFamily = ta.getString(ta.getIndex(0));
            return SystemFonts.getRawSystemFallbackMap().containsKey(fontFamily);
        } finally {
            if (ta != null) {
                ta.recycle();
            }
        }
    }

    private static boolean validateBaseTheme(Resources.Theme theme, int styleAttr) {
        TypedArray ta = null;
        try {
            ta = theme.obtainStyledAttributes(null,
                    new int[]{android.R.attr.isAutofillInlineSuggestionTheme}, styleAttr, 0);
            if (ta.getIndexCount() == 0) {
                return false;
            }
            return ta.getBoolean(ta.getIndex(0), false);
        } finally {
            if (ta != null) {
                ta.recycle();
            }
        }
    }
}
