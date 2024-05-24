/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.nfc.Flags.enableNfcMainline;

import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.nfc.NfcAdapter;
import android.os.Bundle;

import java.util.ArrayList;

/**
 * Caller-customizable variant of {@link ResolverActivity} to support the
 * NFC resolver intent.
 */
public class NfcResolverActivity extends ResolverActivity {

    @Override
    @SuppressWarnings("MissingSuperCall")  // Called indirectly via `super_onCreate()`.
    protected void onCreate(Bundle savedInstanceState) {
        if (!enableNfcMainline()) {
            super_onCreate(savedInstanceState);
            finish();
            return;
        }

        Intent intent = getIntent();
        Intent target = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        ArrayList<ResolveInfo> rList =
                intent.getParcelableArrayListExtra(
                NfcAdapter.EXTRA_RESOLVE_INFOS, ResolveInfo.class);
        CharSequence title = intent.getExtras().getCharSequence(
                Intent.EXTRA_TITLE,
                getResources().getText(com.android.internal.R.string.chooseActivity));

        super.onCreate(
                savedInstanceState,
                target,
                title,
                /* initialIntents=*/ null,
                rList,
                /* supportsAlwaysUseOption=*/ false);
    }
}
