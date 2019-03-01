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
package android.view.textclassifier.intent;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.textclassifier.Log;
import android.view.textclassifier.TextClassifier;

import com.android.internal.annotations.VisibleForTesting;

import com.google.android.textclassifier.NamedVariant;
import com.google.android.textclassifier.RemoteActionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates intents based on {@link RemoteActionTemplate} objects.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class TemplateIntentFactory {
    private static final String TAG = TextClassifier.DEFAULT_LOG_TAG;

    @Nullable
    public List<LabeledIntent> create(
            @NonNull RemoteActionTemplate[] remoteActionTemplates) {
        if (remoteActionTemplates.length == 0) {
            return new ArrayList<>();
        }
        final List<LabeledIntent> labeledIntents = new ArrayList<>();
        for (RemoteActionTemplate remoteActionTemplate : remoteActionTemplates) {
            if (!isValidTemplate(remoteActionTemplate)) {
                Log.w(TAG, "Invalid RemoteActionTemplate skipped.");
                continue;
            }
            labeledIntents.add(
                    new LabeledIntent(
                            remoteActionTemplate.titleWithoutEntity,
                            remoteActionTemplate.titleWithEntity,
                            remoteActionTemplate.description,
                            createIntent(remoteActionTemplate),
                            remoteActionTemplate.requestCode == null
                                    ? LabeledIntent.DEFAULT_REQUEST_CODE
                                    : remoteActionTemplate.requestCode));
        }
        labeledIntents.forEach(
                action -> action.intent.putExtra(TextClassifier.EXTRA_FROM_TEXT_CLASSIFIER, true));
        return labeledIntents;
    }

    private static boolean isValidTemplate(@Nullable RemoteActionTemplate remoteActionTemplate) {
        if (remoteActionTemplate == null) {
            Log.w(TAG, "Invalid RemoteActionTemplate: is null");
            return false;
        }
        if (TextUtils.isEmpty(remoteActionTemplate.titleWithEntity)
                && TextUtils.isEmpty(remoteActionTemplate.titleWithoutEntity)) {
            Log.w(TAG, "Invalid RemoteActionTemplate: title is null");
            return false;
        }
        if (TextUtils.isEmpty(remoteActionTemplate.description)) {
            Log.w(TAG, "Invalid RemoteActionTemplate: description is null");
            return false;
        }
        if (!TextUtils.isEmpty(remoteActionTemplate.packageName)) {
            Log.w(TAG, "Invalid RemoteActionTemplate: package name is set");
            return false;
        }
        if (TextUtils.isEmpty(remoteActionTemplate.action)) {
            Log.w(TAG, "Invalid RemoteActionTemplate: intent action not set");
            return false;
        }
        return true;
    }

    private static Intent createIntent(RemoteActionTemplate remoteActionTemplate) {
        final Intent intent = new Intent(remoteActionTemplate.action);
        final Uri uri = TextUtils.isEmpty(remoteActionTemplate.data)
                ? null : Uri.parse(remoteActionTemplate.data).normalizeScheme();
        final String type = TextUtils.isEmpty(remoteActionTemplate.type)
                ? null : Intent.normalizeMimeType(remoteActionTemplate.type);
        intent.setDataAndType(uri, type);
        intent.setFlags(remoteActionTemplate.flags == null ? 0 : remoteActionTemplate.flags);
        if (remoteActionTemplate.category != null) {
            for (String category : remoteActionTemplate.category) {
                if (category != null) {
                    intent.addCategory(category);
                }
            }
        }
        intent.putExtras(createExtras(remoteActionTemplate.extras));
        return intent;
    }

    private static Bundle createExtras(NamedVariant[] namedVariants) {
        if (namedVariants == null) {
            return Bundle.EMPTY;
        }
        Bundle bundle = new Bundle();
        for (NamedVariant namedVariant : namedVariants) {
            if (namedVariant == null) {
                continue;
            }
            switch (namedVariant.getType()) {
                case NamedVariant.TYPE_INT:
                    bundle.putInt(namedVariant.getName(), namedVariant.getInt());
                    break;
                case NamedVariant.TYPE_LONG:
                    bundle.putLong(namedVariant.getName(), namedVariant.getLong());
                    break;
                case NamedVariant.TYPE_FLOAT:
                    bundle.putFloat(namedVariant.getName(), namedVariant.getFloat());
                    break;
                case NamedVariant.TYPE_DOUBLE:
                    bundle.putDouble(namedVariant.getName(), namedVariant.getDouble());
                    break;
                case NamedVariant.TYPE_BOOL:
                    bundle.putBoolean(namedVariant.getName(), namedVariant.getBool());
                    break;
                case NamedVariant.TYPE_STRING:
                    bundle.putString(namedVariant.getName(), namedVariant.getString());
                    break;
                default:
                    Log.w(TAG,
                            "Unsupported type found in createExtras : " + namedVariant.getType());
            }
        }
        return bundle;
    }
}
