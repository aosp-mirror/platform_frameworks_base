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
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.os.UserManager;
import android.provider.Browser;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.view.textclassifier.logging.DefaultLogger;
import android.view.textclassifier.logging.GenerateLinksLogger;
import android.view.textclassifier.logging.Logger;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default implementation of the {@link TextClassifier} interface.
 *
 * <p>This class uses machine learning to recognize entities in text.
 * Unless otherwise stated, methods of this class are blocking operations and should most
 * likely not be called on the UI thread.
 *
 * @hide
 */
public final class TextClassifierImpl implements TextClassifier {

    private static final String LOG_TAG = DEFAULT_LOG_TAG;
    private static final String MODEL_DIR = "/etc/textclassifier/";
    private static final String MODEL_FILE_REGEX = "textclassifier\\.(.*)\\.model";
    private static final String UPDATED_MODEL_FILE_PATH =
            "/data/misc/textclassifier/textclassifier.model";

    private final Context mContext;
    private final TextClassifier mFallback;

    private final GenerateLinksLogger mGenerateLinksLogger;

    private final Object mLock = new Object();
    @GuardedBy("mLock") // Do not access outside this lock.
    private List<ModelFile> mAllModelFiles;
    @GuardedBy("mLock") // Do not access outside this lock.
    private ModelFile mModel;
    @GuardedBy("mLock") // Do not access outside this lock.
    private TextClassifierImplNative mNative;

    private final Object mLoggerLock = new Object();
    @GuardedBy("mLoggerLock") // Do not access outside this lock.
    private WeakReference<Logger.Config> mLoggerConfig = new WeakReference<>(null);
    @GuardedBy("mLoggerLock") // Do not access outside this lock.
    private Logger mLogger;  // Should never be null if mLoggerConfig.get() is not null.

    private final TextClassificationConstants mSettings;

    public TextClassifierImpl(Context context, TextClassificationConstants settings) {
        mContext = Preconditions.checkNotNull(context);
        mFallback = TextClassifier.NO_OP;
        mSettings = Preconditions.checkNotNull(settings);
        mGenerateLinksLogger = new GenerateLinksLogger(mSettings.getGenerateLinksLogSampleRate());
    }

