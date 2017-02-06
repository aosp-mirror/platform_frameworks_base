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
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.icu.text.BreakIterator;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.SmartSelection;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.method.WordIterator;
import android.text.style.ClickableSpan;
import android.text.util.Linkify;
import android.util.Log;
import android.view.View;

import com.android.internal.util.Preconditions;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

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

    private final Object mSmartSelectionLock = new Object();

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
                    return createClassificationResult(type, classified);
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
    public LinksInfo getLinks(CharSequence text, int linkMask) {
        Preconditions.checkArgument(text != null);
        try {
            return LinksInfoFactory.create(
                    mContext, getSmartSelection(), text.toString(), linkMask);
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error getting links info.", t);
        }
        // Getting here means something went wrong, return a NO_OP result.
        return TextClassifier.NO_OP.getLinks(text, linkMask);
    }

    private SmartSelection getSmartSelection() throws FileNotFoundException {
        synchronized (mSmartSelectionLock) {
            if (mSmartSelection == null) {
                mSmartSelection = new SmartSelection(mFd.getFd());
            }
            return mSmartSelection;
        }
    }

    private TextClassificationResult createClassificationResult(String type, CharSequence text) {
        final Intent intent = IntentFactory.create(type, text.toString());
        if (intent == null) {
            return TextClassificationResult.EMPTY;
        }

        final TextClassificationResult.Builder builder = new TextClassificationResult.Builder()
                .setText(text.toString())
                .setEntityType(type, 1.0f /* confidence */)
                .setIntent(intent)
                .setOnClickListener(TextClassificationResult.createStartActivityOnClick(
                        mContext, intent));
        final PackageManager pm = mContext.getPackageManager();
        final ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
        if (resolveInfo != null && resolveInfo.activityInfo != null) {
            final String packageName = resolveInfo.activityInfo.packageName;
            if ("android".equals(packageName)) {
                // Requires the chooser to find an activity to handle the intent.
                builder.setLabel(IntentFactory.getLabel(mContext, type));
            } else {
                // A default activity will handle the intent.
                intent.setComponent(new ComponentName(packageName, resolveInfo.activityInfo.name));
                Drawable icon = resolveInfo.activityInfo.loadIcon(pm);
                if (icon == null) {
                    icon = resolveInfo.loadIcon(pm);
                }
                builder.setIcon(icon);
                CharSequence label = resolveInfo.activityInfo.loadLabel(pm);
                if (label == null) {
                    label = resolveInfo.loadLabel(pm);
                }
                builder.setLabel(label != null ? label.toString() : null);
            }
        }
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

    /**
     * Detects and creates links for specified text.
     */
    private static final class LinksInfoFactory {

        private LinksInfoFactory() {}

        public static LinksInfo create(
                Context context, SmartSelection smartSelection, String text, int linkMask) {
            final WordIterator wordIterator = new WordIterator();
            wordIterator.setCharSequence(text, 0, text.length());
            final List<SpanSpec> spans = new ArrayList<>();
            int start = 0;
            int end;
            while ((end = wordIterator.nextBoundary(start)) != BreakIterator.DONE) {
                final String token = text.substring(start, end);
                if (TextUtils.isEmpty(token)) {
                    continue;
                }

                final int[] selection = smartSelection.suggest(text, start, end);
                final int selectionStart = selection[0];
                final int selectionEnd = selection[1];
                if (selectionStart >= 0 && selectionEnd <= text.length()
                        && selectionStart <= selectionEnd) {
                    final String type =
                            smartSelection.classifyText(text, selectionStart, selectionEnd);
                    if (matches(type, linkMask)) {
                        final Intent intent = IntentFactory.create(
                                type, text.substring(selectionStart, selectionEnd));
                        if (hasActivityHandler(context, intent)) {
                            final ClickableSpan span = createSpan(context, intent);
                            spans.add(new SpanSpec(selectionStart, selectionEnd, span));
                        }
                    }
                }
                start = end;
            }
            return new LinksInfoImpl(text, avoidOverlaps(spans, text));
        }

        /**
         * Returns true if the classification type matches the specified linkMask.
         */
        private static boolean matches(String type, int linkMask) {
            if ((linkMask & Linkify.PHONE_NUMBERS) != 0
                    && TextClassifier.TYPE_PHONE.equals(type)) {
                return true;
            }
            if ((linkMask & Linkify.EMAIL_ADDRESSES) != 0
                    && TextClassifier.TYPE_EMAIL.equals(type)) {
                return true;
            }
            if ((linkMask & Linkify.MAP_ADDRESSES) != 0
                    && TextClassifier.TYPE_ADDRESS.equals(type)) {
                return true;
            }
            return false;
        }

        /**
         * Trim the number of spans so that no two spans overlap.
         *
         * This algorithm first ensures that there is only one span per start index, then it
         * makes sure that no two spans overlap.
         */
        private static List<SpanSpec> avoidOverlaps(List<SpanSpec> spans, String text) {
            Collections.sort(spans, Comparator.comparingInt(span -> span.mStart));
            // Group spans by start index. Take the longest span.
            final Map<Integer, SpanSpec> reps = new LinkedHashMap<>();  // order matters.
            final int size = spans.size();
            for (int i = 0; i < size; i++) {
                final SpanSpec span = spans.get(i);
                final LinksInfoFactory.SpanSpec rep = reps.get(span.mStart);
                if (rep == null || rep.mEnd < span.mEnd) {
                    reps.put(span.mStart, span);
                }
            }
            // Avoid span intersections. Take the longer span.
            final LinkedList<SpanSpec> result = new LinkedList<>();
            for (SpanSpec rep : reps.values()) {
                if (result.isEmpty()) {
                    result.add(rep);
                    continue;
                }

                final SpanSpec last = result.getLast();
                if (rep.mStart < last.mEnd) {
                    // Spans intersect. Use the one with characters.
                    if ((rep.mEnd - rep.mStart) > (last.mEnd - last.mStart)) {
                        result.set(result.size() - 1, rep);
                    }
                } else {
                    result.add(rep);
                }
            }
            return result;
        }

        private static ClickableSpan createSpan(final Context context, final Intent intent) {
            return new ClickableSpan() {
                // TODO: Style this span.
                @Override
                public void onClick(View widget) {
                    context.startActivity(intent);
                }
            };
        }

        private static boolean hasActivityHandler(Context context, @Nullable Intent intent) {
            if (intent == null) {
                return false;
            }
            final ResolveInfo resolveInfo = context.getPackageManager().resolveActivity(intent, 0);
            return resolveInfo != null && resolveInfo.activityInfo != null;
        }

        /**
         * Implementation of LinksInfo that adds ClickableSpans to the specified text.
         */
        private static final class LinksInfoImpl implements LinksInfo {

            private final CharSequence mOriginalText;
            private final List<SpanSpec> mSpans;

            LinksInfoImpl(CharSequence originalText, List<SpanSpec> spans) {
                mOriginalText = originalText;
                mSpans = spans;
            }

            @Override
            public boolean apply(@NonNull CharSequence text) {
                Preconditions.checkArgument(text != null);
                if (text instanceof Spannable && mOriginalText.toString().equals(text.toString())) {
                    Spannable spannable = (Spannable) text;
                    final int size = mSpans.size();
                    for (int i = 0; i < size; i++) {
                        final SpanSpec span = mSpans.get(i);
                        spannable.setSpan(span.mSpan, span.mStart, span.mEnd, 0);
                    }
                    return true;
                }
                return false;
            }
        }

        /**
         * Span plus its start and end index.
         */
        private static final class SpanSpec {

            private final int mStart;
            private final int mEnd;
            private final ClickableSpan mSpan;

            SpanSpec(int start, int end, ClickableSpan span) {
                mStart = start;
                mEnd = end;
                mSpan = span;
            }
        }
    }

    /**
     * Creates intents based on the classification type.
     */
    private static final class IntentFactory {

        private IntentFactory() {}

        @Nullable
        public static Intent create(String type, String text) {
            switch (type) {
                case TextClassifier.TYPE_EMAIL:
                    return new Intent(Intent.ACTION_SENDTO)
                            .setData(Uri.parse(String.format("mailto:%s", text)));
                case TextClassifier.TYPE_PHONE:
                    return new Intent(Intent.ACTION_DIAL)
                            .setData(Uri.parse(String.format("tel:%s", text)));
                case TextClassifier.TYPE_ADDRESS:
                    return new Intent(Intent.ACTION_VIEW)
                            .setData(Uri.parse(String.format("geo:0,0?q=%s", text)));
                default:
                    return null;
                // TODO: Add other classification types.
            }
        }

        @Nullable
        public static String getLabel(Context context, String type) {
            switch (type) {
                case TextClassifier.TYPE_EMAIL:
                    return context.getString(com.android.internal.R.string.email);
                case TextClassifier.TYPE_PHONE:
                    return context.getString(com.android.internal.R.string.dial);
                case TextClassifier.TYPE_ADDRESS:
                    return context.getString(com.android.internal.R.string.map);
                default:
                    return null;
                // TODO: Add other classification types.
            }
        }
    }
}
