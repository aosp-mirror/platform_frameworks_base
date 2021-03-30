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

            ).addProperty(new AppSearchSchema.Int64PropertyConfig.Builder(KEY_SHORT_LABEL_RES_ID)
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

            ).addProperty(new AppSearchSchema.Int64PropertyConfig.Builder(KEY_LONG_LABEL_RES_ID)
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

            ).addProperty(new AppSearchSchema.Int64PropertyConfig.Builder(
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

            ).addProperty(new AppSearchSchema.DocumentPropertyConfig.Builder(KEY_PERSON)
                    .setSchemaType(AppSearchPerson.SCHEMA_TYPE)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_LOCUS_ID)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                    .build()

            ).addProperty(new AppSearchSchema.Int64PropertyConfig.Builder(KEY_RANK)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build()

            ).addProperty(new AppSearchSchema.Int64PropertyConfig.Builder(KEY_IMPLICIT_RANK)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build()

            ).addProperty(new AppSearchSchema.BytesPropertyConfig.Builder(KEY_EXTRAS)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .build()

            ).addProperty(new AppSearchSchema.Int64PropertyConfig.Builder(KEY_FLAGS)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .build()

            ).addProperty(new AppSearchSchema.Int64PropertyConfig.Builder(KEY_ICON_RES_ID)
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

            ).addProperty(new AppSearchSchema.Int64PropertyConfig.Builder(KEY_DISABLED_REASON)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                    .build()

            ).build();

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
        final int rank = (int) getPropertyLong(KEY_RANK);
        final int implicitRank = (int) getPropertyLong(KEY_IMPLICIT_RANK);
        final byte[] extrasByte = getPropertyBytes(KEY_EXTRAS);
        final PersistableBundle extras = transformToPersistableBundle(extrasByte);
        final int flags = parseFlags(getPropertyLongArray(KEY_FLAGS));
        final int iconResId = (int) getPropertyLong(KEY_ICON_RES_ID);
        final String iconResName = getPropertyString(KEY_ICON_RES_NAME);
        final String iconUri = getPropertyString(KEY_ICON_URI);
        final String bitmapPath = getPropertyString(KEY_BITMAP_PATH);
        final int disabledReason = (int) getPropertyLong(KEY_DISABLED_REASON);
        final ShortcutInfo si = new ShortcutInfo(
                userId, getUri(), packageName, activity, icon, shortLabel, shortLabelResId,
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
        public Builder setShortLabelResId(@Nullable final int shortLabelResId) {
            setPropertyLong(KEY_SHORT_LABEL_RES_ID, shortLabelResId);
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
        public Builder setLongLabelResId(@Nullable final int longLabelResId) {
            setPropertyLong(KEY_LONG_LABEL_RES_ID, longLabelResId);
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
        public Builder setDisabledMessageResId(@Nullable final int disabledMessageResId) {
            setPropertyLong(KEY_DISABLED_MESSAGE_RES_ID, disabledMessageResId);
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
            setPropertyLong(KEY_RANK, rank);
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
            setPropertyLong(KEY_FLAGS, flattenFlags(flags));
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
            setPropertyLong(KEY_DISABLED_REASON, disabledReason);
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        @Override
        public AppSearchShortcutInfo build() {
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

    private static long[] flattenFlags(@ShortcutInfo.ShortcutFlags final int flags) {
        final List<Integer> flattenedFlags = new ArrayList<>();
        flattenedFlags.add(0);
        for (int i = 0; i < 31; i++) {
            final int mask = 1 << i;
            if ((flags & mask) != 0) {
                flattenedFlags.add(mask);
            }
        }
        return flattenedFlags.stream().mapToLong(i -> i).toArray();
    }

    private static int parseFlags(final long[] flags) {
        return (int) Arrays.stream(flags).reduce((p, v) -> p | v).getAsLong();
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