    /** @inheritDoc */
    @Override
    public TextSelection suggestSelection(
            @NonNull CharSequence text, int selectionStartIndex, int selectionEndIndex,
            @Nullable TextSelection.Options options) {
        Utils.validate(text, selectionStartIndex, selectionEndIndex, false /* allowInMainThread */);
        try {
            final int rangeLength = selectionEndIndex - selectionStartIndex;
            if (text.length() > 0
                    && rangeLength <= mSettings.getSuggestSelectionMaxRangeLength()) {
                final LocaleList locales = (options == null) ? null : options.getDefaultLocales();
                final String localesString = concatenateLocales(locales);
                final Calendar refTime = Calendar.getInstance();
                final boolean darkLaunchAllowed = options != null && options.isDarkLaunchAllowed();
                final TextClassifierImplNative nativeImpl = getNative(locales);
                final String string = text.toString();
                final int start;
                final int end;
                if (mSettings.isModelDarkLaunchEnabled() && !darkLaunchAllowed) {
                    start = selectionStartIndex;
                    end = selectionEndIndex;
                } else {
                    final int[] startEnd = nativeImpl.suggestSelection(
                            string, selectionStartIndex, selectionEndIndex,
                            new TextClassifierImplNative.SelectionOptions(localesString));
                    start = startEnd[0];
                    end = startEnd[1];
                }
                if (start < end
                        && start >= 0 && end <= string.length()
                        && start <= selectionStartIndex && end >= selectionEndIndex) {
                    final TextSelection.Builder tsBuilder = new TextSelection.Builder(start, end);
                    final TextClassifierImplNative.ClassificationResult[] results =
                            nativeImpl.classifyText(
                                    string, start, end,
                                    new TextClassifierImplNative.ClassificationOptions(
                                            refTime.getTimeInMillis(),
                                            refTime.getTimeZone().getID(),
                                            localesString));
                    final int size = results.length;
                    for (int i = 0; i < size; i++) {
                        tsBuilder.setEntityType(results[i].getCollection(), results[i].getScore());
                    }
                    return tsBuilder
                            .setSignature(
                                    getSignature(string, selectionStartIndex, selectionEndIndex))
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
        return mFallback.suggestSelection(
                text, selectionStartIndex, selectionEndIndex, options);
    }

    /** @inheritDoc */
    @Override
    public TextClassification classifyText(
            @NonNull CharSequence text, int startIndex, int endIndex,
            @Nullable TextClassification.Options options) {
        Utils.validate(text, startIndex, endIndex, false /* allowInMainThread */);
        try {
            final int rangeLength = endIndex - startIndex;
            if (text.length() > 0 && rangeLength <= mSettings.getClassifyTextMaxRangeLength()) {
                final String string = text.toString();
                final LocaleList locales = (options == null) ? null : options.getDefaultLocales();
                final String localesString = concatenateLocales(locales);
                final Calendar refTime = (options != null && options.getReferenceTime() != null)
                        ? options.getReferenceTime() : Calendar.getInstance();

                final TextClassifierImplNative.ClassificationResult[] results =
                        getNative(locales)
                                .classifyText(string, startIndex, endIndex,
                                        new TextClassifierImplNative.ClassificationOptions(
                                                refTime.getTimeInMillis(),
                                                refTime.getTimeZone().getID(),
                                                localesString));
                if (results.length > 0) {
                    return createClassificationResult(
                            results, string, startIndex, endIndex, refTime);
                }
            }
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error getting text classification info.", t);
        }
        // Getting here means something went wrong, return a NO_OP result.
        return mFallback.classifyText(text, startIndex, endIndex, options);
    }

    /** @inheritDoc */
    @Override
    public TextLinks generateLinks(
            @NonNull CharSequence text, @Nullable TextLinks.Options options) {
        Utils.validate(text, getMaxGenerateLinksTextLength(), false /* allowInMainThread */);
        final String textString = text.toString();
        final TextLinks.Builder builder = new TextLinks.Builder(textString);

        if (!mSettings.isSmartLinkifyEnabled()) {
            return builder.build();
        }

        try {
            final long startTimeMs = System.currentTimeMillis();
            final LocaleList defaultLocales = options != null ? options.getDefaultLocales() : null;
            final Calendar refTime = Calendar.getInstance();
            final Collection<String> entitiesToIdentify =
                    options != null && options.getEntityConfig() != null
                            ? options.getEntityConfig().resolveEntityListModifications(
                                    getEntitiesForHints(options.getEntityConfig().getHints()))
                            : mSettings.getEntityListDefault();
            final TextClassifierImplNative nativeImpl =
                    getNative(defaultLocales);
            final TextClassifierImplNative.AnnotatedSpan[] annotations =
                    nativeImpl.annotate(
                        textString,
                        new TextClassifierImplNative.AnnotationOptions(
                                refTime.getTimeInMillis(),
                                refTime.getTimeZone().getID(),
                                concatenateLocales(defaultLocales)));
            for (TextClassifierImplNative.AnnotatedSpan span : annotations) {
                final TextClassifierImplNative.ClassificationResult[] results =
                        span.getClassification();
                if (results.length == 0
                        || !entitiesToIdentify.contains(results[0].getCollection())) {
                    continue;
                }
                final Map<String, Float> entityScores = new HashMap<>();
                for (int i = 0; i < results.length; i++) {
                    entityScores.put(results[i].getCollection(), results[i].getScore());
                }
                builder.addLink(span.getStartIndex(), span.getEndIndex(), entityScores);
            }
            final TextLinks links = builder.build();
            final long endTimeMs = System.currentTimeMillis();
            final String callingPackageName =
                    options == null || options.getCallingPackageName() == null
                            ? mContext.getPackageName()  // local (in process) TC.
                            : options.getCallingPackageName();
            mGenerateLinksLogger.logGenerateLinks(
                    text, links, callingPackageName, endTimeMs - startTimeMs);
            return links;
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error getting links info.", t);
        }
        return mFallback.generateLinks(text, options);
    }

    /** @inheritDoc */
    @Override
    public int getMaxGenerateLinksTextLength() {
        return mSettings.getGenerateLinksMaxTextLength();
    }

    private Collection<String> getEntitiesForHints(Collection<String> hints) {
        final boolean editable = hints.contains(HINT_TEXT_IS_EDITABLE);
        final boolean notEditable = hints.contains(HINT_TEXT_IS_NOT_EDITABLE);

        // Use the default if there is no hint, or conflicting ones.
        final boolean useDefault = editable == notEditable;
        if (useDefault) {
            return mSettings.getEntityListDefault();
        } else if (editable) {
            return mSettings.getEntityListEditable();
        } else {  // notEditable
            return mSettings.getEntityListNotEditable();
        }
    }

    @Override
    public Logger getLogger(@NonNull Logger.Config config) {
        Preconditions.checkNotNull(config);
        synchronized (mLoggerLock) {
            if (mLoggerConfig.get() == null || !mLoggerConfig.get().equals(config)) {
                mLoggerConfig = new WeakReference<>(config);
                mLogger = new DefaultLogger(config);
            }
            return mLogger;
        }
    }

    private TextClassifierImplNative getNative(LocaleList localeList)
            throws FileNotFoundException {
        synchronized (mLock) {
            localeList = localeList == null ? LocaleList.getEmptyLocaleList() : localeList;
            final ModelFile bestModel = findBestModelLocked(localeList);
            if (bestModel == null) {
                throw new FileNotFoundException("No model for " + localeList.toLanguageTags());
            }
            if (mNative == null || !Objects.equals(mModel, bestModel)) {
                Log.d(DEFAULT_LOG_TAG, "Loading " + bestModel);
                destroyNativeIfExistsLocked();
                final ParcelFileDescriptor fd = ParcelFileDescriptor.open(
                        new File(bestModel.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
                mNative = new TextClassifierImplNative(fd.getFd());
                closeAndLogError(fd);
                mModel = bestModel;
            }
            return mNative;
        }
    }

    private String getSignature(String text, int start, int end) {
        synchronized (mLock) {
            return DefaultLogger.createSignature(text, start, end, mContext, mModel.getVersion(),
                    mModel.getSupportedLocales());
        }
    }

    @GuardedBy("mLock") // Do not call outside this lock.
    private void destroyNativeIfExistsLocked() {
        if (mNative != null) {
            mNative.close();
            mNative = null;
        }
    }

    private static String concatenateLocales(@Nullable LocaleList locales) {
        return (locales == null) ? "" : locales.toLanguageTags();
    }

    /**
     * Finds the most appropriate model to use for the given target locale list.
     *
     * The basic logic is: we ignore all models that don't support any of the target locales. For
     * the remaining candidates, we take the update model unless its version number is lower than
     * the factory version. It's assumed that factory models do not have overlapping locale ranges
     * and conflict resolution between these models hence doesn't matter.
     */
    @GuardedBy("mLock") // Do not call outside this lock.
    @Nullable
    private ModelFile findBestModelLocked(LocaleList localeList) {
        // Specified localeList takes priority over the system default, so it is listed first.
        final String languages = localeList.isEmpty()
                ? LocaleList.getDefault().toLanguageTags()
                : localeList.toLanguageTags() + "," + LocaleList.getDefault().toLanguageTags();
        final List<Locale.LanguageRange> languageRangeList = Locale.LanguageRange.parse(languages);

        ModelFile bestModel = null;
        int bestModelVersion = -1;
        for (ModelFile model : listAllModelsLocked()) {
            if (model.isAnyLanguageSupported(languageRangeList)) {
                if (model.getVersion() >= bestModelVersion) {
                    bestModel = model;
                    bestModelVersion = model.getVersion();
                }
            }
        }
        return bestModel;
    }

    /** Returns a list of all model files available, in order of precedence. */
    @GuardedBy("mLock") // Do not call outside this lock.
    private List<ModelFile> listAllModelsLocked() {
        if (mAllModelFiles == null) {
            final List<ModelFile> allModels = new ArrayList<>();
            // The update model has the highest precedence.
            if (new File(UPDATED_MODEL_FILE_PATH).exists()) {
                final ModelFile updatedModel = ModelFile.fromPath(UPDATED_MODEL_FILE_PATH);
                if (updatedModel != null) {
                    allModels.add(updatedModel);
                }
            }
            // Factory models should never have overlapping locales, so the order doesn't matter.
            final File modelsDir = new File(MODEL_DIR);
            if (modelsDir.exists() && modelsDir.isDirectory()) {
                final File[] modelFiles = modelsDir.listFiles();
                final Pattern modelFilenamePattern = Pattern.compile(MODEL_FILE_REGEX);
                for (File modelFile : modelFiles) {
                    final Matcher matcher = modelFilenamePattern.matcher(modelFile.getName());
                    if (matcher.matches() && modelFile.isFile()) {
                        final ModelFile model = ModelFile.fromPath(modelFile.getAbsolutePath());
                        if (model != null) {
                            allModels.add(model);
                        }
                    }
                }
            }
            mAllModelFiles = allModels;
        }
        return mAllModelFiles;
    }

    private TextClassification createClassificationResult(
            TextClassifierImplNative.ClassificationResult[] classifications,
            String text, int start, int end, @Nullable Calendar referenceTime) {
        final String classifiedText = text.substring(start, end);
        final TextClassification.Builder builder = new TextClassification.Builder()
                .setText(classifiedText);

        final int size = classifications.length;
        TextClassifierImplNative.ClassificationResult highestScoringResult = null;
        float highestScore = Float.MIN_VALUE;
        for (int i = 0; i < size; i++) {
            builder.setEntityType(classifications[i].getCollection(),
                                  classifications[i].getScore());
            if (classifications[i].getScore() > highestScore) {
                highestScoringResult = classifications[i];
                highestScore = classifications[i].getScore();
            }
        }

        addActions(builder, IntentFactory.create(
                mContext, referenceTime, highestScoringResult, classifiedText));

        return builder.setSignature(getSignature(text, start, end)).build();
    }

    /** Extends the classification with the intents that can be resolved. */
    private void addActions(
            TextClassification.Builder builder, List<Intent> intents) {
        final PackageManager pm = mContext.getPackageManager();
        final int size = intents.size();
        for (int i = 0; i < size; i++) {
            final Intent intent = intents.get(i);
            final ResolveInfo resolveInfo;
            if (intent != null) {
                resolveInfo = pm.resolveActivity(intent, 0);
            } else {
                resolveInfo = null;
            }
            if (resolveInfo != null && resolveInfo.activityInfo != null) {
                final String packageName = resolveInfo.activityInfo.packageName;
                final String label = IntentFactory.getLabel(mContext, intent);
                Drawable icon;
                if ("android".equals(packageName)) {
                    // Requires the chooser to find an activity to handle the intent.
                    icon = null;
                } else {
                    // A default activity will handle the intent.
                    intent.setComponent(
                            new ComponentName(packageName, resolveInfo.activityInfo.name));
                    icon = resolveInfo.activityInfo.loadIcon(pm);
                    if (icon == null) {
                        icon = resolveInfo.loadIcon(pm);
                    }
                }
                if (i == 0) {
                    builder.setPrimaryAction(intent, label, icon);
                } else {
                    builder.addSecondaryAction(intent, label, icon);
                }
            }
        }
    }

    /**
     * Closes the ParcelFileDescriptor and logs any errors that occur.
     */
    private static void closeAndLogError(ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error closing file.", e);
        }
    }

    /**
     * Describes TextClassifier model files on disk.
     */
    private static final class ModelFile {

        private final String mPath;
        private final String mName;
        private final int mVersion;
        private final List<Locale> mSupportedLocales;

        /** Returns null if the path did not point to a compatible model. */
        static @Nullable ModelFile fromPath(String path) {
            final File file = new File(path);
            try {
                final ParcelFileDescriptor modelFd = ParcelFileDescriptor.open(
                        file, ParcelFileDescriptor.MODE_READ_ONLY);
                final int version = TextClassifierImplNative.getVersion(modelFd.getFd());
                final String supportedLocalesStr =
                        TextClassifierImplNative.getLocales(modelFd.getFd());
                if (supportedLocalesStr.isEmpty()) {
                    Log.d(DEFAULT_LOG_TAG, "Ignoring " + file.getAbsolutePath());
                    return null;
                }
                final List<Locale> supportedLocales = new ArrayList<>();
                for (String langTag : supportedLocalesStr.split(",")) {
                    supportedLocales.add(Locale.forLanguageTag(langTag));
                }
                closeAndLogError(modelFd);
                return new ModelFile(path, file.getName(), version, supportedLocales);
            } catch (FileNotFoundException e) {
                Log.e(DEFAULT_LOG_TAG, "Failed to peek " + file.getAbsolutePath(), e);
                return null;
            }
        }

        /** The absolute path to the model file. */
        String getPath() {
            return mPath;
        }

        /** A name to use for signature generation. Effectively the name of the model file. */
        String getName() {
            return mName;
        }

        /** Returns the version tag in the model's metadata. */
        int getVersion() {
            return mVersion;
        }

        /** Returns whether the language supports any language in the given ranges. */
        boolean isAnyLanguageSupported(List<Locale.LanguageRange> languageRanges) {
            return Locale.lookup(languageRanges, mSupportedLocales) != null;
        }

        /** All locales supported by the model. */
        List<Locale> getSupportedLocales() {
            return Collections.unmodifiableList(mSupportedLocales);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            } else if (other == null || !ModelFile.class.isAssignableFrom(other.getClass())) {
                return false;
            } else {
                final ModelFile otherModel = (ModelFile) other;
                return mPath.equals(otherModel.mPath);
            }
        }

        @Override
        public String toString() {
            final StringJoiner localesJoiner = new StringJoiner(",");
            for (Locale locale : mSupportedLocales) {
                localesJoiner.add(locale.toLanguageTag());
            }
            return String.format(Locale.US, "ModelFile { path=%s name=%s version=%d locales=%s }",
                    mPath, mName, mVersion, localesJoiner.toString());
        }

        private ModelFile(String path, String name, int version, List<Locale> supportedLocales) {
            mPath = path;
            mName = name;
            mVersion = version;
            mSupportedLocales = supportedLocales;
        }
    }

    /**
     * Creates intents based on the classification type.
     */
    static final class IntentFactory {

        private static final long MIN_EVENT_FUTURE_MILLIS = TimeUnit.MINUTES.toMillis(5);
        private static final long DEFAULT_EVENT_DURATION = TimeUnit.HOURS.toMillis(1);

        private IntentFactory() {}

        @NonNull
        public static List<Intent> create(
                Context context,
                @Nullable Calendar referenceTime,
                TextClassifierImplNative.ClassificationResult classification,
                String text) {
            final String type = classification.getCollection().trim().toLowerCase(Locale.ENGLISH);
            text = text.trim();
            switch (type) {
                case TextClassifier.TYPE_EMAIL:
                    return createForEmail(text);
                case TextClassifier.TYPE_PHONE:
                    return createForPhone(context, text);
                case TextClassifier.TYPE_ADDRESS:
                    return createForAddress(text);
                case TextClassifier.TYPE_URL:
                    return createForUrl(context, text);
                case TextClassifier.TYPE_DATE:
                case TextClassifier.TYPE_DATE_TIME:
                    if (classification.getDatetimeResult() != null) {
                        Calendar eventTime = Calendar.getInstance();
                        eventTime.setTimeInMillis(
                                classification.getDatetimeResult().getTimeMsUtc());
                        return createForDatetime(type, referenceTime, eventTime);
                    } else {
                        return new ArrayList<>();
                    }
                case TextClassifier.TYPE_FLIGHT_NUMBER:
                    return createForFlight(text);
                default:
                    return new ArrayList<>();
            }
        }

        @NonNull
        private static List<Intent> createForEmail(String text) {
            return Arrays.asList(
                    new Intent(Intent.ACTION_SENDTO)
                            .setData(Uri.parse(String.format("mailto:%s", text))),
                    new Intent(Intent.ACTION_INSERT_OR_EDIT)
                            .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                            .putExtra(ContactsContract.Intents.Insert.EMAIL, text));
        }

        @NonNull
        private static List<Intent> createForPhone(Context context, String text) {
            final List<Intent> intents = new ArrayList<>();
            final UserManager userManager = context.getSystemService(UserManager.class);
            final Bundle userRestrictions = userManager != null
                    ? userManager.getUserRestrictions() : new Bundle();
            if (!userRestrictions.getBoolean(UserManager.DISALLOW_OUTGOING_CALLS, false)) {
                intents.add(new Intent(Intent.ACTION_DIAL)
                        .setData(Uri.parse(String.format("tel:%s", text))));
            }
            intents.add(new Intent(Intent.ACTION_INSERT_OR_EDIT)
                    .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, text));
            if (!userRestrictions.getBoolean(UserManager.DISALLOW_SMS, false)) {
                intents.add(new Intent(Intent.ACTION_SENDTO)
                        .setData(Uri.parse(String.format("smsto:%s", text))));
            }
            return intents;
        }

        @NonNull
        private static List<Intent> createForAddress(String text) {
            final List<Intent> intents = new ArrayList<>();
            try {
                final String encText = URLEncoder.encode(text, "UTF-8");
                intents.add(new Intent(Intent.ACTION_VIEW)
                        .setData(Uri.parse(String.format("geo:0,0?q=%s", encText))));
            } catch (UnsupportedEncodingException e) {
                Log.e(LOG_TAG, "Could not encode address", e);
            }
            return intents;
        }

        @NonNull
        private static List<Intent> createForUrl(Context context, String text) {
            final String httpPrefix = "http://";
            final String httpsPrefix = "https://";
            if (text.toLowerCase().startsWith(httpPrefix)) {
                text = httpPrefix + text.substring(httpPrefix.length());
            } else if (text.toLowerCase().startsWith(httpsPrefix)) {
                text = httpsPrefix + text.substring(httpsPrefix.length());
            } else {
                text = httpPrefix + text;
            }
            return Arrays.asList(new Intent(Intent.ACTION_VIEW, Uri.parse(text))
                    .putExtra(Browser.EXTRA_APPLICATION_ID, context.getPackageName()));
        }

        @NonNull
        private static List<Intent> createForDatetime(
                String type, @Nullable Calendar referenceTime, Calendar eventTime) {
            if (referenceTime == null) {
                // If no reference time was given, use now.
                referenceTime = Calendar.getInstance();
            }
            List<Intent> intents = new ArrayList<>();
            intents.add(createCalendarViewIntent(eventTime));
            final long millisSinceReference =
                    eventTime.getTimeInMillis() - referenceTime.getTimeInMillis();
            if (millisSinceReference > MIN_EVENT_FUTURE_MILLIS) {
                intents.add(createCalendarCreateEventIntent(eventTime, type));
            }
            return intents;
        }

        @NonNull
        private static List<Intent> createForFlight(String text) {
            return Arrays.asList(new Intent(Intent.ACTION_WEB_SEARCH)
                    .putExtra(SearchManager.QUERY, text));
        }

        @NonNull
        private static Intent createCalendarViewIntent(Calendar eventTime) {
            Uri.Builder builder = CalendarContract.CONTENT_URI.buildUpon();
            builder.appendPath("time");
            ContentUris.appendId(builder, eventTime.getTimeInMillis());
            return new Intent(Intent.ACTION_VIEW).setData(builder.build());
        }

        @NonNull
        private static Intent createCalendarCreateEventIntent(
                Calendar eventTime, @EntityType String type) {
            final boolean isAllDay = TextClassifier.TYPE_DATE.equals(type);
            return new Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, isAllDay)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, eventTime.getTimeInMillis())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                            eventTime.getTimeInMillis() + DEFAULT_EVENT_DURATION);
        }

