/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.bidi;

import android.app.AlertDialog;
import android.app.Fragment;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

public class BiDiTestBasic extends Fragment {

    private View currentView;
    private Button alertDialogButton;
    private String[] items = {"This is a very very very very very very very very very very very long Item1", "Item2"};

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        currentView = inflater.inflate(R.layout.basic, container, false);
        return currentView;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        alertDialogButton = (Button) currentView.findViewById(R.id.button_alert_dialog);
        alertDialogButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showDialog();
            }
        });

        useSpans();
    }

    private void showDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(currentView.getContext());
        builder.setSingleChoiceItems(items, 0, null);
        builder.show();
    }

    private void useSpans() {
        EditText urlEdit = (EditText) currentView.findViewById(R.id.edittext_url);
        Editable url = urlEdit.getText();
        if (url.length() < 1) {
          return;
        }

        String urlString = url.toString();
        int urlLength = urlString.length();
        String domainAndRegistry = "amazon.co.uk";

        int startSchemeIndex = urlString.startsWith("https") ? 5 : 0;
        int startDomainIndex = urlString.indexOf(domainAndRegistry);
        if (startDomainIndex == -1) {
          assert false;
          return;
        }
        int stopIndex = startDomainIndex + domainAndRegistry.length();

        if (startDomainIndex != 0) {
          url.setSpan(new ForegroundColorSpan(0xfff00fff),
                  startSchemeIndex,
                  startDomainIndex,
                  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        url.setSpan(new ForegroundColorSpan(0xff548aff),
                startDomainIndex,
                stopIndex,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (stopIndex < urlString.length()) {
          url.setSpan(new ForegroundColorSpan(0xfff00fff),
                  stopIndex,
                  urlLength,
                  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
