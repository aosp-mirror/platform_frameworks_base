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
import android.util.ArrayMap;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A {@link GenericDocument} representation of {@link ShortcutInfo} object.
 * @hide
 */
public class AppSearchShortcutInfo extends GenericDocument {

    /** The TTL (time-to-live) of the shortcut, in milli-second. */
    public static final long SHORTCUT_TTL = TimeUnit.DAYS.toMillis(90);

    /** The name of the schema type for {@link ShortcutInfo} documents.*/
    public static final String SCHEMA_TYPE = "Shortcut";

    /** @hide */
    public static final int SCHEMA_VERSION = 3;

    /**
     * Property name of the activity this {@link ShortcutInfo} is associated with.
     * See {@link ShortcutInfo#getActivity()}.
     */
    public static final String KEY_ACTIVITY = "activity";

    /**
     * Property name of the short description of this {@link ShortcutInfo}.
     * See {@link ShortcutInfo#getShortLabel()}.
     */
    public static final String KEY_SHORT_LABEL = "shortLabel";

    /**
     * Property name of the long description of this {@link ShortcutInfo}.
     * See {@link ShortcutInfo#getLongLabel()}.
     */
    public static final String KEY_LONG_LABEL = "longLabel";

    /**
     * @hide
     */
    public static final String KEY_DISABLED_MESSAGE = "disabledMessage";

    /**
     * Property name of the categories this {@link ShortcutInfo} is associated with.
     * See {@link ShortcutInfo#getCategories()}.
     */
    public static final String KEY_CATEGORIES = "categories";

    /**
     * Property name of the intents this {@link ShortcutInfo} is associated with.
     * See {@link ShortcutInfo#getIntents()}.
     */
    public static final String KEY_INTENTS = "intents";

    /**
     * @hide
     */
    public static final String KEY_INTENT_PERSISTABLE_EXTRAS = "intentPersistableExtras";

    /**
     * Property name of {@link Person} objects this {@link ShortcutInfo} is associated with.
     * See {@link ShortcutInfo#getPersons()}.
     */
    public static final String KEY_PERSON = "person";

    /**
     * Property name of {@link LocusId} this {@link ShortcutInfo} is associated with.
     * See {@link ShortcutInfo#getLocusId()}.
     */
    public static final String KEY_LOCUS_ID = "locusId";

    /**
     * @hide
     */
    public static final String KEY_EXTRAS = "extras";

    /**
     * Property name of the states this {@link ShortcutInfo} is currently in.
     * Possible values are one or more of the following:
     *     {@link #IS_DYNAMIC}, {@link #NOT_DYNAMIC}, {@link #IS_MANIFEST}, {@link #NOT_MANIFEST},
     *     {@link #IS_DISABLED}, {@link #NOT_DISABLED}, {@link #IS_IMMUTABLE},
     *     {@link #NOT_IMMUTABLE}
     *
     */
    public static final String KEY_FLAGS = "flags";

    /**
     * @hide
     */
    public static final String KEY_ICON_RES_ID = "iconResId";

    /**
     * @hide
     */
    public static final String KEY_ICON_RES_NAME = "iconResName";

    /**
     * @hide
     */
    public static final String KEY_ICON_URI = "iconUri";

    /**
     * @hide
     */
    public static final String KEY_DISABLED_REASON = "disabledReason";

    /**
     * Property name of capability this {@link ShortcutInfo} is associated with.
     * See {@link ShortcutInfo#hasCapability(String)}.
     */
    public static final String KEY_CAPABILITY = "capability";

