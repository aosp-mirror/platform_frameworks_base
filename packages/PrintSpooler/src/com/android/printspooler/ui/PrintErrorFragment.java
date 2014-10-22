/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.printspooler.ui;

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

import com.android.printspooler.R;

/**
 * Fragment for showing an error UI.
 */
public final class PrintErrorFragment extends Fragment {
    public static final int ACTION_NONE = 0;
    public static final int ACTION_RETRY = 1;

    private static final String EXTRA_MESSAGE = "EXTRA_MESSAGE";
    private static final String EXTRA_ACTION = "EXTRA_ACTION";

    public interface OnActionListener {
        public void onActionPerformed();
    }

    public static PrintErrorFragment newInstance(CharSequence message, int action) {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(EXTRA_MESSAGE, message);
        arguments.putInt(EXTRA_ACTION, action);
        PrintErrorFragment fragment = new PrintErrorFragment();
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup root,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.print_error_fragment, root, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        CharSequence message = getArguments().getCharSequence(EXTRA_MESSAGE);

        if (!TextUtils.isEmpty(message)) {
            TextView messageView = (TextView) view.findViewById(R.id.message);
            messageView.setText(message);
        }

        Button actionButton = (Button) view.findViewById(R.id.action_button);

        final int action = getArguments().getInt(EXTRA_ACTION);
        switch (action) {
            case ACTION_RETRY: {
                actionButton.setVisibility(View.VISIBLE);
                actionButton.setText(R.string.print_error_retry);
            } break;

            case ACTION_NONE: {
                actionButton.setVisibility(View.GONE);
            } break;
        }

        actionButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                Activity activity = getActivity();
                if (activity instanceof OnActionListener) {
                    ((OnActionListener) getActivity()).onActionPerformed();
                }
            }
        });
    }
}
