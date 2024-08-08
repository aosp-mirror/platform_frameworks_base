/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.widget;

import static com.android.text.flags.Flags.FLAG_NO_BREAK_NO_HYPHENATION_SPAN;
import static com.android.text.flags.Flags.noBreakNoHyphenationSpan;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.annotation.FlaggedApi;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlendMode;
import android.graphics.drawable.Icon;
import android.graphics.text.LineBreakConfig;
import android.os.LocaleList;
import android.os.PersistableBundle;
import android.text.Annotation;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AccessibilityClickableSpan;
import android.text.style.AccessibilityReplacementSpan;
import android.text.style.AccessibilityURLSpan;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.CharacterStyle;
import android.text.style.EasyEditSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.LineBreakConfigSpan;
import android.text.style.LineHeightSpan;
import android.text.style.LocaleSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ScaleXSpan;
import android.text.style.SpellCheckSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuggestionRangeSpan;
import android.text.style.SuggestionSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TextAppearanceSpan;
import android.text.style.TtsSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;

import androidx.annotation.NonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * This class provides serialization for certain types used within RemoteViews.
 *
 * @hide
 */
public class RemoteViewsSerializers {
    private static final String TAG = "RemoteViews";

    /**
     * Write Icon to proto.
     */
    public static void writeIconToProto(@NonNull ProtoOutputStream out,
            @NonNull Resources appResources, @NonNull Icon icon) {
        if (icon.getTintList() != null) {
            final long token = out.start(RemoteViewsProto.Icon.TINT_LIST);
            icon.getTintList().writeToProto(out);
            out.end(token);
        }
        out.write(RemoteViewsProto.Icon.BLEND_MODE, BlendMode.toValue(icon.getTintBlendMode()));
        switch (icon.getType()) {
            case Icon.TYPE_BITMAP:
                final ByteArrayOutputStream bitmapBytes = new ByteArrayOutputStream();
                icon.getBitmap().compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, bitmapBytes);
                out.write(RemoteViewsProto.Icon.BITMAP, bitmapBytes.toByteArray());
                break;
            case Icon.TYPE_ADAPTIVE_BITMAP:
                final ByteArrayOutputStream adaptiveBitmapBytes = new ByteArrayOutputStream();
                icon.getBitmap()
                        .compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, adaptiveBitmapBytes);
                out.write(RemoteViewsProto.Icon.ADAPTIVE_BITMAP, adaptiveBitmapBytes.toByteArray());
                break;
            case Icon.TYPE_RESOURCE:
                out.write(
                        RemoteViewsProto.Icon.RESOURCE,
                        appResources.getResourceName(icon.getResId()));
                break;
            case Icon.TYPE_DATA:
                out.write(RemoteViewsProto.Icon.DATA, icon.getDataBytes());
                break;
            case Icon.TYPE_URI:
                out.write(RemoteViewsProto.Icon.URI, icon.getUriString());
                break;
            case Icon.TYPE_URI_ADAPTIVE_BITMAP:
                out.write(RemoteViewsProto.Icon.URI_ADAPTIVE_BITMAP, icon.getUriString());
                break;
            default:
                Log.e(TAG, "Tried to serialize unknown Icon type " + icon.getType());
        }
    }

    /**
     * Create Icon from proto.
     */
    @NonNull
    public static Function<Resources, Icon> createIconFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        final LongSparseArray<Object> values = new LongSparseArray<>();
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.Icon.BLEND_MODE:
                    values.put(
                            RemoteViewsProto.Icon.BLEND_MODE,
                            in.readInt(RemoteViewsProto.Icon.BLEND_MODE));
                    break;
                case (int) RemoteViewsProto.Icon.TINT_LIST:
                    final long tintListToken = in.start(RemoteViewsProto.Icon.TINT_LIST);
                    values.put(RemoteViewsProto.Icon.TINT_LIST, ColorStateList.createFromProto(in));
                    in.end(tintListToken);
                    break;
                case (int) RemoteViewsProto.Icon.BITMAP:
                    byte[] bitmapData = in.readBytes(RemoteViewsProto.Icon.BITMAP);
                    values.put(
                            RemoteViewsProto.Icon.BITMAP,
                            BitmapFactory.decodeByteArray(bitmapData, 0, bitmapData.length));
                    break;
                case (int) RemoteViewsProto.Icon.ADAPTIVE_BITMAP:
                    final byte[] bitmapAdaptiveData = in.readBytes(
                            RemoteViewsProto.Icon.ADAPTIVE_BITMAP);
                    values.put(RemoteViewsProto.Icon.ADAPTIVE_BITMAP,
                            BitmapFactory.decodeByteArray(bitmapAdaptiveData, 0,
                                    bitmapAdaptiveData.length));
                    break;
                case (int) RemoteViewsProto.Icon.RESOURCE:
                    values.put(
                            RemoteViewsProto.Icon.RESOURCE,
                            in.readString(RemoteViewsProto.Icon.RESOURCE));
                    break;
                case (int) RemoteViewsProto.Icon.DATA:
                    values.put(
                            RemoteViewsProto.Icon.DATA, in.readBytes(RemoteViewsProto.Icon.DATA));
                    break;
                case (int) RemoteViewsProto.Icon.URI:
                    values.put(RemoteViewsProto.Icon.URI, in.readString(RemoteViewsProto.Icon.URI));
                    break;
                case (int) RemoteViewsProto.Icon.URI_ADAPTIVE_BITMAP:
                    values.put(
                            RemoteViewsProto.Icon.URI_ADAPTIVE_BITMAP,
                            in.readString(RemoteViewsProto.Icon.URI_ADAPTIVE_BITMAP));
                    break;
                default:
                    Log.w(
                            TAG,
                            "Unhandled field while reading Icon proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }

        return (resources) -> {
            final int blendMode = (int) values.get(RemoteViewsProto.Icon.BLEND_MODE, -1);
            final ColorStateList tintList = (ColorStateList) values.get(
                    RemoteViewsProto.Icon.TINT_LIST);
            final Bitmap bitmap = (Bitmap) values.get(RemoteViewsProto.Icon.BITMAP);
            final Bitmap bitmapAdaptive = (Bitmap) values.get(
                    RemoteViewsProto.Icon.ADAPTIVE_BITMAP);
            final String resName = (String) values.get(RemoteViewsProto.Icon.RESOURCE);
            final int resource = resName != null ? resources.getIdentifier(resName, /* defType= */
                    null,
                    /* defPackage= */ null) : -1;
            final byte[] data = (byte[]) values.get(RemoteViewsProto.Icon.DATA);
            final String uri = (String) values.get(RemoteViewsProto.Icon.URI);
            final String uriAdaptive = (String) values.get(
                    RemoteViewsProto.Icon.URI_ADAPTIVE_BITMAP);
            Icon icon;
            if (bitmap != null) {
                icon = Icon.createWithBitmap(bitmap);
            } else if (bitmapAdaptive != null) {
                icon = Icon.createWithAdaptiveBitmap(bitmapAdaptive);
            } else if (resource != -1) {
                icon = Icon.createWithResource(resources, resource);
            } else if (data != null) {
                icon = Icon.createWithData(data, 0, data.length);
            } else if (uri != null) {
                icon = Icon.createWithContentUri(uri);
            } else if (uriAdaptive != null) {
                icon = Icon.createWithAdaptiveBitmapContentUri(uriAdaptive);
            } else {
                // Either this Icon has no data or is of an unknown type.
                return null;
            }

            if (tintList != null) {
                icon.setTintList(tintList);
            }
            if (blendMode != -1) {
                icon.setTintBlendMode(BlendMode.fromValue(blendMode));
            }
            return icon;
        };
    }

    public static void writeCharSequenceToProto(@NonNull ProtoOutputStream out,
            @NonNull CharSequence cs) {
        out.write(RemoteViewsProto.CharSequence.TEXT, cs.toString());
        if (!(cs instanceof Spanned sp)) return;

        Object[] os = sp.getSpans(0, cs.length(), Object.class);
        for (Object original : os) {
            Object prop = original;
            if (prop instanceof CharacterStyle) {
                prop = ((CharacterStyle) prop).getUnderlying();
            }

            final long spansToken = out.start(RemoteViewsProto.CharSequence.SPANS);
            out.write(RemoteViewsProto.CharSequence.Span.START, sp.getSpanStart(original));
            out.write(RemoteViewsProto.CharSequence.Span.END, sp.getSpanEnd(original));
            out.write(RemoteViewsProto.CharSequence.Span.FLAGS, sp.getSpanFlags(original));

            if (prop instanceof AbsoluteSizeSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.ABSOLUTE_SIZE);
                writeAbsoluteSizeSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof AccessibilityClickableSpan span) {
                final long spanToken = out.start(
                        RemoteViewsProto.CharSequence.Span.ACCESSIBILITY_CLICKABLE);
                writeAccessibilityClickableSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof AccessibilityReplacementSpan span) {
                final long spanToken = out.start(
                        RemoteViewsProto.CharSequence.Span.ACCESSIBILITY_REPLACEMENT);
                writeAccessibilityReplacementSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof AccessibilityURLSpan span) {
                final long spanToken = out.start(
                        RemoteViewsProto.CharSequence.Span.ACCESSIBILITY_URL);
                writeAccessibilityURLSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof Annotation span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.ANNOTATION);
                writeAnnotationToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof BackgroundColorSpan span) {
                final long spanToken = out.start(
                        RemoteViewsProto.CharSequence.Span.BACKGROUND_COLOR);
                writeBackgroundColorSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof BulletSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.BULLET);
                writeBulletSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof EasyEditSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.EASY_EDIT);
                writeEasyEditSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof ForegroundColorSpan span) {
                final long spanToken = out.start(
                        RemoteViewsProto.CharSequence.Span.FOREGROUND_COLOR);
                writeForegroundColorSpanToProto(out, span);
                out.end(spanToken);
            } else if (noBreakNoHyphenationSpan() && prop instanceof LineBreakConfigSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.LINE_BREAK);
                writeLineBreakConfigSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof LocaleSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.LOCALE);
                writeLocaleSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof QuoteSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.QUOTE);
                writeQuoteSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof RelativeSizeSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.RELATIVE_SIZE);
                writeRelativeSizeSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof ScaleXSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.SCALE_X);
                writeScaleXSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof SpellCheckSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.SPELL_CHECK);
                writeSpellCheckSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof LineBackgroundSpan.Standard span) {
                final long spanToken = out.start(
                        RemoteViewsProto.CharSequence.Span.LINE_BACKGROUND);
                writeLineBackgroundSpanStandardToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof LineHeightSpan.Standard span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.LINE_HEIGHT);
                writeLineHeightSpanStandardToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof LeadingMarginSpan.Standard span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.LEADING_MARGIN);
                writeLeadingMarginSpanStandardToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof AlignmentSpan.Standard span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.ALIGNMENT);
                writeAlignmentSpanStandardToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof StrikethroughSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.STRIKETHROUGH);
                writeStrikethroughSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof StyleSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.STYLE);
                writeStyleSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof SubscriptSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.SUBSCRIPT);
                writeSubscriptSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof SuggestionRangeSpan span) {
                final long spanToken = out.start(
                        RemoteViewsProto.CharSequence.Span.SUGGESTION_RANGE);
                writeSuggestionRangeSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof SuggestionSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.SUGGESTION);
                writeSuggestionSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof SuperscriptSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.SUPERSCRIPT);
                writeSuperscriptSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof TextAppearanceSpan span) {
                final long spanToken = out.start(
                        RemoteViewsProto.CharSequence.Span.TEXT_APPEARANCE);
                writeTextAppearanceSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof TtsSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.TTS);
                writeTtsSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof TypefaceSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.TYPEFACE);
                writeTypefaceSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof URLSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.URL);
                writeURLSpanToProto(out, span);
                out.end(spanToken);
            } else if (prop instanceof UnderlineSpan span) {
                final long spanToken = out.start(RemoteViewsProto.CharSequence.Span.UNDERLINE);
                writeUnderlineSpanToProto(out, span);
                out.end(spanToken);
            }
            out.end(spansToken);
        }
    }

    public static CharSequence createCharSequenceFromProto(ProtoInputStream in) throws Exception {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        boolean hasSpans = false;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.TEXT:
                    String text = in.readString(RemoteViewsProto.CharSequence.TEXT);
                    builder.append(text);
                    break;
                case (int) RemoteViewsProto.CharSequence.SPANS:
                    hasSpans = true;
                    final long spansToken = in.start(RemoteViewsProto.CharSequence.SPANS);
                    createSpanFromProto(in, builder);
                    in.end(spansToken);
                    break;
                default:
                    Log.w(TAG, "Unhandled field while reading CharSequence proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }
        return hasSpans ? builder : builder.toString();
    }

    private static void createSpanFromProto(ProtoInputStream in, SpannableStringBuilder builder)
            throws Exception {
        int start = 0;
        int end = 0;
        int flags = 0;
        Object what = null;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.START:
                    start = in.readInt(RemoteViewsProto.CharSequence.Span.START);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.END:
                    end = in.readInt(RemoteViewsProto.CharSequence.Span.END);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.FLAGS:
                    flags = in.readInt(RemoteViewsProto.CharSequence.Span.FLAGS);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.ABSOLUTE_SIZE:
                    final long asToken = in.start(RemoteViewsProto.CharSequence.Span.ABSOLUTE_SIZE);
                    what = createAbsoluteSizeSpanFromProto(in);
                    in.end(asToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.ACCESSIBILITY_CLICKABLE:
                    final long acToken = in.start(
                            RemoteViewsProto.CharSequence.Span.ACCESSIBILITY_CLICKABLE);
                    what = createAccessibilityClickableSpanFromProto(in);
                    in.end(acToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.ACCESSIBILITY_REPLACEMENT:
                    final long arToken = in.start(
                            RemoteViewsProto.CharSequence.Span.ACCESSIBILITY_REPLACEMENT);
                    what = createAccessibilityReplacementSpanFromProto(in);
                    in.end(arToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.ACCESSIBILITY_URL:
                    final long auToken = in.start(
                            RemoteViewsProto.CharSequence.Span.ACCESSIBILITY_URL);
                    what = createAccessibilityURLSpanFromProto(in);
                    in.end(auToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.ALIGNMENT:
                    final long aToken = in.start(RemoteViewsProto.CharSequence.Span.ALIGNMENT);
                    what = createAlignmentSpanStandardFromProto(in);
                    in.end(aToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.ANNOTATION:
                    final long annToken = in.start(RemoteViewsProto.CharSequence.Span.ANNOTATION);
                    what = createAnnotationFromProto(in);
                    in.end(annToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.BACKGROUND_COLOR:
                    final long bcToken = in.start(
                            RemoteViewsProto.CharSequence.Span.BACKGROUND_COLOR);
                    what = createBackgroundColorSpanFromProto(in);
                    in.end(bcToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.BULLET:
                    final long bToken = in.start(RemoteViewsProto.CharSequence.Span.BULLET);
                    what = createBulletSpanFromProto(in);
                    in.end(bToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.EASY_EDIT:
                    final long eeToken = in.start(RemoteViewsProto.CharSequence.Span.EASY_EDIT);
                    what = createEasyEditSpanFromProto(in);
                    in.end(eeToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.FOREGROUND_COLOR:
                    final long fcToken = in.start(
                            RemoteViewsProto.CharSequence.Span.FOREGROUND_COLOR);
                    what = createForegroundColorSpanFromProto(in);
                    in.end(fcToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.LEADING_MARGIN:
                    final long lmToken = in.start(
                            RemoteViewsProto.CharSequence.Span.LEADING_MARGIN);
                    what = createLeadingMarginSpanStandardFromProto(in);
                    in.end(lmToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.LINE_BACKGROUND:
                    final long lbToken = in.start(
                            RemoteViewsProto.CharSequence.Span.LINE_BACKGROUND);
                    what = createLineBackgroundSpanStandardFromProto(in);
                    in.end(lbToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.LINE_BREAK:
                    if (!noBreakNoHyphenationSpan()) {
                        continue;
                    }
                    final long lbrToken = in.start(RemoteViewsProto.CharSequence.Span.LINE_BREAK);
                    what = createLineBreakConfigSpanFromProto(in);
                    in.end(lbrToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.LINE_HEIGHT:
                    final long lhToken = in.start(RemoteViewsProto.CharSequence.Span.LINE_HEIGHT);
                    what = createLineHeightSpanStandardFromProto(in);
                    in.end(lhToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.LOCALE:
                    final long lToken = in.start(RemoteViewsProto.CharSequence.Span.LOCALE);
                    what = createLocaleSpanFromProto(in);
                    in.end(lToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.QUOTE:
                    final long qToken = in.start(RemoteViewsProto.CharSequence.Span.QUOTE);
                    what = createQuoteSpanFromProto(in);
                    in.end(qToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.RELATIVE_SIZE:
                    final long rsToken = in.start(RemoteViewsProto.CharSequence.Span.RELATIVE_SIZE);
                    what = createRelativeSizeSpanFromProto(in);
                    in.end(rsToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.SCALE_X:
                    final long sxToken = in.start(RemoteViewsProto.CharSequence.Span.SCALE_X);
                    what = createScaleXSpanFromProto(in);
                    in.end(sxToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.SPELL_CHECK:
                    final long scToken = in.start(RemoteViewsProto.CharSequence.Span.SPELL_CHECK);
                    what = createSpellCheckSpanFromProto(in);
                    in.end(scToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.STRIKETHROUGH:
                    final long stToken = in.start(RemoteViewsProto.CharSequence.Span.STRIKETHROUGH);
                    what = createStrikethroughSpanFromProto(in);
                    in.end(stToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.STYLE:
                    final long sToken = in.start(RemoteViewsProto.CharSequence.Span.STYLE);
                    what = createStyleSpanFromProto(in);
                    in.end(sToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.SUBSCRIPT:
                    final long suToken = in.start(RemoteViewsProto.CharSequence.Span.SUBSCRIPT);
                    what = createSubscriptSpanFromProto(in);
                    in.end(suToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.SUGGESTION_RANGE:
                    final long srToken = in.start(
                            RemoteViewsProto.CharSequence.Span.SUGGESTION_RANGE);
                    what = createSuggestionRangeSpanFromProto(in);
                    in.end(srToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.SUGGESTION:
                    final long sugToken = in.start(RemoteViewsProto.CharSequence.Span.SUGGESTION);
                    what = createSuggestionSpanFromProto(in);
                    in.end(sugToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.SUPERSCRIPT:
                    final long supToken = in.start(RemoteViewsProto.CharSequence.Span.SUPERSCRIPT);
                    what = createSuperscriptSpanFromProto(in);
                    in.end(supToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TEXT_APPEARANCE:
                    final long taToken = in.start(
                            RemoteViewsProto.CharSequence.Span.TEXT_APPEARANCE);
                    what = createTextAppearanceSpanFromProto(in);
                    in.end(taToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TTS:
                    final long ttsToken = in.start(RemoteViewsProto.CharSequence.Span.TTS);
                    what = createTtsSpanFromProto(in);
                    in.end(ttsToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TYPEFACE:
                    final long tfToken = in.start(RemoteViewsProto.CharSequence.Span.TYPEFACE);
                    what = createTypefaceSpanFromProto(in);
                    in.end(tfToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.UNDERLINE:
                    final long unToken = in.start(RemoteViewsProto.CharSequence.Span.UNDERLINE);
                    what = createUnderlineSpanFromProto(in);
                    in.end(unToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.URL:
                    final long urlToken = in.start(RemoteViewsProto.CharSequence.Span.URL);
                    what = createURLSpanFromProto(in);
                    in.end(urlToken);
                    break;
                default:
                    Log.w(TAG, "Unhandled field while reading CharSequence proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }
        if (what == null) {
            return;
        }
        builder.setSpan(what, start, end, flags);
    }

    public static AbsoluteSizeSpan createAbsoluteSizeSpanFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        int size = 0;
        boolean dip = false;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.AbsoluteSize.SIZE:
                    size = in.readInt(RemoteViewsProto.CharSequence.Span.AbsoluteSize.SIZE);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.AbsoluteSize.DIP:
                    dip = in.readBoolean(RemoteViewsProto.CharSequence.Span.AbsoluteSize.DIP);
                    break;
                default:
                    Log.w("AbsoluteSizeSpan",
                            "Unhandled field while reading AbsoluteSizeSpan " + "proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        return new AbsoluteSizeSpan(size, dip);
    }

    public static void writeAbsoluteSizeSpanToProto(@NonNull ProtoOutputStream out,
            AbsoluteSizeSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.AbsoluteSize.SIZE, span.getSize());
        out.write(RemoteViewsProto.CharSequence.Span.AbsoluteSize.DIP, span.getDip());
    }

    public static AccessibilityClickableSpan createAccessibilityClickableSpanFromProto(
            @NonNull ProtoInputStream in) throws Exception {
        int originalClickableSpanId = 0;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span
                        .AccessibilityClickable.ORIGINAL_CLICKABLE_SPAN_ID:
                    originalClickableSpanId = in.readInt(
                            RemoteViewsProto.CharSequence.Span
                                    .AccessibilityClickable.ORIGINAL_CLICKABLE_SPAN_ID);
                    break;
                default:
                    Log.w("AccessibilityClickable",
                            "Unhandled field while reading" + " AccessibilityClickableSpan proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        return new AccessibilityClickableSpan(originalClickableSpanId);
    }

    public static void writeAccessibilityClickableSpanToProto(@NonNull ProtoOutputStream out,
            AccessibilityClickableSpan span) {
        out.write(
                RemoteViewsProto.CharSequence.Span
                        .AccessibilityClickable.ORIGINAL_CLICKABLE_SPAN_ID,
                span.getOriginalClickableSpanId());
    }

    public static AccessibilityReplacementSpan createAccessibilityReplacementSpanFromProto(
            @NonNull ProtoInputStream in) throws Exception {
        CharSequence contentDescription = null;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span
                        .AccessibilityReplacement.CONTENT_DESCRIPTION:
                    final long token = in.start(
                            RemoteViewsProto.CharSequence.Span
                                    .AccessibilityReplacement.CONTENT_DESCRIPTION);
                    contentDescription = createCharSequenceFromProto(in);
                    in.end(token);
                    break;
                default:
                    Log.w("AccessibilityReplacemen", "Unhandled field while reading"
                            + " AccessibilityReplacementSpan proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }
        return new AccessibilityReplacementSpan(contentDescription);
    }

    public static void writeAccessibilityReplacementSpanToProto(@NonNull ProtoOutputStream out,
            AccessibilityReplacementSpan span) {
        final long token = out.start(
                RemoteViewsProto.CharSequence.Span.AccessibilityReplacement.CONTENT_DESCRIPTION);
        CharSequence description = span.getContentDescription();
        if (description != null) {
            writeCharSequenceToProto(out, description);
        }
        out.end(token);
    }

    public static AccessibilityURLSpan createAccessibilityURLSpanFromProto(ProtoInputStream in)
            throws Exception {
        String url = null;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.AccessibilityUrl.URL:
                    url = in.readString(RemoteViewsProto.CharSequence.Span.AccessibilityUrl.URL);
                    break;
                default:
                    Log.w("AccessibilityURLSpan",
                            "Unhandled field while reading AccessibilityURLSpan " + "proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        return new AccessibilityURLSpan(new URLSpan(url));
    }

    public static void writeAccessibilityURLSpanToProto(@NonNull ProtoOutputStream out,
            AccessibilityURLSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.AccessibilityUrl.URL, span.getURL());
    }

    public static AlignmentSpan.Standard createAlignmentSpanStandardFromProto(
            @NonNull ProtoInputStream in) throws Exception {
        String alignment = null;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.Alignment.ALIGNMENT:
                    alignment = in.readString(
                            RemoteViewsProto.CharSequence.Span.Alignment.ALIGNMENT);
                    break;
                default:
                    Log.w("AlignmentSpan",
                            "Unhandled field while reading AlignmentSpan " + "proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        return new AlignmentSpan.Standard(Layout.Alignment.valueOf(alignment));
    }

    public static void writeAlignmentSpanStandardToProto(@NonNull ProtoOutputStream out,
            AlignmentSpan.Standard span) {
        out.write(RemoteViewsProto.CharSequence.Span.Alignment.ALIGNMENT,
                span.getAlignment().name());
    }

    public static Annotation createAnnotationFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        String key = null;
        String value = null;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.Annotation.KEY:
                    key = in.readString(RemoteViewsProto.CharSequence.Span.Annotation.KEY);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.Annotation.VALUE:
                    value = in.readString(RemoteViewsProto.CharSequence.Span.Annotation.VALUE);
                    break;
                default:
                    Log.w("Annotation", "Unhandled field while reading" + " Annotation proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }
        return new Annotation(key, value);
    }

    public static void writeAnnotationToProto(@NonNull ProtoOutputStream out, Annotation span) {
        out.write(RemoteViewsProto.CharSequence.Span.Annotation.KEY, span.getKey());
        out.write(RemoteViewsProto.CharSequence.Span.Annotation.VALUE, span.getValue());
    }

    public static BackgroundColorSpan createBackgroundColorSpanFromProto(
            @NonNull ProtoInputStream in) throws Exception {
        int color = 0;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.BackgroundColor.COLOR:
                    color = in.readInt(RemoteViewsProto.CharSequence.Span.BackgroundColor.COLOR);
                    break;
                default:
                    Log.w("BackgroundColorSpan",
                            "Unhandled field while reading" + " BackgroundColorSpan proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        return new BackgroundColorSpan(color);
    }

    public static void writeBackgroundColorSpanToProto(@NonNull ProtoOutputStream out,
            BackgroundColorSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.BackgroundColor.COLOR,
                span.getBackgroundColor());
    }

    public static BulletSpan createBulletSpanFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        int bulletRadius = 0;
        int color = 0;
        int gapWidth = 0;
        boolean wantColor = false;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.Bullet.BULLET_RADIUS:
                    bulletRadius = in.readInt(
                            RemoteViewsProto.CharSequence.Span.Bullet.BULLET_RADIUS);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.Bullet.COLOR:
                    color = in.readInt(RemoteViewsProto.CharSequence.Span.Bullet.COLOR);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.Bullet.GAP_WIDTH:
                    gapWidth = in.readInt(RemoteViewsProto.CharSequence.Span.Bullet.GAP_WIDTH);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.Bullet.WANT_COLOR:
                    wantColor = in.readBoolean(
                            RemoteViewsProto.CharSequence.Span.Bullet.WANT_COLOR);
                    break;
                default:
                    Log.w("BulletSpan", "Unhandled field while reading BulletSpan " + "proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }
        return new BulletSpan(gapWidth, color, wantColor, bulletRadius);
    }

    public static void writeBulletSpanToProto(@NonNull ProtoOutputStream out, BulletSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.Bullet.BULLET_RADIUS, span.getBulletRadius());
        out.write(RemoteViewsProto.CharSequence.Span.Bullet.COLOR, span.getColor());
        out.write(RemoteViewsProto.CharSequence.Span.Bullet.GAP_WIDTH, span.getGapWidth());
        out.write(RemoteViewsProto.CharSequence.Span.Bullet.WANT_COLOR, span.getWantColor());
    }

    public static EasyEditSpan createEasyEditSpanFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        return new EasyEditSpan();
    }

    public static void writeEasyEditSpanToProto(@NonNull ProtoOutputStream out, EasyEditSpan span) {
    }

    public static ForegroundColorSpan createForegroundColorSpanFromProto(
            @NonNull ProtoInputStream in) throws Exception {
        int color = 0;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.BackgroundColor.COLOR:
                    color = in.readInt(RemoteViewsProto.CharSequence.Span.BackgroundColor.COLOR);
                    break;
                default:
                    Log.w("ForegroundColorSpan",
                            "Unhandled field while reading" + " ForegroundColorSpan proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        return new ForegroundColorSpan(color);
    }

    public static LeadingMarginSpan.Standard createLeadingMarginSpanStandardFromProto(
            @NonNull ProtoInputStream in) throws Exception {
        int first = 0;
        int rest = 0;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.LeadingMargin.FIRST:
                    first = in.readInt(RemoteViewsProto.CharSequence.Span.LeadingMargin.FIRST);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.LeadingMargin.REST:
                    rest = in.readInt(RemoteViewsProto.CharSequence.Span.LeadingMargin.REST);
                    break;
                default:
                    Log.w("LeadingMarginSpan",
                            "Unhandled field while reading LeadingMarginSpan" + "proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        return new LeadingMarginSpan.Standard(first, rest);
    }

    public static void writeLeadingMarginSpanStandardToProto(@NonNull ProtoOutputStream out,
            LeadingMarginSpan.Standard span) {
        out.write(RemoteViewsProto.CharSequence.Span.LeadingMargin.FIRST,
                span.getLeadingMargin(/* first= */ true));
        out.write(RemoteViewsProto.CharSequence.Span.LeadingMargin.REST,
                span.getLeadingMargin(/* first= */ false));
    }

    public static void writeForegroundColorSpanToProto(@NonNull ProtoOutputStream out,
            ForegroundColorSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.ForegroundColor.COLOR,
                span.getForegroundColor());
    }

    public static LineBackgroundSpan.Standard createLineBackgroundSpanStandardFromProto(
            @NonNull ProtoInputStream in) throws Exception {
        int color = 0;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.LineBackground.COLOR:
                    color = in.readInt(RemoteViewsProto.CharSequence.Span.LineBackground.COLOR);
                    break;
                default:
                    Log.w("LineBackgroundSpan",
                            "Unhandled field while reading" + " LineBackgroundSpan proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        return new LineBackgroundSpan.Standard(color);
    }

    public static void writeLineBackgroundSpanStandardToProto(@NonNull ProtoOutputStream out,
            LineBackgroundSpan.Standard span) {
        out.write(RemoteViewsProto.CharSequence.Span.LineBackground.COLOR, span.getColor());
    }

    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static LineBreakConfigSpan createLineBreakConfigSpanFromProto(
            @NonNull ProtoInputStream in) throws Exception {
        int lineBreakStyle = 0;
        int lineBreakWordStyle = 0;
        int hyphenation = 0;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.LineBreak.LINE_BREAK_STYLE:
                    lineBreakStyle = in.readInt(
                            RemoteViewsProto.CharSequence.Span.LineBreak.LINE_BREAK_STYLE);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.LineBreak.LINE_BREAK_WORD_STYLE:
                    lineBreakWordStyle = in.readInt(
                            RemoteViewsProto.CharSequence.Span.LineBreak.LINE_BREAK_WORD_STYLE);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.LineBreak.HYPHENATION:
                    hyphenation = in.readInt(
                            RemoteViewsProto.CharSequence.Span.LineBreak.HYPHENATION);
                    break;
                default:
                    Log.w("LineBreakConfigSpan",
                            "Unhandled field while reading " + "LineBreakConfigSpan proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        LineBreakConfig lbc = new LineBreakConfig.Builder().setLineBreakStyle(
                lineBreakStyle).setLineBreakWordStyle(lineBreakWordStyle).setHyphenation(
                hyphenation).build();
        return new LineBreakConfigSpan(lbc);
    }

    @FlaggedApi(FLAG_NO_BREAK_NO_HYPHENATION_SPAN)
    public static void writeLineBreakConfigSpanToProto(@NonNull ProtoOutputStream out,
            LineBreakConfigSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.LineBreak.LINE_BREAK_STYLE,
                span.getLineBreakConfig().getLineBreakStyle());
        out.write(RemoteViewsProto.CharSequence.Span.LineBreak.LINE_BREAK_WORD_STYLE,
                span.getLineBreakConfig().getLineBreakWordStyle());
        out.write(RemoteViewsProto.CharSequence.Span.LineBreak.HYPHENATION,
                span.getLineBreakConfig().getHyphenation());
    }

    public static LineHeightSpan.Standard createLineHeightSpanStandardFromProto(
            @NonNull ProtoInputStream in) throws Exception {
        int height = 0;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.LineHeight.HEIGHT:
                    height = in.readInt(RemoteViewsProto.CharSequence.Span.LineHeight.HEIGHT);
                    break;
                default:
                    Log.w("LineHeightSpan.Standard",
                            "Unhandled field while reading" + " LineHeightSpan.Standard proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        return new LineHeightSpan.Standard(height);
    }

    public static void writeLineHeightSpanStandardToProto(@NonNull ProtoOutputStream out,
            LineHeightSpan.Standard span) {
        out.write(RemoteViewsProto.CharSequence.Span.LineHeight.HEIGHT, span.getHeight());
    }

    public static LocaleSpan createLocaleSpanFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        String languageTags = null;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.Locale.LANGUAGE_TAGS:
                    languageTags = in.readString(
                            RemoteViewsProto.CharSequence.Span.Locale.LANGUAGE_TAGS);
                    break;
                default:
                    Log.w("LocaleSpan", "Unhandled field while reading" + " LocaleSpan proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }
        return new LocaleSpan(LocaleList.forLanguageTags(languageTags));
    }

    public static void writeLocaleSpanToProto(@NonNull ProtoOutputStream out, LocaleSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.Locale.LANGUAGE_TAGS,
                span.getLocales().toLanguageTags());
    }

    public static QuoteSpan createQuoteSpanFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        int color = 0;
        int stripeWidth = 0;
        int gapWidth = 0;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.Quote.COLOR:
                    color = in.readInt(RemoteViewsProto.CharSequence.Span.Quote.COLOR);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.Quote.STRIPE_WIDTH:
                    stripeWidth = in.readInt(RemoteViewsProto.CharSequence.Span.Quote.STRIPE_WIDTH);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.Quote.GAP_WIDTH:
                    gapWidth = in.readInt(RemoteViewsProto.CharSequence.Span.Quote.GAP_WIDTH);
                    break;
                default:
                    Log.w("QuoteSpan", "Unhandled field while reading QuoteSpan " + "proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }
        return new QuoteSpan(color, stripeWidth, gapWidth);
    }

    public static void writeQuoteSpanToProto(@NonNull ProtoOutputStream out, QuoteSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.Quote.COLOR, span.getColor());
        out.write(RemoteViewsProto.CharSequence.Span.Quote.STRIPE_WIDTH, span.getStripeWidth());
        out.write(RemoteViewsProto.CharSequence.Span.Quote.GAP_WIDTH, span.getGapWidth());
    }

    public static RelativeSizeSpan createRelativeSizeSpanFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        float proportion = 0;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.RelativeSize.PROPORTION:
                    proportion = in.readFloat(
                            RemoteViewsProto.CharSequence.Span.RelativeSize.PROPORTION);
                    break;
                default:
                    Log.w("RelativeSizeSpan",
                            "Unhandled field while reading" + " RelativeSizeSpan proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        return new RelativeSizeSpan(proportion);
    }

    public static void writeRelativeSizeSpanToProto(@NonNull ProtoOutputStream out,
            RelativeSizeSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.RelativeSize.PROPORTION, span.getSizeChange());
    }

    public static ScaleXSpan createScaleXSpanFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        float proportion = 0f;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.ScaleX.PROPORTION:
                    proportion = in.readFloat(RemoteViewsProto.CharSequence.Span.ScaleX.PROPORTION);
                    break;
                default:
                    Log.w("ScaleXSpan", "Unhandled field while reading" + " ScaleXSpan proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }
        return new ScaleXSpan(proportion);
    }

    public static void writeScaleXSpanToProto(@NonNull ProtoOutputStream out, ScaleXSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.ScaleX.PROPORTION, span.getScaleX());
    }

    public static SpellCheckSpan createSpellCheckSpanFromProto(@NonNull ProtoInputStream in) {
        return new SpellCheckSpan();
    }

    public static void writeSpellCheckSpanToProto(@NonNull ProtoOutputStream out,
            SpellCheckSpan span) {
    }

    public static StrikethroughSpan createStrikethroughSpanFromProto(@NonNull ProtoInputStream in) {
        return new StrikethroughSpan();
    }

    public static void writeStrikethroughSpanToProto(@NonNull ProtoOutputStream out,
            StrikethroughSpan span) {
    }

    public static StyleSpan createStyleSpanFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        int style = 0;
        int fontWeightAdjustment = 0;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.Style.STYLE:
                    style = in.readInt(RemoteViewsProto.CharSequence.Span.Style.STYLE);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.Style.FONT_WEIGHT_ADJUSTMENT:
                    fontWeightAdjustment = in.readInt(
                            RemoteViewsProto.CharSequence.Span.Style.FONT_WEIGHT_ADJUSTMENT);
                    break;
                default:
                    Log.w("StyleSpan", "Unhandled field while reading StyleSpan " + "proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }
        return new StyleSpan(style, fontWeightAdjustment);
    }

    public static void writeStyleSpanToProto(@NonNull ProtoOutputStream out, StyleSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.Style.STYLE, span.getStyle());
        out.write(RemoteViewsProto.CharSequence.Span.Style.FONT_WEIGHT_ADJUSTMENT,
                span.getFontWeightAdjustment());
    }

    public static SubscriptSpan createSubscriptSpanFromProto(@NonNull ProtoInputStream in) {
        return new SubscriptSpan();
    }

    public static void writeSubscriptSpanToProto(@NonNull ProtoOutputStream out,
            SubscriptSpan span) {
    }

    public static SuggestionRangeSpan createSuggestionRangeSpanFromProto(
            @NonNull ProtoInputStream in) throws Exception {
        int backgroundColor = 0;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.SuggestionRange.BACKGROUND_COLOR:
                    backgroundColor = in.readInt(
                            RemoteViewsProto.CharSequence.Span.SuggestionRange.BACKGROUND_COLOR);
                    break;
                default:
                    Log.w("SuggestionRangeSpan",
                            "Unhandled field while reading" + " SuggestionRangeSpan proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        SuggestionRangeSpan span = new SuggestionRangeSpan();
        span.setBackgroundColor(backgroundColor);
        return span;
    }

    public static void writeSuggestionRangeSpanToProto(@NonNull ProtoOutputStream out,
            SuggestionRangeSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.SuggestionRange.BACKGROUND_COLOR,
                span.getBackgroundColor());
    }

    public static SuggestionSpan createSuggestionSpanFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        List<String> suggestions = new ArrayList<>();
        int flags = 0;
        String localeStringForCompatibility = null;
        String languageTag = null;
        int hashCode = 0;
        int easyCorrectUnderlineColor = 0;
        float easyCorrectUnderlineThickness = 0;
        int misspelledUnderlineColor = 0;
        float misspelledUnderlineThickness = 0;
        int autoCorrectionUnderlineColor = 0;
        float autoCorrectionUnderlineThickness = 0;
        int grammarErrorUnderlineColor = 0;
        float grammarErrorUnderlineThickness = 0;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.Suggestion.SUGGESTIONS:
                    suggestions.add(in.readString(
                            RemoteViewsProto.CharSequence.Span.Suggestion.SUGGESTIONS));
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.Suggestion.FLAGS:
                    flags = in.readInt(RemoteViewsProto.CharSequence.Span.Suggestion.FLAGS);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span
                        .Suggestion.LOCALE_STRING_FOR_COMPATIBILITY:
                    localeStringForCompatibility = in.readString(
                            RemoteViewsProto.CharSequence.Span
                                    .Suggestion.LOCALE_STRING_FOR_COMPATIBILITY);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.Suggestion.LANGUAGE_TAG:
                    languageTag = in.readString(
                            RemoteViewsProto.CharSequence.Span.Suggestion.LANGUAGE_TAG);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.Suggestion.HASH_CODE:
                    hashCode = in.readInt(RemoteViewsProto.CharSequence.Span.Suggestion.HASH_CODE);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span
                        .Suggestion.EASY_CORRECT_UNDERLINE_COLOR:
                    easyCorrectUnderlineColor = in.readInt(
                            RemoteViewsProto.CharSequence.Span
                                    .Suggestion.EASY_CORRECT_UNDERLINE_COLOR);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span
                        .Suggestion.EASY_CORRECT_UNDERLINE_THICKNESS:
                    easyCorrectUnderlineThickness = in.readFloat(
                            RemoteViewsProto.CharSequence.Span
                                    .Suggestion.EASY_CORRECT_UNDERLINE_THICKNESS);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span
                        .Suggestion.MISSPELLED_UNDERLINE_COLOR:
                    misspelledUnderlineColor = in.readInt(
                            RemoteViewsProto.CharSequence.Span
                                    .Suggestion.MISSPELLED_UNDERLINE_COLOR);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span
                        .Suggestion.MISSPELLED_UNDERLINE_THICKNESS:
                    misspelledUnderlineThickness = in.readFloat(
                            RemoteViewsProto.CharSequence.Span
                                    .Suggestion.MISSPELLED_UNDERLINE_THICKNESS);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span
                        .Suggestion.AUTO_CORRECTION_UNDERLINE_COLOR:
                    autoCorrectionUnderlineColor = in.readInt(
                            RemoteViewsProto.CharSequence.Span
                                    .Suggestion.AUTO_CORRECTION_UNDERLINE_COLOR);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span
                        .Suggestion.AUTO_CORRECTION_UNDERLINE_THICKNESS:
                    autoCorrectionUnderlineThickness = in.readFloat(
                            RemoteViewsProto.CharSequence.Span
                                    .Suggestion.AUTO_CORRECTION_UNDERLINE_THICKNESS);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span
                        .Suggestion.GRAMMAR_ERROR_UNDERLINE_COLOR:
                    grammarErrorUnderlineColor = in.readInt(
                            RemoteViewsProto.CharSequence.Span
                                    .Suggestion.GRAMMAR_ERROR_UNDERLINE_COLOR);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span
                        .Suggestion.GRAMMAR_ERROR_UNDERLINE_THICKNESS:
                    grammarErrorUnderlineThickness = in.readFloat(
                            RemoteViewsProto.CharSequence.Span
                                    .Suggestion.GRAMMAR_ERROR_UNDERLINE_THICKNESS);
                    break;
                default:
                    Log.w("SuggestionSpan",
                            "Unhandled field while reading SuggestionSpan " + "proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        String[] suggestionsArray = new String[suggestions.size()];
        suggestions.toArray(suggestionsArray);
        return new SuggestionSpan(suggestionsArray, flags, localeStringForCompatibility,
                languageTag, hashCode, easyCorrectUnderlineColor, easyCorrectUnderlineThickness,
                misspelledUnderlineColor, misspelledUnderlineThickness,
                autoCorrectionUnderlineColor, autoCorrectionUnderlineThickness,
                grammarErrorUnderlineColor, grammarErrorUnderlineThickness);
    }

    public static void writeSuggestionSpanToProto(@NonNull ProtoOutputStream out,
            SuggestionSpan span) {
        for (String suggestion : span.getSuggestions()) {
            out.write(RemoteViewsProto.CharSequence.Span.Suggestion.SUGGESTIONS, suggestion);
        }
        out.write(RemoteViewsProto.CharSequence.Span.Suggestion.FLAGS, span.getFlags());
        out.write(RemoteViewsProto.CharSequence.Span.Suggestion.LOCALE_STRING_FOR_COMPATIBILITY,
                span.getLocale());
        if (span.getLocaleObject() != null) {
            out.write(RemoteViewsProto.CharSequence.Span.Suggestion.LANGUAGE_TAG,
                    span.getLocaleObject().toLanguageTag());
        }
        out.write(RemoteViewsProto.CharSequence.Span.Suggestion.HASH_CODE, span.hashCode());
        out.write(RemoteViewsProto.CharSequence.Span.Suggestion.EASY_CORRECT_UNDERLINE_COLOR,
                span.getEasyCorrectUnderlineColor());
        out.write(RemoteViewsProto.CharSequence.Span.Suggestion.EASY_CORRECT_UNDERLINE_THICKNESS,
                span.getEasyCorrectUnderlineThickness());
        out.write(RemoteViewsProto.CharSequence.Span.Suggestion.MISSPELLED_UNDERLINE_COLOR,
                span.getMisspelledUnderlineColor());
        out.write(RemoteViewsProto.CharSequence.Span.Suggestion.MISSPELLED_UNDERLINE_THICKNESS,
                span.getMisspelledUnderlineThickness());
        out.write(RemoteViewsProto.CharSequence.Span.Suggestion.AUTO_CORRECTION_UNDERLINE_COLOR,
                span.getAutoCorrectionUnderlineColor());
        out.write(RemoteViewsProto.CharSequence.Span.Suggestion.AUTO_CORRECTION_UNDERLINE_THICKNESS,
                span.getAutoCorrectionUnderlineThickness());
        out.write(RemoteViewsProto.CharSequence.Span.Suggestion.GRAMMAR_ERROR_UNDERLINE_COLOR,
                span.getGrammarErrorUnderlineColor());
        out.write(RemoteViewsProto.CharSequence.Span.Suggestion.GRAMMAR_ERROR_UNDERLINE_THICKNESS,
                span.getGrammarErrorUnderlineThickness());
    }

    public static SuperscriptSpan createSuperscriptSpanFromProto(@NonNull ProtoInputStream in) {
        return new SuperscriptSpan();
    }

    public static void writeSuperscriptSpanToProto(@NonNull ProtoOutputStream out,
            SuperscriptSpan span) {
    }

    public static TextAppearanceSpan createTextAppearanceSpanFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        String familyName = null;
        int style = 0;
        int textSize = 0;
        ColorStateList textColor = null;
        ColorStateList textColorLink = null;
        int textFontWeight = 0;
        LocaleList textLocales = null;
        float shadowRadius = 0F;
        float shadowDx = 0F;
        float shadowDy = 0F;
        int shadowColor = 0;
        boolean hasElegantTextHeight = false;
        boolean elegantTextHeight = false;
        boolean hasLetterSpacing = false;
        float letterSpacing = 0F;
        String fontFeatureSettings = null;
        String fontVariationSettings = null;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.FAMILY_NAME:
                    familyName = in.readString(
                            RemoteViewsProto.CharSequence.Span.TextAppearance.FAMILY_NAME);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.STYLE:
                    style = in.readInt(RemoteViewsProto.CharSequence.Span.TextAppearance.STYLE);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_SIZE:
                    textSize = in.readInt(
                            RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_SIZE);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_COLOR:
                    final long textColorToken = in.start(
                            RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_COLOR);
                    textColor = ColorStateList.createFromProto(in);
                    in.end(textColorToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_COLOR_LINK:
                    final long textColorLinkToken = in.start(
                            RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_COLOR_LINK);
                    textColorLink = ColorStateList.createFromProto(in);
                    in.end(textColorLinkToken);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_FONT_WEIGHT:
                    textFontWeight = in.readInt(
                            RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_FONT_WEIGHT);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_LOCALE:
                    textLocales = LocaleList.forLanguageTags(in.readString(
                            RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_LOCALE));
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.SHADOW_RADIUS:
                    shadowRadius = in.readFloat(
                            RemoteViewsProto.CharSequence.Span.TextAppearance.SHADOW_RADIUS);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.SHADOW_DX:
                    shadowDx = in.readFloat(
                            RemoteViewsProto.CharSequence.Span.TextAppearance.SHADOW_DX);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.SHADOW_DY:
                    shadowDy = in.readFloat(
                            RemoteViewsProto.CharSequence.Span.TextAppearance.SHADOW_DY);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.SHADOW_COLOR:
                    shadowColor = in.readInt(
                            RemoteViewsProto.CharSequence.Span.TextAppearance.SHADOW_COLOR);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span
                        .TextAppearance.HAS_ELEGANT_TEXT_HEIGHT_FIELD:
                    hasElegantTextHeight = in.readBoolean(
                            RemoteViewsProto.CharSequence.Span
                                    .TextAppearance.HAS_ELEGANT_TEXT_HEIGHT_FIELD);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.ELEGANT_TEXT_HEIGHT:
                    elegantTextHeight = in.readBoolean(
                            RemoteViewsProto.CharSequence.Span.TextAppearance.ELEGANT_TEXT_HEIGHT);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span
                        .TextAppearance.HAS_LETTER_SPACING_FIELD:
                    hasLetterSpacing = in.readBoolean(
                            RemoteViewsProto.CharSequence.Span
                                    .TextAppearance.HAS_LETTER_SPACING_FIELD);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.LETTER_SPACING:
                    letterSpacing = in.readFloat(
                            RemoteViewsProto.CharSequence.Span.TextAppearance.LETTER_SPACING);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.TextAppearance.FONT_FEATURE_SETTINGS:
                    fontFeatureSettings = in.readString(
                            RemoteViewsProto.CharSequence.Span
                                    .TextAppearance.FONT_FEATURE_SETTINGS);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span
                        .TextAppearance.FONT_VARIATION_SETTINGS:
                    fontVariationSettings = in.readString(
                            RemoteViewsProto.CharSequence.Span
                                    .TextAppearance.FONT_VARIATION_SETTINGS);
                    break;
                default:
                    Log.w("TextAppearanceSpan",
                            "Unhandled field while reading TextAppearanceSpan " + "proto!\n"
                                    + ProtoUtils.currentFieldToString(in));
            }
        }
        return new TextAppearanceSpan(familyName, style, textSize, textColor, textColorLink,
                /* typeface= */ null, textFontWeight, textLocales, shadowRadius, shadowDx, shadowDy,
                shadowColor, hasElegantTextHeight, elegantTextHeight, hasLetterSpacing,
                letterSpacing, fontFeatureSettings, fontVariationSettings);
    }

    public static void writeTextAppearanceSpanToProto(@NonNull ProtoOutputStream out,
            TextAppearanceSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.FAMILY_NAME, span.getFamily());
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.STYLE, span.getTextStyle());
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_SIZE, span.getTextSize());
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_FONT_WEIGHT,
                span.getTextFontWeight());
        if (span.getTextLocales() != null) {
            out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_LOCALE,
                    span.getTextLocales().toLanguageTags());
        }
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.SHADOW_RADIUS,
                span.getShadowRadius());
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.SHADOW_DX, span.getShadowDx());
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.SHADOW_DY, span.getShadowDy());
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.SHADOW_COLOR,
                span.getShadowColor());
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.HAS_ELEGANT_TEXT_HEIGHT_FIELD,
                span.hasElegantTextHeight());
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.ELEGANT_TEXT_HEIGHT,
                span.isElegantTextHeight());
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.HAS_LETTER_SPACING_FIELD,
                span.hasLetterSpacing());
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.LETTER_SPACING,
                span.getLetterSpacing());
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.FONT_FEATURE_SETTINGS,
                span.getFontFeatureSettings());
        out.write(RemoteViewsProto.CharSequence.Span.TextAppearance.FONT_VARIATION_SETTINGS,
                span.getFontVariationSettings());
        if (span.getTextColor() != null) {
            final long textColorToken = out.start(
                    RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_COLOR);
            span.getTextColor().writeToProto(out);
            out.end(textColorToken);
        }
        if (span.getLinkTextColor() != null) {
            final long textColorLinkToken = out.start(
                    RemoteViewsProto.CharSequence.Span.TextAppearance.TEXT_COLOR_LINK);
            span.getLinkTextColor().writeToProto(out);
            out.end(textColorLinkToken);
        }
    }

    public static TtsSpan createTtsSpanFromProto(@NonNull ProtoInputStream in) throws Exception {
        String type = null;
        PersistableBundle args = null;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.Tts.TYPE:
                    type = in.readString(RemoteViewsProto.CharSequence.Span.Tts.TYPE);
                    break;
                case (int) RemoteViewsProto.CharSequence.Span.Tts.ARGS:
                    final byte[] data = in.readString(
                            RemoteViewsProto.CharSequence.Span.Tts.ARGS).getBytes();
                    args = PersistableBundle.readFromStream(new ByteArrayInputStream(data));
                    break;
                default:
                    Log.w("TtsSpan", "Unhandled field while reading TtsSpan " + "proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }
        return new TtsSpan(type, args);
    }

    public static void writeTtsSpanToProto(@NonNull ProtoOutputStream out, TtsSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.Tts.TYPE, span.getType());
        if (span.getArgs() != null) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try {
                span.getArgs().writeToStream(buf);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            out.write(RemoteViewsProto.CharSequence.Span.Tts.ARGS, buf.toString(UTF_8));
        }
    }

    public static TypefaceSpan createTypefaceSpanFromProto(@NonNull ProtoInputStream in)
            throws Exception {
        String family = null;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.Typeface.FAMILY:
                    family = in.readString(RemoteViewsProto.CharSequence.Span.Typeface.FAMILY);
                    break;
                default:
                    Log.w("TypefaceSpan", "Unhandled field while reading" + " TypefaceSpan proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }
        return new TypefaceSpan(family);
    }

    public static void writeTypefaceSpanToProto(@NonNull ProtoOutputStream out, TypefaceSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.Typeface.FAMILY, span.getFamily());
    }

    public static URLSpan createURLSpanFromProto(@NonNull ProtoInputStream in) throws Exception {
        String url = null;
        while (in.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            switch (in.getFieldNumber()) {
                case (int) RemoteViewsProto.CharSequence.Span.Url.URL:
                    url = in.readString(RemoteViewsProto.CharSequence.Span.Url.URL);
                    break;
                default:
                    Log.w("URLSpan", "Unhandled field while reading" + " URLSpan proto!\n"
                            + ProtoUtils.currentFieldToString(in));
            }
        }
        return new URLSpan(url);
    }

    public static void writeURLSpanToProto(@NonNull ProtoOutputStream out, URLSpan span) {
        out.write(RemoteViewsProto.CharSequence.Span.Url.URL, span.getURL());
    }

    public static UnderlineSpan createUnderlineSpanFromProto(@NonNull ProtoInputStream in) {
        return new UnderlineSpan();
    }

    public static void writeUnderlineSpanToProto(@NonNull ProtoOutputStream out,
            UnderlineSpan span) {
    }
}
