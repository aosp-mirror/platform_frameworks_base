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

/**
 * Media route controller dialog fragment.
 * <p>
 * Creates a {@link MediaRouteControllerDialog}.  The application may subclass
 * this dialog fragment to customize the media route controller dialog.
 * </p>
 *
 * TODO: Move this back into the API, as in the support library media router.
 */
public class MediaRouteControllerDialogFragment extends DialogFragment {
    /**
     * Creates a media route controller dialog fragment.
     * <p>
     * All subclasses of this class must also possess a default constructor.
     * </p>
     */
    public MediaRouteControllerDialogFragment() {
        setCancelable(true);
        setStyle(STYLE_NORMAL, android.R.style.Theme_DeviceDefault_Dialog);
    }

    /**
     * Called when the controller dialog is being created.
     * <p>
     * Subclasses may override this method to customize the dialog.
     * </p>
     */
    public MediaRouteControllerDialog onCreateControllerDialog(
            Context context, Bundle savedInstanceState) {
        return new MediaRouteControllerDialog(context, getTheme());
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        return onCreateControllerDialog(getActivity(), savedInstanceState);
    }
}
