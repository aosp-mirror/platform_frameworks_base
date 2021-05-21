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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Person;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.content.ComponentName;
import android.content.Intent;
import android.content.LocusId;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * @hide
 */
public class AppSearchShortcutInfo extends GenericDocument {

    /** The name of the schema type for {@link ShortcutInfo} documents.*/
    public static final String SCHEMA_TYPE = "Shortcut";
    public static final int SCHEMA_VERSION = 2;

    public static final String KEY_ACTIVITY = "activity";
    public static final String KEY_SHORT_LABEL = "shortLabel";
    public static final String KEY_SHORT_LABEL_RES_ID = "shortLabelResId";
    public static final String KEY_SHORT_LABEL_RES_NAME = "shortLabelResName";
    public static final String KEY_LONG_LABEL = "longLabel";
    public static final String KEY_LONG_LABEL_RES_ID = "longLabelResId";
    public static final String KEY_LONG_LABEL_RES_NAME = "longLabelResName";
    public static final String KEY_DISABLED_MESSAGE = "disabledMessage";
    public static final String KEY_DISABLED_MESSAGE_RES_ID = "disabledMessageResId";
    public static final String KEY_DISABLED_MESSAGE_RES_NAME = "disabledMessageResName";
    public static final String KEY_CATEGORIES = "categories";
    public static final String KEY_INTENTS = "intents";
    public static final String KEY_INTENT_PERSISTABLE_EXTRAS = "intentPersistableExtras";
    public static final String KEY_PERSON = "person";
    public static final String KEY_LOCUS_ID = "locusId";
    public static final String KEY_RANK = "rank";
    public static final String KEY_IMPLICIT_RANK = "implicitRank";
    public static final String KEY_EXTRAS = "extras";
    public static final String KEY_FLAGS = "flags";
    public static final String KEY_ICON_RES_ID = "iconResId";
    public static final String KEY_ICON_RES_NAME = "iconResName";
    public static final String KEY_ICON_URI = "iconUri";
    public static final String KEY_BITMAP_PATH = "bitmapPath";
    public static final String KEY_DISABLED_REASON = "disabledReason";

