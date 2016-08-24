/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import android.annotation.Nullable;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * A message bar displaying some info/error messages and a Dismiss button.
 */
public class MessageBar extends Fragment {
    private View mView;
    private ViewGroup mContainer;

    /**
     * Creates an instance of a MessageBar. Note that the new MessagBar is not visible by default,
     * and has to be shown by calling MessageBar.show.
     */
    public static MessageBar create(FragmentManager fm) {
        final MessageBar fragment = new MessageBar();

        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_message_bar, fragment);
        ft.commitAllowingStateLoss();

        return fragment;
    }

    /**
     * Sets the info message. Can be null, in which case no info message will be displayed. The
     * message bar layout will be adjusted accordingly.
     */
    public void setInfo(@Nullable String info) {
        View infoContainer = mView.findViewById(R.id.container_info);
        if (info != null) {
            TextView infoText = (TextView) mView.findViewById(R.id.textview_info);
            infoText.setText(info);
            infoContainer.setVisibility(View.VISIBLE);
        } else {
            infoContainer.setVisibility(View.GONE);
        }
    }

    /**
     * Sets the error message. Can be null, in which case no error message will be displayed. The
     * message bar layout will be adjusted accordingly.
     */
    public void setError(@Nullable String error) {
        View errorView = mView.findViewById(R.id.container_error);
        if (error != null) {
            TextView errorText = (TextView) mView.findViewById(R.id.textview_error);
            errorText.setText(error);
            errorView.setVisibility(View.VISIBLE);
        } else {
            errorView.setVisibility(View.GONE);
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        mView = inflater.inflate(R.layout.fragment_message_bar, container, false);

        ImageView infoIcon = (ImageView) mView.findViewById(R.id.icon_info);
        infoIcon.setImageResource(R.drawable.ic_dialog_info);

        ImageView errorIcon = (ImageView) mView.findViewById(R.id.icon_error);
        errorIcon.setImageResource(R.drawable.ic_dialog_alert);

        Button dismiss = (Button) mView.findViewById(R.id.button_dismiss);
        dismiss.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        hide();
                    }
                });

        mContainer = container;

        return mView;
    }

    public void hide() {
        // The container view is used to show/hide the error bar. If a container is not provided,
        // fall back to showing/hiding the error bar View, which also works, but does not provide
        // the same animated transition.
        if (mContainer != null) {
            mContainer.setVisibility(View.GONE);
        } else {
            mView.setVisibility(View.GONE);
        }
    }

    public void show() {
        // The container view is used to show/hide the error bar. If a container is not provided,
        // fall back to showing/hiding the error bar View, which also works, but does not provide
        // the same animated transition.
        if (mContainer != null) {
            mContainer.setVisibility(View.VISIBLE);
        } else {
            mView.setVisibility(View.VISIBLE);
        }
    }
}
