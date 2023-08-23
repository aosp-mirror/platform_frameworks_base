/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import android.app.RemoteAction;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.Optional;

import javax.inject.Inject;

class ClipboardOverlayUtils {

    // minimum proportion of entire text an entity must take up, to be considered for smart actions
    private static final float MINIMUM_ENTITY_PROPORTION = .8f;

    private final TextClassifier mTextClassifier;

    @Inject
    ClipboardOverlayUtils(TextClassificationManager textClassificationManager) {
        mTextClassifier = textClassificationManager.getTextClassifier();
    }

    boolean isRemoteCopy(Context context, ClipData clipData, String clipSource) {
        if (clipData != null && clipData.getDescription().getExtras() != null
                && clipData.getDescription().getExtras().getBoolean(
                ClipDescription.EXTRA_IS_REMOTE_DEVICE)) {
            if (Build.isDebuggable() && DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_SYSTEMUI,
                    SystemUiDeviceConfigFlags.CLIPBOARD_IGNORE_REMOTE_COPY_SOURCE,
                    false)) {
                return true;
            }
            ComponentName remoteComponent = ComponentName.unflattenFromString(
                    context.getResources().getString(R.string.config_remoteCopyPackage));
            if (remoteComponent != null) {
                return remoteComponent.getPackageName().equals(clipSource);
            }
        }
        return false;
    }

    public Optional<RemoteAction> getAction(TextLinks textLinks, String source) {
        return getActions(textLinks).stream().filter(remoteAction -> {
            ComponentName component = remoteAction.getActionIntent().getIntent().getComponent();
            return component != null && !TextUtils.equals(source, component.getPackageName());
        }).findFirst();
    }

    private ArrayList<RemoteAction> getActions(TextLinks textLinks) {
        ArrayList<RemoteAction> actions = new ArrayList<>();
        for (TextLinks.TextLink link : textLinks.getLinks()) {
            // skip classification for incidental entities
            if (link.getEnd() - link.getStart()
                    >= textLinks.getText().length() * MINIMUM_ENTITY_PROPORTION) {
                TextClassification classification = mTextClassifier.classifyText(
                        textLinks.getText(), link.getStart(), link.getEnd(), null);
                actions.addAll(classification.getActions());
            }
        }
        return actions;
    }
}