    public static final AppSearchSchema SCHEMA = new AppSearchSchema.Builder(SCHEMA_TYPE)
            .addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_ACTIVITY)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_SHORT_LABEL)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new AppSearchSchema.LongPropertyConfig.Builder(KEY_SHORT_LABEL_RES_ID)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_SHORT_LABEL_RES_NAME)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_LONG_LABEL)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new AppSearchSchema.LongPropertyConfig.Builder(KEY_LONG_LABEL_RES_ID)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_LONG_LABEL_RES_NAME)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_DISABLED_MESSAGE)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                    .build()

            ).addProperty(new AppSearchSchema.LongPropertyConfig.Builder(
                    KEY_DISABLED_MESSAGE_RES_ID)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(
                    KEY_DISABLED_MESSAGE_RES_NAME)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_CATEGORIES)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_INTENTS)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                    .build()

            ).addProperty(new AppSearchSchema.BytesPropertyConfig.Builder(
                    KEY_INTENT_PERSISTABLE_EXTRAS)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .build()

            ).addProperty(new AppSearchSchema.DocumentPropertyConfig.Builder(
                    KEY_PERSON, AppSearchPerson.SCHEMA_TYPE)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_LOCUS_ID)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_RANK)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                    .build()

            ).addProperty(new AppSearchSchema.LongPropertyConfig.Builder(KEY_IMPLICIT_RANK)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build()

            ).addProperty(new AppSearchSchema.BytesPropertyConfig.Builder(KEY_EXTRAS)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_FLAGS)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                    .build()

            ).addProperty(new AppSearchSchema.LongPropertyConfig.Builder(KEY_ICON_RES_ID)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_ICON_RES_NAME)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_ICON_URI)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_BITMAP_PATH)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_DISABLED_REASON)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                    .build()

            ).build();

    /**
     * The string representation of every flag within {@link ShortcutInfo}. Note that its value
     * needs to be camelCase since AppSearch's tokenizer will break the word when it sees
     * underscore.
     */
    private static final String IS_DYNAMIC = "Dyn";
    private static final String NOT_DYNAMIC = "nDyn";
    private static final String IS_PINNED = "Pin";
    private static final String NOT_PINNED = "nPin";
    private static final String HAS_ICON_RES = "IcR";
    private static final String NO_ICON_RES = "nIcR";
    private static final String HAS_ICON_FILE = "IcF";
    private static final String NO_ICON_FILE = "nIcF";
    private static final String IS_KEY_FIELD_ONLY = "Key";
    private static final String NOT_KEY_FIELD_ONLY = "nKey";
    private static final String IS_MANIFEST = "Man";
    private static final String NOT_MANIFEST = "nMan";
    private static final String IS_DISABLED = "Dis";
    private static final String NOT_DISABLED = "nDis";
    private static final String ARE_STRINGS_RESOLVED = "Str";
    private static final String NOT_STRINGS_RESOLVED = "nStr";
    private static final String IS_IMMUTABLE = "Im";
    private static final String NOT_IMMUTABLE = "nIm";
    private static final String HAS_ADAPTIVE_BITMAP = "IcA";
    private static final String NO_ADAPTIVE_BITMAP = "nIcA";
    private static final String IS_RETURNED_BY_SERVICE = "Rets";
    private static final String NOT_RETURNED_BY_SERVICE = "nRets";
    private static final String HAS_ICON_FILE_PENDING_SAVE = "Pens";
    private static final String NO_ICON_FILE_PENDING_SAVE = "nPens";
    private static final String IS_SHADOW = "Sdw";
    private static final String NOT_SHADOW = "nSdw";
    private static final String IS_LONG_LIVED = "Liv";
    private static final String NOT_LONG_LIVED = "nLiv";
    private static final String HAS_ICON_URI = "IcU";
    private static final String NO_ICON_URI = "nIcU";
    private static final String IS_CACHED_NOTIFICATION = "CaN";
    private static final String NOT_CACHED_NOTIFICATION = "nCaN";
    private static final String IS_CACHED_BUBBLE = "CaB";
    private static final String NOT_CACHED_BUBBLE = "nCaB";
    private static final String IS_CACHED_PEOPLE_TITLE = "CaPT";
    private static final String NOT_CACHED_PEOPLE_TITLE = "nCaPT";

    /**
     * Following flags are not store within ShortcutInfo, but book-keeping states to reduce search
     * space when performing queries against AppSearch.
     */
    private static final String HAS_BITMAP_PATH = "hBiP";
    private static final String HAS_STRING_RESOURCE = "hStr";
    private static final String HAS_NON_ZERO_RANK = "hRan";

    public static final String QUERY_IS_DYNAMIC = KEY_FLAGS + ":" + IS_DYNAMIC;
    public static final String QUERY_IS_NOT_DYNAMIC = KEY_FLAGS + ":" + NOT_DYNAMIC;
    public static final String QUERY_IS_PINNED = KEY_FLAGS + ":" + IS_PINNED;
    public static final String QUERY_IS_NOT_PINNED = KEY_FLAGS + ":" + NOT_PINNED;
    public static final String QUERY_IS_MANIFEST = KEY_FLAGS + ":" + IS_MANIFEST;
    public static final String QUERY_IS_NOT_MANIFEST = KEY_FLAGS + ":" + NOT_MANIFEST;
    public static final String QUERY_IS_PINNED_AND_ENABLED =
            "(" + KEY_FLAGS + ":" + IS_PINNED + " " + KEY_FLAGS + ":" + NOT_DISABLED + ")";
    public static final String QUERY_IS_CACHED =
            "(" + KEY_FLAGS + ":" + IS_CACHED_NOTIFICATION + " OR "
            + KEY_FLAGS + ":" + IS_CACHED_BUBBLE + " OR "
            + KEY_FLAGS + ":" + IS_CACHED_PEOPLE_TITLE + ")";
    public static final String QUERY_IS_NOT_CACHED =
            "(" + KEY_FLAGS + ":" + NOT_CACHED_NOTIFICATION + " "
                    + KEY_FLAGS + ":" + NOT_CACHED_BUBBLE + " "
                    + KEY_FLAGS + ":" + NOT_CACHED_PEOPLE_TITLE + ")";
    public static final String QUERY_IS_FLOATING =
            "((" + IS_PINNED + " OR " + QUERY_IS_CACHED + ") "
                    + QUERY_IS_NOT_DYNAMIC + " " + QUERY_IS_NOT_MANIFEST + ")";
    public static final String QUERY_IS_NOT_FLOATING =
            "((" + QUERY_IS_NOT_PINNED + " " + QUERY_IS_NOT_CACHED + ") OR "
                    + QUERY_IS_DYNAMIC + " OR " + QUERY_IS_MANIFEST + ")";
    public static final String QUERY_IS_VISIBLE_TO_PUBLISHER =
            "(" + KEY_DISABLED_REASON + ":" + ShortcutInfo.DISABLED_REASON_NOT_DISABLED
                    + " OR " + KEY_DISABLED_REASON + ":"
                    + ShortcutInfo.DISABLED_REASON_BY_APP
                    + " OR " + KEY_DISABLED_REASON + ":"
                    + ShortcutInfo.DISABLED_REASON_APP_CHANGED
                    + " OR " + KEY_DISABLED_REASON + ":"
                    + ShortcutInfo.DISABLED_REASON_UNKNOWN + ")";
    public static final String QUERY_DISABLED_REASON_VERSION_LOWER =
            KEY_DISABLED_REASON + ":" + ShortcutInfo.DISABLED_REASON_VERSION_LOWER;
    public static final String QUERY_IS_NON_MANIFEST_VISIBLE =
            "(" + QUERY_IS_NOT_MANIFEST + " " + QUERY_IS_VISIBLE_TO_PUBLISHER + " ("
                    + QUERY_IS_PINNED + " OR " + QUERY_IS_CACHED + " OR " + QUERY_IS_DYNAMIC + "))";
    public static final String QUERY_IS_VISIBLE_CACHED_OR_PINNED =
            "(" + QUERY_IS_VISIBLE_TO_PUBLISHER + " " + QUERY_IS_DYNAMIC
                    + " (" + QUERY_IS_CACHED + " OR " + QUERY_IS_PINNED + "))";
    public static final String QUERY_IS_VISIBLE_PINNED_ONLY =
            "(" + QUERY_IS_VISIBLE_TO_PUBLISHER + " " + QUERY_IS_PINNED + " " + QUERY_IS_NOT_CACHED
            + " " + QUERY_IS_NOT_DYNAMIC + " " + QUERY_IS_NOT_MANIFEST + ")";
    public static final String QUERY_HAS_BITMAP_PATH = KEY_FLAGS + ":" + HAS_BITMAP_PATH;
    public static final String QUERY_HAS_STRING_RESOURCE = KEY_FLAGS + ":" + HAS_STRING_RESOURCE;
    public static final String QUERY_HAS_NON_ZERO_RANK = KEY_FLAGS + ":" + HAS_NON_ZERO_RANK;
    public static final String QUERY_IS_FLOATING_AND_HAS_RANK =
            "(" + QUERY_IS_FLOATING + " " + QUERY_HAS_NON_ZERO_RANK + ")";

    public AppSearchShortcutInfo(@NonNull GenericDocument document) {
        super(document);
    }

    /**
     * @hide
     */
    @NonNull
    public static AppSearchShortcutInfo instance(@NonNull final ShortcutInfo shortcutInfo) {
        Objects.requireNonNull(shortcutInfo);
        return new Builder(shortcutInfo.getPackage(), shortcutInfo.getId())
                .setActivity(shortcutInfo.getActivity())
                .setShortLabel(shortcutInfo.getShortLabel())
                .setShortLabelResId(shortcutInfo.getShortLabelResourceId())
                .setShortLabelResName(shortcutInfo.getTitleResName())
                .setLongLabel(shortcutInfo.getLongLabel())
                .setLongLabelResId(shortcutInfo.getLongLabelResourceId())
                .setLongLabelResName(shortcutInfo.getTextResName())
                .setDisabledMessage(shortcutInfo.getDisabledMessage())
                .setDisabledMessageResId(shortcutInfo.getDisabledMessageResourceId())
                .setDisabledMessageResName(shortcutInfo.getDisabledMessageResName())
                .setCategories(shortcutInfo.getCategories())
                .setIntents(shortcutInfo.getIntents())
                .setRank(shortcutInfo.getRank())
                .setImplicitRank(shortcutInfo.getImplicitRank()
                        | (shortcutInfo.isRankChanged() ? ShortcutInfo.RANK_CHANGED_BIT : 0))
                .setExtras(shortcutInfo.getExtras())
                .setCreationTimestampMillis(shortcutInfo.getLastChangedTimestamp())
                .setFlags(shortcutInfo.getFlags())
                .setIconResId(shortcutInfo.getIconResourceId())
                .setIconResName(shortcutInfo.getIconResName())
                .setBitmapPath(shortcutInfo.getBitmapPath())
                .setIconUri(shortcutInfo.getIconUri())
                .setDisabledReason(shortcutInfo.getDisabledReason())
                .setPersons(shortcutInfo.getPersons())
                .setLocusId(shortcutInfo.getLocusId())
                .build();
    }

    /**
     * @hide
     */
    @NonNull
    public ShortcutInfo toShortcutInfo(@UserIdInt int userId) {
        final String packageName = getNamespace();
        final String activityString = getPropertyString(KEY_ACTIVITY);
        final ComponentName activity = activityString == null
                ? null : ComponentName.unflattenFromString(activityString);
        // TODO: proper icon handling
        // NOTE: bitmap based icons are currently saved in side-channel (see ShortcutBitmapSaver),
        // re-creating Icon object at creation time implies turning this function into async since
        // loading bitmap is I/O bound. Since ShortcutInfo#getIcon is already annotated with
        // @hide and @UnsupportedAppUsage, we could migrate existing usage in platform with
        // LauncherApps#getShortcutIconDrawable instead.
        final Icon icon = null;
        final String shortLabel = getPropertyString(KEY_SHORT_LABEL);
        final int shortLabelResId = (int) getPropertyLong(KEY_SHORT_LABEL_RES_ID);
        final String shortLabelResName = getPropertyString(KEY_SHORT_LABEL_RES_NAME);
        final String longLabel = getPropertyString(KEY_LONG_LABEL);
        final int longLabelResId = (int) getPropertyLong(KEY_LONG_LABEL_RES_ID);
        final String longLabelResName = getPropertyString(KEY_LONG_LABEL_RES_NAME);
        final String disabledMessage = getPropertyString(KEY_DISABLED_MESSAGE);
        final int disabledMessageResId = (int) getPropertyLong(KEY_DISABLED_MESSAGE_RES_ID);
        final String disabledMessageResName = getPropertyString(KEY_DISABLED_MESSAGE_RES_NAME);
        final String[] categories = getPropertyStringArray(KEY_CATEGORIES);
        final Set<String> categoriesSet = categories == null
                ? null : new ArraySet<>(Arrays.asList(categories));
        final String[] intentsStrings = getPropertyStringArray(KEY_INTENTS);
        final Intent[] intents = intentsStrings == null
                ? new Intent[0] : Arrays.stream(intentsStrings).map(uri -> {
                    if (TextUtils.isEmpty(uri)) {
                        return new Intent(Intent.ACTION_VIEW);
                    }
                    try {
                        return Intent.parseUri(uri, /* flags =*/ 0);
                    } catch (URISyntaxException e) {
                        // ignore malformed entry
                    }
                    return null;
                }).toArray(Intent[]::new);
        final byte[][] intentExtrasesBytes = getPropertyBytesArray(KEY_INTENT_PERSISTABLE_EXTRAS);
        final Bundle[] intentExtrases = intentExtrasesBytes == null
                ? null : Arrays.stream(intentExtrasesBytes)
                .map(this::transformToBundle).toArray(Bundle[]::new);
        if (intents != null) {
            for (int i = 0; i < intents.length; i++) {
                final Intent intent = intents[i];
                if (intent == null || intentExtrases == null || intentExtrases.length <= i
                        || intentExtrases[i] == null || intentExtrases[i].size() == 0) {
                    continue;
                }
                intent.replaceExtras(intentExtrases[i]);
            }
        }
        final Person[] persons = parsePerson(getPropertyDocumentArray(KEY_PERSON));
        final String locusIdString = getPropertyString(KEY_LOCUS_ID);
        final LocusId locusId = locusIdString == null ? null : new LocusId(locusIdString);
        final int rank = Integer.parseInt(getPropertyString(KEY_RANK));
        final int implicitRank = (int) getPropertyLong(KEY_IMPLICIT_RANK);
        final byte[] extrasByte = getPropertyBytes(KEY_EXTRAS);
        final PersistableBundle extras = transformToPersistableBundle(extrasByte);
        final int flags = parseFlags(getPropertyStringArray(KEY_FLAGS));
        final int iconResId = (int) getPropertyLong(KEY_ICON_RES_ID);
        final String iconResName = getPropertyString(KEY_ICON_RES_NAME);
        final String iconUri = getPropertyString(KEY_ICON_URI);
        final String bitmapPath = getPropertyString(KEY_BITMAP_PATH);
        final int disabledReason = Integer.parseInt(getPropertyString(KEY_DISABLED_REASON));
        final ShortcutInfo si = new ShortcutInfo(
                userId, getId(), packageName, activity, icon, shortLabel, shortLabelResId,
                shortLabelResName, longLabel, longLabelResId, longLabelResName, disabledMessage,
                disabledMessageResId, disabledMessageResName, categoriesSet, intents, rank, extras,
                getCreationTimestampMillis(), flags, iconResId, iconResName, bitmapPath, iconUri,
                disabledReason, persons, locusId, 0);
        si.setImplicitRank(implicitRank);
        if ((implicitRank & ShortcutInfo.RANK_CHANGED_BIT) != 0) {
            si.setRankChanged();
        }
        return si;
    }

    /**
     * @hide
     */
    @NonNull
    public static List<GenericDocument> toGenericDocuments(
            @NonNull final Collection<ShortcutInfo> shortcuts) {
        final List<GenericDocument> docs = new ArrayList<>(shortcuts.size());
        for (ShortcutInfo si : shortcuts) {
            docs.add(AppSearchShortcutInfo.instance(si));
        }
        return docs;
    }

    /** @hide */
    @VisibleForTesting
    public static class Builder extends GenericDocument.Builder<Builder> {

        private List<String> mFlags = new ArrayList<>(1);
        private boolean mHasStringResource = false;

        public Builder(String packageName, String id) {
            super(/*namespace=*/ packageName, id, SCHEMA_TYPE);
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setLocusId(@Nullable final LocusId locusId) {
            if (locusId != null) {
                setPropertyString(KEY_LOCUS_ID, locusId.getId());
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setActivity(@Nullable final ComponentName activity) {
            if (activity != null) {
                setPropertyString(KEY_ACTIVITY, activity.flattenToShortString());
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setShortLabel(@Nullable final CharSequence shortLabel) {
            if (!TextUtils.isEmpty(shortLabel)) {
                setPropertyString(KEY_SHORT_LABEL, Preconditions.checkStringNotEmpty(
                        shortLabel, "shortLabel cannot be empty").toString());
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setShortLabelResId(final int shortLabelResId) {
            setPropertyLong(KEY_SHORT_LABEL_RES_ID, shortLabelResId);
            if (shortLabelResId != 0) {
                mHasStringResource = true;
            }
            return this;
        }

        /**
         * @hide
         */
        public Builder setShortLabelResName(@Nullable final String shortLabelResName) {
            if (!TextUtils.isEmpty(shortLabelResName)) {
                setPropertyString(KEY_SHORT_LABEL_RES_NAME, shortLabelResName);
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setLongLabel(@Nullable final CharSequence longLabel) {
            if (!TextUtils.isEmpty(longLabel)) {
                setPropertyString(KEY_LONG_LABEL, Preconditions.checkStringNotEmpty(
                        longLabel, "longLabel cannot be empty").toString());
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setLongLabelResId(final int longLabelResId) {
            setPropertyLong(KEY_LONG_LABEL_RES_ID, longLabelResId);
            if (longLabelResId != 0) {
                mHasStringResource = true;
            }
            return this;
        }

        /**
         * @hide
         */
        public Builder setLongLabelResName(@Nullable final String longLabelResName) {
            if (!TextUtils.isEmpty(longLabelResName)) {
                setPropertyString(KEY_LONG_LABEL_RES_NAME, longLabelResName);
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setDisabledMessage(@Nullable final CharSequence disabledMessage) {
            if (!TextUtils.isEmpty(disabledMessage)) {
                setPropertyString(KEY_DISABLED_MESSAGE, Preconditions.checkStringNotEmpty(
                        disabledMessage, "disabledMessage cannot be empty").toString());
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setDisabledMessageResId(final int disabledMessageResId) {
            setPropertyLong(KEY_DISABLED_MESSAGE_RES_ID, disabledMessageResId);
            if (disabledMessageResId != 0) {
                mHasStringResource = true;
            }
            return this;
        }

        /**
         * @hide
         */
        public Builder setDisabledMessageResName(@Nullable final String disabledMessageResName) {
            if (!TextUtils.isEmpty(disabledMessageResName)) {
                setPropertyString(KEY_DISABLED_MESSAGE_RES_NAME, disabledMessageResName);
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setCategories(@Nullable final Set<String> categories) {
            if (categories != null && !categories.isEmpty()) {
                setPropertyString(KEY_CATEGORIES, categories.stream().toArray(String[]::new));
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setIntent(@Nullable final Intent intent) {
            if (intent == null) {
                return this;
            }
            return setIntents(new Intent[]{intent});
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setIntents(@Nullable final Intent[] intents) {
            if (intents == null || intents.length == 0) {
                return this;
            }
            for (Intent intent : intents) {
                Objects.requireNonNull(intent, "intents cannot contain null");
                Objects.requireNonNull(intent.getAction(), "intent's action must be set");
            }
            final byte[][] intentExtrases = new byte[intents.length][];
            for (int i = 0; i < intents.length; i++) {
                final Intent intent = intents[i];
                final Bundle extras = intent.getExtras();
                intentExtrases[i] = extras == null
                        ? new byte[0] : transformToByteArray(new PersistableBundle(extras));
            }
            setPropertyString(KEY_INTENTS, Arrays.stream(intents).map(it -> it.toUri(0))
                    .toArray(String[]::new));
            setPropertyBytes(KEY_INTENT_PERSISTABLE_EXTRAS, intentExtrases);
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setPerson(@Nullable final Person person) {
            if (person == null) {
                return this;
            }
            return setPersons(new Person[]{person});
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setPersons(@Nullable final Person[] persons) {
            if (persons == null || persons.length == 0) {
                return this;
            }
            final GenericDocument[] documents = new GenericDocument[persons.length];
            for (int i = 0; i < persons.length; i++) {
                final Person person = persons[i];
                if (person == null) continue;
                final AppSearchPerson appSearchPerson = AppSearchPerson.instance(person);
                documents[i] = appSearchPerson;
            }
            setPropertyDocument(KEY_PERSON, documents);
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setRank(final int rank) {
            Preconditions.checkArgument((0 <= rank), "Rank cannot be negative");
            setPropertyString(KEY_RANK, String.valueOf(rank));
            if (rank != 0) {
                mFlags.add(HAS_NON_ZERO_RANK);
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setImplicitRank(final int rank) {
            setPropertyLong(KEY_IMPLICIT_RANK, rank);
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setExtras(@Nullable final PersistableBundle extras) {
            if (extras != null) {
                setPropertyBytes(KEY_EXTRAS, transformToByteArray(extras));
            }
            return this;
        }

        /**
         * @hide
         */
        public Builder setFlags(@ShortcutInfo.ShortcutFlags final int flags) {
            final String[] flagArray = flattenFlags(flags);
            if (flagArray != null && flagArray.length > 0) {
                mFlags.addAll(Arrays.asList(flagArray));
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        public Builder setIconResId(@Nullable final int iconResId) {
            setPropertyLong(KEY_ICON_RES_ID, iconResId);
            return this;
        }

        /**
         * @hide
         */
        public Builder setIconResName(@Nullable final String iconResName) {
            if (!TextUtils.isEmpty(iconResName)) {
                setPropertyString(KEY_ICON_RES_NAME, iconResName);
            }
            return this;
        }

        /**
         * @hide
         */
        public Builder setBitmapPath(@Nullable final String bitmapPath) {
            if (!TextUtils.isEmpty(bitmapPath)) {
                setPropertyString(KEY_BITMAP_PATH, bitmapPath);
                mFlags.add(HAS_BITMAP_PATH);
            }
            return this;
        }

        /**
         * @hide
         */
        public Builder setIconUri(@Nullable final String iconUri) {
            if (!TextUtils.isEmpty(iconUri)) {
                setPropertyString(KEY_ICON_URI, iconUri);
            }
            return this;
        }

        /**
         * @hide
         */
        public Builder setDisabledReason(@ShortcutInfo.DisabledReason final int disabledReason) {
            setPropertyString(KEY_DISABLED_REASON, String.valueOf(disabledReason));
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        @Override
        public AppSearchShortcutInfo build() {
            if (mHasStringResource) {
                mFlags.add(HAS_STRING_RESOURCE);
            }
            setPropertyString(KEY_FLAGS, mFlags.toArray(new String[0]));
            return new AppSearchShortcutInfo(super.build());
        }
    }

    /**
     * Convert PersistableBundle into byte[] for persistence.
     */
    @Nullable
    private static byte[] transformToByteArray(@NonNull final PersistableBundle extras) {
        Objects.requireNonNull(extras);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            new PersistableBundle(extras).writeToStream(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Convert byte[] into Bundle.
     */
    @Nullable
    private Bundle transformToBundle(@Nullable final byte[] extras) {
        if (extras == null) {
            return null;
        }
        Objects.requireNonNull(extras);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(extras)) {
            final Bundle ret = new Bundle();
            ret.putAll(PersistableBundle.readFromStream(bais));
            return ret;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Convert byte[] into PersistableBundle.
     */
    @Nullable
    private PersistableBundle transformToPersistableBundle(@Nullable final byte[] extras) {
        if (extras == null) {
            return null;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(extras)) {
            return PersistableBundle.readFromStream(bais);
        } catch (IOException e) {
            return null;
        }
    }

    private static String[] flattenFlags(@ShortcutInfo.ShortcutFlags final int flags) {
        final List<String> flattenedFlags = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            final int mask = 1 << i;
            final String value = flagToString(flags, mask);
            if (value != null) {
                flattenedFlags.add(value);
            }
        }
        return flattenedFlags.toArray(new String[0]);
    }

    @Nullable
    private static String flagToString(
            @ShortcutInfo.ShortcutFlags final int flags, final int mask) {
        switch (mask) {
            case ShortcutInfo.FLAG_DYNAMIC:
                return (flags & mask) != 0 ? IS_DYNAMIC : NOT_DYNAMIC;
            case ShortcutInfo.FLAG_PINNED:
                return (flags & mask) != 0 ? IS_PINNED : NOT_PINNED;
            case ShortcutInfo.FLAG_HAS_ICON_RES:
                return (flags & mask) != 0 ? HAS_ICON_RES : NO_ICON_RES;
            case ShortcutInfo.FLAG_HAS_ICON_FILE:
                return (flags & mask) != 0 ? HAS_ICON_FILE : NO_ICON_FILE;
            case ShortcutInfo.FLAG_KEY_FIELDS_ONLY:
                return (flags & mask) != 0 ? IS_KEY_FIELD_ONLY : NOT_KEY_FIELD_ONLY;
            case ShortcutInfo.FLAG_MANIFEST:
                return (flags & mask) != 0 ? IS_MANIFEST : NOT_MANIFEST;
            case ShortcutInfo.FLAG_DISABLED:
                return (flags & mask) != 0 ? IS_DISABLED : NOT_DISABLED;
            case ShortcutInfo.FLAG_STRINGS_RESOLVED:
                return (flags & mask) != 0 ? ARE_STRINGS_RESOLVED : NOT_STRINGS_RESOLVED;
            case ShortcutInfo.FLAG_IMMUTABLE:
                return (flags & mask) != 0 ? IS_IMMUTABLE : NOT_IMMUTABLE;
            case ShortcutInfo.FLAG_ADAPTIVE_BITMAP:
                return (flags & mask) != 0 ? HAS_ADAPTIVE_BITMAP : NO_ADAPTIVE_BITMAP;
            case ShortcutInfo.FLAG_RETURNED_BY_SERVICE:
                return (flags & mask) != 0 ? IS_RETURNED_BY_SERVICE : NOT_RETURNED_BY_SERVICE;
            case ShortcutInfo.FLAG_ICON_FILE_PENDING_SAVE:
                return (flags & mask) != 0 ? HAS_ICON_FILE_PENDING_SAVE : NO_ICON_FILE_PENDING_SAVE;
            case ShortcutInfo.FLAG_SHADOW:
                return (flags & mask) != 0 ? IS_SHADOW : NOT_SHADOW;
            case ShortcutInfo.FLAG_LONG_LIVED:
                return (flags & mask) != 0 ? IS_LONG_LIVED : NOT_LONG_LIVED;
            case ShortcutInfo.FLAG_HAS_ICON_URI:
                return (flags & mask) != 0 ? HAS_ICON_URI : NO_ICON_URI;
            case ShortcutInfo.FLAG_CACHED_NOTIFICATIONS:
                return (flags & mask) != 0 ? IS_CACHED_NOTIFICATION : NOT_CACHED_NOTIFICATION;
            case ShortcutInfo.FLAG_CACHED_BUBBLES:
                return (flags & mask) != 0 ? IS_CACHED_BUBBLE : NOT_CACHED_BUBBLE;
            case ShortcutInfo.FLAG_CACHED_PEOPLE_TILE:
                return (flags & mask) != 0 ? IS_CACHED_PEOPLE_TITLE : NOT_CACHED_PEOPLE_TITLE;
            default:
                return null;
        }
    }

    private static int parseFlags(@Nullable final String[] flags) {
        if (flags == null) {
            return 0;
        }
        int ret = 0;
        for (int i = 0; i < flags.length; i++) {
            ret = ret | parseFlag(flags[i]);
        }
        return ret;
    }

    private static int parseFlag(final String value) {
        switch (value) {
            case IS_DYNAMIC:
                return ShortcutInfo.FLAG_DYNAMIC;
            case IS_PINNED:
                return ShortcutInfo.FLAG_PINNED;
            case HAS_ICON_RES:
                return ShortcutInfo.FLAG_HAS_ICON_RES;
            case HAS_ICON_FILE:
                return ShortcutInfo.FLAG_HAS_ICON_FILE;
            case IS_KEY_FIELD_ONLY:
                return ShortcutInfo.FLAG_KEY_FIELDS_ONLY;
            case IS_MANIFEST:
                return ShortcutInfo.FLAG_MANIFEST;
            case IS_DISABLED:
                return ShortcutInfo.FLAG_DISABLED;
            case ARE_STRINGS_RESOLVED:
                return ShortcutInfo.FLAG_STRINGS_RESOLVED;
            case IS_IMMUTABLE:
                return ShortcutInfo.FLAG_IMMUTABLE;
            case HAS_ADAPTIVE_BITMAP:
                return ShortcutInfo.FLAG_ADAPTIVE_BITMAP;
            case IS_RETURNED_BY_SERVICE:
                return ShortcutInfo.FLAG_RETURNED_BY_SERVICE;
            case HAS_ICON_FILE_PENDING_SAVE:
                return ShortcutInfo.FLAG_ICON_FILE_PENDING_SAVE;
            case IS_SHADOW:
                return ShortcutInfo.FLAG_SHADOW;
            case IS_LONG_LIVED:
                return ShortcutInfo.FLAG_LONG_LIVED;
            case HAS_ICON_URI:
                return ShortcutInfo.FLAG_HAS_ICON_URI;
            case IS_CACHED_NOTIFICATION:
                return ShortcutInfo.FLAG_CACHED_NOTIFICATIONS;
            case IS_CACHED_BUBBLE:
                return ShortcutInfo.FLAG_CACHED_BUBBLES;
            case IS_CACHED_PEOPLE_TITLE:
                return ShortcutInfo.FLAG_CACHED_PEOPLE_TILE;
            default:
                return 0;
        }
    }

    @NonNull
    private static Person[] parsePerson(@Nullable final GenericDocument[] persons) {
        if (persons == null) return new Person[0];
        final Person[] ret = new Person[persons.length];
        for (int i = 0; i < persons.length; i++) {
            final GenericDocument document = persons[i];
            if (document == null) continue;
            final AppSearchPerson person = new AppSearchPerson(document);
            ret[i] = person.toPerson();
        }
        return ret;
    }
}