    /**
     * Property name of capability binding this {@link ShortcutInfo} is associated with.
     * See {@link ShortcutInfo#getCapabilityParameters(String, String)}.
     */
    public static final String KEY_CAPABILITY_BINDINGS = "capabilityBindings";

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

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_LONG_LABEL)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_DISABLED_MESSAGE)
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
                    KEY_PERSON, AppSearchShortcutPerson.SCHEMA_TYPE)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_LOCUS_ID)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
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

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_DISABLED_REASON)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REQUIRED)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_CAPABILITY)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
                    .build()

            ).addProperty(new AppSearchSchema.StringPropertyConfig.Builder(KEY_CAPABILITY_BINDINGS)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .setTokenizerType(AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN)
                    .setIndexingType(AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES)
                    .build()

            ).build();

    /**
     * The string representation of every flag within {@link ShortcutInfo}. Note that its value
     * needs to be camelCase since AppSearch's tokenizer will break the word when it sees
     * underscore.
     */

    /**
     * Indicates the {@link ShortcutInfo} is dynamic shortcut.
     * See {@link #KEY_FLAGS}
     * See {@link ShortcutInfo#isDynamic()}.
     */
    public static final String IS_DYNAMIC = "Dyn";

    /**
     * Indicates the {@link ShortcutInfo} is not a dynamic shortcut.
     * See {@link #KEY_FLAGS}
     * See {@link ShortcutInfo#isDynamic()}.
     */
    public static final String NOT_DYNAMIC = "nDyn";

    /**
     * Indicates the {@link ShortcutInfo} is manifest shortcut.
     * See {@link #KEY_FLAGS}
     * See {@link ShortcutInfo#isDeclaredInManifest()}.
     */
    public static final String IS_MANIFEST = "Man";

    /**
     * Indicates the {@link ShortcutInfo} is manifest shortcut.
     * See {@link #KEY_FLAGS}
     * See {@link ShortcutInfo#isDeclaredInManifest()}.
     */
    public static final String NOT_MANIFEST = "nMan";

    /**
     * Indicates the {@link ShortcutInfo} is disabled.
     * See {@link #KEY_FLAGS}
     * See {@link ShortcutInfo#isEnabled()}.
     */
    public static final String IS_DISABLED = "Dis";

    /**
     * Indicates the {@link ShortcutInfo} is enabled.
     * See {@link #KEY_FLAGS}
     * See {@link ShortcutInfo#isEnabled()}.
     */
    public static final String NOT_DISABLED = "nDis";

    /**
     * Indicates the {@link ShortcutInfo} was originally from manifest, but currently disabled.
     * See {@link #KEY_FLAGS}
     * See {@link ShortcutInfo#isOriginallyFromManifest()}.
     */
    public static final String IS_IMMUTABLE = "Im";

    /**
     * Indicates the {@link ShortcutInfo} was not originally from manifest.
     * See {@link #KEY_FLAGS}
     * See {@link ShortcutInfo#isOriginallyFromManifest()}.
     */
    public static final String NOT_IMMUTABLE = "nIm";

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
                .setLongLabel(shortcutInfo.getLongLabel())
                .setDisabledMessage(shortcutInfo.getDisabledMessage())
                .setCategories(shortcutInfo.getCategories())
                .setIntents(shortcutInfo.getIntents())
                .setExtras(shortcutInfo.getExtras())
                .setCreationTimestampMillis(shortcutInfo.getLastChangedTimestamp())
                .setFlags(shortcutInfo.getFlags())
                .setIconResId(shortcutInfo.getIconResourceId())
                .setIconResName(shortcutInfo.getIconResName())
                .setIconUri(shortcutInfo.getIconUri())
                .setDisabledReason(shortcutInfo.getDisabledReason())
                .setPersons(shortcutInfo.getPersons())
                .setLocusId(shortcutInfo.getLocusId())
                .setCapabilityBindings(shortcutInfo.getCapabilityBindingsInternal())
                .setTtlMillis(SHORTCUT_TTL)
                .build();
    }

    /**
     * Converts this {@link GenericDocument} object into {@link ShortcutInfo} to read the
     * information.
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
        final String longLabel = getPropertyString(KEY_LONG_LABEL);
        final String disabledMessage = getPropertyString(KEY_DISABLED_MESSAGE);
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
        final byte[] extrasByte = getPropertyBytes(KEY_EXTRAS);
        final PersistableBundle extras = transformToPersistableBundle(extrasByte);
        final int flags = parseFlags(getPropertyStringArray(KEY_FLAGS));
        final int iconResId = (int) getPropertyLong(KEY_ICON_RES_ID);
        final String iconResName = getPropertyString(KEY_ICON_RES_NAME);
        final String iconUri = getPropertyString(KEY_ICON_URI);
        final String disabledReasonString = getPropertyString(KEY_DISABLED_REASON);
        final int disabledReason = !TextUtils.isEmpty(disabledReasonString)
                ? Integer.parseInt(getPropertyString(KEY_DISABLED_REASON))
                : ShortcutInfo.DISABLED_REASON_NOT_DISABLED;
        final Map<String, Map<String, List<String>>> capabilityBindings =
                parseCapabilityBindings(getPropertyStringArray(KEY_CAPABILITY_BINDINGS));
        return new ShortcutInfo(
                userId, getId(), packageName, activity, icon, shortLabel, 0,
                null, longLabel, 0, null, disabledMessage,
                0, null, categoriesSet, intents,
                ShortcutInfo.RANK_NOT_SET, extras, getCreationTimestampMillis(), flags, iconResId,
                iconResName, null, iconUri, disabledReason, persons, locusId,
                null, capabilityBindings);
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
                final AppSearchShortcutPerson personEntity =
                        AppSearchShortcutPerson.instance(person);
                documents[i] = personEntity;
            }
            setPropertyDocument(KEY_PERSON, documents);
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
        public Builder setCapabilityBindings(
                @Nullable final Map<String, Map<String, List<String>>> bindings) {
            if (bindings != null && !bindings.isEmpty()) {
                final Set<String> capabilityNames = bindings.keySet();
                final Set<String> capabilityBindings = new ArraySet<>(1);
                for (String capabilityName: capabilityNames) {
                    final Map<String, List<String>> params =
                            bindings.get(capabilityName);
                    for (String paramName: params.keySet()) {
                        params.get(paramName).stream()
                                .map(v -> capabilityName + "/" + paramName + "/" + v)
                                .forEach(capabilityBindings::add);
                    }
                }
                setPropertyString(KEY_CAPABILITY, capabilityNames.toArray(new String[0]));
                setPropertyString(KEY_CAPABILITY_BINDINGS,
                        capabilityBindings.toArray(new String[0]));
            }
            return this;
        }

        /**
         * @hide
         */
        @NonNull
        @Override
        public AppSearchShortcutInfo build() {
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
            case ShortcutInfo.FLAG_MANIFEST:
                return (flags & mask) != 0 ? IS_MANIFEST : NOT_MANIFEST;
            case ShortcutInfo.FLAG_DISABLED:
                return (flags & mask) != 0 ? IS_DISABLED : NOT_DISABLED;
            case ShortcutInfo.FLAG_IMMUTABLE:
                return (flags & mask) != 0 ? IS_IMMUTABLE : NOT_IMMUTABLE;
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
            case IS_MANIFEST:
                return ShortcutInfo.FLAG_MANIFEST;
            case IS_DISABLED:
                return ShortcutInfo.FLAG_DISABLED;
            case IS_IMMUTABLE:
                return ShortcutInfo.FLAG_IMMUTABLE;
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
            final AppSearchShortcutPerson person = new AppSearchShortcutPerson(document);
            ret[i] = person.toPerson();
        }
        return ret;
    }

    @Nullable
    private static Map<String, Map<String, List<String>>> parseCapabilityBindings(
            @Nullable final String[] capabilityBindings) {
        if (capabilityBindings == null || capabilityBindings.length == 0) {
            return null;
        }
        final Map<String, Map<String, List<String>>> ret = new ArrayMap<>(1);
        Arrays.stream(capabilityBindings).forEach(binding -> {
            if (TextUtils.isEmpty(binding)) {
                return;
            }
            final int capabilityStopIndex = binding.indexOf("/");
            if (capabilityStopIndex == -1 || capabilityStopIndex == binding.length() - 1) {
                return;
            }
            final String capabilityName = binding.substring(0, capabilityStopIndex);
            final int paramStopIndex = binding.indexOf("/", capabilityStopIndex + 1);
            if (paramStopIndex == -1 || paramStopIndex == binding.length() - 1) {
                return;
            }
            final String paramName = binding.substring(capabilityStopIndex + 1, paramStopIndex);
            final String paramValue = binding.substring(paramStopIndex + 1);
            if (!ret.containsKey(capabilityName)) {
                ret.put(capabilityName, new ArrayMap<>(1));
            }
            final Map<String, List<String>> params = ret.get(capabilityName);
            if (!params.containsKey(paramName)) {
                params.put(paramName, new ArrayList<>(1));
            }
            params.get(paramName).add(paramValue);
        });
        return ret;
    }
}
