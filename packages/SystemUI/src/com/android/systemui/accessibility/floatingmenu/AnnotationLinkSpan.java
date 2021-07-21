/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import android.text.Annotation;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ClickableSpan;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Optional;

/**
 * A span that turns the text wrapped by annotation tag into the clickable link text.
 */
class AnnotationLinkSpan extends ClickableSpan {
    private final Optional<View.OnClickListener> mClickListener;

    private AnnotationLinkSpan(View.OnClickListener listener) {
        mClickListener = Optional.ofNullable(listener);
    }

    @Override
    public void onClick(View view) {
        mClickListener.ifPresent(listener -> listener.onClick(view));
    }

    /**
     * Makes the text has the link with the click action. In addition, the span will match first
     * LinkInfo and attach into the text.
     *
     * @param text the text wrapped by annotation tag
     * @param linkInfos used to attach the click action into the corresponding span
     * @return the text attached with the span
     */
    static CharSequence linkify(CharSequence text, LinkInfo... linkInfos) {
        final SpannableString msg = new SpannableString(text);
        final Annotation[] spans =
                msg.getSpans(/* queryStart= */ 0, msg.length(), Annotation.class);
        final SpannableStringBuilder builder = new SpannableStringBuilder(msg);

        Arrays.asList(spans).forEach(annotationTag -> {
            final String key = annotationTag.getValue();
            final Optional<LinkInfo> linkInfo =
                    Arrays.asList(linkInfos).stream().filter(
                            info -> info.mAnnotation.isPresent()
                                    && info.mAnnotation.get().equals(key)).findFirst();

            linkInfo.flatMap(info -> info.mListener).ifPresent(listener -> {
                final AnnotationLinkSpan span = new AnnotationLinkSpan(listener);
                builder.setSpan(span,
                        msg.getSpanStart(annotationTag),
                        msg.getSpanEnd(annotationTag),
                        msg.getSpanFlags(span));
            });
        });

        return builder;
    }

    /**
     * Data class to store the annotation and the click action.
     */
    static class LinkInfo {
        static final String DEFAULT_ANNOTATION = "link";
        private final Optional<String> mAnnotation;
        private final Optional<View.OnClickListener> mListener;

        LinkInfo(@NonNull String annotation, View.OnClickListener listener) {
            mAnnotation = Optional.of(annotation);
            mListener = Optional.ofNullable(listener);
        }
    }
}
