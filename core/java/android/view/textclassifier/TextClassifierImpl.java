/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.textclassifier;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.SmartSelection;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.FileNotFoundException;

/**
 * Default implementation of the {@link TextClassifier} interface.
 *
 * <p>This class uses machine learning to recognize entities in text.
 * Unless otherwise stated, methods of this class are blocking operations and should most
 * likely not be called on the UI thread.
 *
 * @hide
 */
final class TextClassifierImpl implements TextClassifier {

    private static final String LOG_TAG = "TextClassifierImpl";

    private final Context mContext;
    private final ParcelFileDescriptor mFd;
    private SmartSelection mSmartSelection;

    TextClassifierImpl(Context context, ParcelFileDescriptor fd) {
        mContext = Preconditions.checkNotNull(context);
        mFd = Preconditions.checkNotNull(fd);
    }

    @Override
    public TextSelection suggestSelection(
            @NonNull CharSequence text, int selectionStartIndex, int selectionEndIndex) {
        validateInput(text, selectionStartIndex, selectionEndIndex);
        try {
            if (text.length() > 0) {
                final String string = text.toString();
                final int[] startEnd = getSmartSelection()
                        .suggest(string, selectionStartIndex, selectionEndIndex);
                final int start = startEnd[0];
                final int end = startEnd[1];
                if (start >= 0 && end <= string.length() && start <= end) {
                    final String type = getSmartSelection().classifyText(string, start, end);
                    return new TextSelection.Builder(start, end)
                            .setEntityType(type, 1.0f)
                            .build();
                } else {
                    // We can not trust the result. Log the issue and ignore the result.
                    Log.d(LOG_TAG, "Got bad indices for input text. Ignoring result.");
                }
            }
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG,
                    "Error suggesting selection for text. No changes to selection suggested.",
                    t);
        }
        // Getting here means something went wrong, return a NO_OP result.
        return TextClassifier.NO_OP.suggestSelection(
                text, selectionStartIndex, selectionEndIndex);
    }

    @Override
    public TextClassificationResult getTextClassificationResult(
            @NonNull CharSequence text, int startIndex, int endIndex) {
        validateInput(text, startIndex, endIndex);
        try {
            if (text.length() > 0) {
                final CharSequence classified = text.subSequence(startIndex, endIndex);
                String type = getSmartSelection()
                        .classifyText(text.toString(), startIndex, endIndex);
                if (!TextUtils.isEmpty(type)) {
                    type = type.toLowerCase().trim();
                    // TODO: Added this log for debug only. Remove before release.
                    Log.d(LOG_TAG, String.format("Classification type: %s", type));
                    final Intent intent;
                    final String title;
                    switch (type) {
                        case TextClassifier.TYPE_EMAIL:
                            intent = new Intent(Intent.ACTION_SENDTO);
                            intent.setData(Uri.parse(String.format("mailto:%s", text)));
                            title = mContext.getString(com.android.internal.R.string.email);
                            return createClassificationResult(classified, type, intent, title);
                        case TextClassifier.TYPE_PHONE:
                            intent = new Intent(Intent.ACTION_DIAL);
                            intent.setData(Uri.parse(String.format("tel:%s", text)));
                            title = mContext.getString(com.android.internal.R.string.dial);
                            return createClassificationResult(classified, type, intent, title);
                        case TextClassifier.TYPE_ADDRESS:
                            intent = new Intent(Intent.ACTION_VIEW);
                            intent.setData(Uri.parse(String.format("geo:0,0?q=%s", text)));
                            title = mContext.getString(com.android.internal.R.string.map);
                            return createClassificationResult(classified, type, intent, title);
                        default:
                            // No classification type found. Return a no-op result.
                            break;
                        // TODO: Add other classification types.
                    }
                }
            }
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error getting assist info.", t);
        }
        // Getting here means something went wrong, return a NO_OP result.
        return TextClassifier.NO_OP.getTextClassificationResult(text, startIndex, endIndex);
    }

    @Override
    public LinksInfo getLinks(@NonNull CharSequence text, int linkMask) {
        // TODO: Implement
        return TextClassifier.NO_OP.getLinks(text, linkMask);
    }

    private synchronized SmartSelection getSmartSelection() throws FileNotFoundException {
        if (mSmartSelection == null) {
            mSmartSelection = new SmartSelection(mFd.getFd());
        }
        return mSmartSelection;
    }

    private TextClassificationResult createClassificationResult(
            CharSequence text, String type, Intent intent, String label) {
        TextClassificationResult.Builder builder = new TextClassificationResult.Builder()
                .setText(text.toString())
                .setEntityType(type, 1.0f /* confidence */)
                .setIntent(intent)
                .setOnClickListener(TextClassificationResult.createStartActivityOnClick(
                        mContext, intent))
                .setLabel(label);
        PackageManager pm = mContext.getPackageManager();
        ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
        // TODO: If the resolveInfo is the "chooser", do not set the package name and use a
        // default icon for this classification type.
        intent.setPackage(resolveInfo.activityInfo.packageName);
        Drawable icon = resolveInfo.activityInfo.loadIcon(pm);
        if (icon == null) {
            icon = resolveInfo.loadIcon(pm);
        }
        builder.setIcon(icon);
        return builder.build();
    }

    /**
     * @throws IllegalArgumentException if text is null; startIndex is negative;
     *      endIndex is greater than text.length() or less than startIndex
     */
    private static void validateInput(@NonNull CharSequence text, int startIndex, int endIndex) {
        Preconditions.checkArgument(text != null);
        Preconditions.checkArgument(startIndex >= 0);
        Preconditions.checkArgument(endIndex <= text.length());
        Preconditions.checkArgument(endIndex >= startIndex);
    }
}