        @Nullable
        public static String getLabel(Context context, @Nullable Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return null;
            }
            final String authority =
                    intent.getData() == null ? null : intent.getData().getAuthority();
            switch (intent.getAction()) {
                case Intent.ACTION_DIAL:
                    return context.getString(com.android.internal.R.string.dial);
                case Intent.ACTION_SENDTO:
                    if ("mailto".equals(intent.getScheme())) {
                        return context.getString(com.android.internal.R.string.email);
                    } else if ("smsto".equals(intent.getScheme())) {
                        return context.getString(com.android.internal.R.string.sms);
                    } else {
                        return null;
                    }
                case Intent.ACTION_INSERT:
                    if (CalendarContract.AUTHORITY.equals(authority)) {
                        return context.getString(com.android.internal.R.string.add_calendar_event);
                    }
                    return null;
                case Intent.ACTION_INSERT_OR_EDIT:
                    if (ContactsContract.Contacts.CONTENT_ITEM_TYPE.equals(
                            intent.getType())) {
                        return context.getString(com.android.internal.R.string.add_contact);
                    } else {
                        return null;
                    }
                case Intent.ACTION_VIEW:
                    if (CalendarContract.AUTHORITY.equals(authority)) {
                        return context.getString(com.android.internal.R.string.view_calendar);
                    } else if ("geo".equals(intent.getScheme())) {
                        return context.getString(com.android.internal.R.string.map);
                    } else if ("http".equals(intent.getScheme())
                            || "https".equals(intent.getScheme())) {
                        return context.getString(com.android.internal.R.string.browse);
                    } else {
                        return null;
                    }
                case Intent.ACTION_WEB_SEARCH:
                    return context.getString(com.android.internal.R.string.view_flight);
                default:
                    return null;
            }
        }
    }
}
