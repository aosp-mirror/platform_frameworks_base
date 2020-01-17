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
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.util.Log;
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

    private final Context mContext;

    public InlineSuggestionUi(Context context) {
        this.mContext = context;
    }

    /**
     * Returns a {@link SurfaceControl} with the inflated content embedded in it.
     */
    @MainThread
    @Nullable
    public SurfaceControl inflate(@NonNull Slice slice, int width, int height,
            @Nullable View.OnClickListener onClickListener) {
        Log.d(TAG, "Inflating the inline suggestion UI");

        //TODO(b/137800469): Pass in inputToken from IME.
        final SurfaceControlViewHost wvr = new SurfaceControlViewHost(mContext,
                mContext.getDisplay(), (IBinder) null);
        final SurfaceControl sc = wvr.getSurfacePackage().getSurfaceControl();
        final ViewGroup suggestionView = (ViewGroup) renderSlice(slice);
        if (onClickListener != null) {
            suggestionView.setOnClickListener(onClickListener);
        }

        WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(width, height,
                        WindowManager.LayoutParams.TYPE_APPLICATION, 0, PixelFormat.TRANSPARENT);
        wvr.addView(suggestionView, lp);
        return sc;
    }

    private View renderSlice(Slice slice) {
        final LayoutInflater inflater = LayoutInflater.from(mContext);
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
}
