/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.app;

import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.os.Bundle;
import android.view.View;

/**
 * Media route chooser dialog fragment.
 * <p>
 * Creates a {@link MediaRouteChooserDialog}.  The application may subclass
 * this dialog fragment to customize the media route chooser dialog.
 * </p>
 *
 * TODO: Move this back into the API, as in the support library media router.
 */
public class MediaRouteChooserDialogFragment extends DialogFragment {
    private final String ARGUMENT_ROUTE_TYPES = "routeTypes";

    private View.OnClickListener mExtendedSettingsClickListener;

    /**
     * Creates a media route chooser dialog fragment.
     * <p>
     * All subclasses of this class must also possess a default constructor.
     * </p>
     */
    public MediaRouteChooserDialogFragment() {
        int theme = MediaRouteChooserDialog.isLightTheme(getContext())
                ? android.R.style.Theme_DeviceDefault_Light_Dialog
                : android.R.style.Theme_DeviceDefault_Dialog;

        setCancelable(true);
        setStyle(STYLE_NORMAL, theme);
    }

    public int getRouteTypes() {
        Bundle args = getArguments();
        return args != null ? args.getInt(ARGUMENT_ROUTE_TYPES) : 0;
    }

    public void setRouteTypes(int types) {
        if (types != getRouteTypes()) {
            Bundle args = getArguments();
            if (args == null) {
                args = new Bundle();
            }
            args.putInt(ARGUMENT_ROUTE_TYPES, types);
            setArguments(args);

            MediaRouteChooserDialog dialog = (MediaRouteChooserDialog)getDialog();
            if (dialog != null) {
                dialog.setRouteTypes(types);
            }
        }
    }

    public void setExtendedSettingsClickListener(View.OnClickListener listener) {
        if (listener != mExtendedSettingsClickListener) {
            mExtendedSettingsClickListener = listener;

            MediaRouteChooserDialog dialog = (MediaRouteChooserDialog)getDialog();
            if (dialog != null) {
                dialog.setExtendedSettingsClickListener(listener);
            }
        }
    }

    /**
     * Called when the chooser dialog is being created.
     * <p>
     * Subclasses may override this method to customize the dialog.
     * </p>
     */
    public MediaRouteChooserDialog onCreateChooserDialog(
            Context context, Bundle savedInstanceState) {
        return new MediaRouteChooserDialog(context, getTheme());
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        MediaRouteChooserDialog dialog = onCreateChooserDialog(getActivity(), savedInstanceState);
        dialog.setRouteTypes(getRouteTypes());
        dialog.setExtendedSettingsClickListener(mExtendedSettingsClickListener);
        return dialog;
    }
}
