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

import static java.time.temporal.ChronoUnit.MILLIS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.os.UserManager;
import android.provider.Browser;
import android.provider.CalendarContract;
import android.provider.ContactsContract;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
    private SelectionSessionLogger mSessionLogger;

    private final TextClassificationConstants mSettings;

    public TextClassifierImpl(
            Context context, TextClassificationConstants settings, TextClassifier fallback) {
        mContext = Preconditions.checkNotNull(context);
        mFallback = Preconditions.checkNotNull(fallback);
        mSettings = Preconditions.checkNotNull(settings);
        mGenerateLinksLogger = new GenerateLinksLogger(mSettings.getGenerateLinksLogSampleRate());
    }

    public TextClassifierImpl(Context context, TextClassificationConstants settings) {
        this(context, settings, TextClassifier.NO_OP);
    }

    /** @inheritDoc */
    @Override
    @WorkerThread
    public TextSelection suggestSelection(TextSelection.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();
        try {
            final int rangeLength = request.getEndIndex() - request.getStartIndex();
            final String string = request.getText().toString();
            if (string.length() > 0
                    && rangeLength <= mSettings.getSuggestSelectionMaxRangeLength()) {
                final String localesString = concatenateLocales(request.getDefaultLocales());
                final ZonedDateTime refTime = ZonedDateTime.now();
                final TextClassifierImplNative nativeImpl = getNative(request.getDefaultLocales());
                final int start;
                final int end;
                if (mSettings.isModelDarkLaunchEnabled() && !request.isDarkLaunchAllowed()) {
                    start = request.getStartIndex();
                    end = request.getEndIndex();
                } else {
                    final int[] startEnd = nativeImpl.suggestSelection(
                            string, request.getStartIndex(), request.getEndIndex(),
                            new TextClassifierImplNative.SelectionOptions(localesString));
                    start = startEnd[0];
                    end = startEnd[1];
                }
                if (start < end
                        && start >= 0 && end <= string.length()
                        && start <= request.getStartIndex() && end >= request.getEndIndex()) {
                    final TextSelection.Builder tsBuilder = new TextSelection.Builder(start, end);
                    final TextClassifierImplNative.ClassificationResult[] results =
                            nativeImpl.classifyText(
                                    string, start, end,
                                    new TextClassifierImplNative.ClassificationOptions(
                                            refTime.toInstant().toEpochMilli(),
                                            refTime.getZone().getId(),
                                            localesString));
                    final int size = results.length;
                    for (int i = 0; i < size; i++) {
                        tsBuilder.setEntityType(results[i].getCollection(), results[i].getScore());
                    }
                    return tsBuilder.setId(createId(
                            string, request.getStartIndex(), request.getEndIndex()))
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
        return mFallback.suggestSelection(request);
    }

    /** @inheritDoc */
    @Override
    @WorkerThread
    public TextClassification classifyText(TextClassification.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();
        try {
            final int rangeLength = request.getEndIndex() - request.getStartIndex();
            final String string = request.getText().toString();
            if (string.length() > 0 && rangeLength <= mSettings.getClassifyTextMaxRangeLength()) {
                final String localesString = concatenateLocales(request.getDefaultLocales());
                final ZonedDateTime refTime = request.getReferenceTime() != null
                        ? request.getReferenceTime() : ZonedDateTime.now();
                final TextClassifierImplNative.ClassificationResult[] results =
                        getNative(request.getDefaultLocales())
                                .classifyText(
                                        string, request.getStartIndex(), request.getEndIndex(),
                                        new TextClassifierImplNative.ClassificationOptions(
                                                refTime.toInstant().toEpochMilli(),
                                                refTime.getZone().getId(),
                                                localesString));
                if (results.length > 0) {
                    return createClassificationResult(
                            results, string,
                            request.getStartIndex(), request.getEndIndex(), refTime.toInstant());
                }
            }
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error getting text classification info.", t);
        }
        // Getting here means something went wrong, return a NO_OP result.
        return mFallback.classifyText(request);
    }

    /** @inheritDoc */
    @Override
    @WorkerThread
    public TextLinks generateLinks(@NonNull TextLinks.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkTextLength(request.getText(), getMaxGenerateLinksTextLength());
        Utils.checkMainThread();

        if (!mSettings.isSmartLinkifyEnabled() && request.isLegacyFallback()) {
            return Utils.generateLegacyLinks(request);
        }

        final String textString = request.getText().toString();
        final TextLinks.Builder builder = new TextLinks.Builder(textString);

        try {
            final long startTimeMs = System.currentTimeMillis();
            final ZonedDateTime refTime = ZonedDateTime.now();
            final Collection<String> entitiesToIdentify = request.getEntityConfig() != null
                    ? request.getEntityConfig().resolveEntityListModifications(
                            getEntitiesForHints(request.getEntityConfig().getHints()))
                    : mSettings.getEntityListDefault();
            final TextClassifierImplNative nativeImpl =
                    getNative(request.getDefaultLocales());
            final TextClassifierImplNative.AnnotatedSpan[] annotations =
                    nativeImpl.annotate(
                        textString,
                        new TextClassifierImplNative.AnnotationOptions(
                                refTime.toInstant().toEpochMilli(),
                                        refTime.getZone().getId(),
                                concatenateLocales(request.getDefaultLocales())));
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
            final String callingPackageName = request.getCallingPackageName() == null
                    ? mContext.getPackageName()  // local (in process) TC.
                    : request.getCallingPackageName();
            mGenerateLinksLogger.logGenerateLinks(
                    request.getText(), links, callingPackageName, endTimeMs - startTimeMs);
            return links;
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error getting links info.", t);
        }
        return mFallback.generateLinks(request);
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
    public void onSelectionEvent(SelectionEvent event) {
        Preconditions.checkNotNull(event);
        synchronized (mLoggerLock) {
            if (mSessionLogger == null) {
                mSessionLogger = new SelectionSessionLogger();
            }
            mSessionLogger.writeEvent(event);
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

    private String createId(String text, int start, int end) {
        synchronized (mLock) {
            return SelectionSessionLogger.createId(text, start, end, mContext, mModel.getVersion(),
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
        for (ModelFile model : listAllModelsLocked()) {
            if (model.isAnyLanguageSupported(languageRangeList)) {
                if (model.isPreferredTo(bestModel)) {
                    bestModel = model;
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
            String text, int start, int end, @Nullable Instant referenceTime) {
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

        boolean isPrimaryAction = true;
        for (LabeledIntent labeledIntent : IntentFactory.create(
                mContext, referenceTime, highestScoringResult, classifiedText)) {
            final RemoteAction action = labeledIntent.asRemoteAction(mContext);
            if (action == null) {
                continue;
            }
            if (isPrimaryAction) {
                // For O backwards compatibility, the first RemoteAction is also written to the
                // legacy API fields.
                builder.setIcon(action.getIcon().loadDrawable(mContext));
                builder.setLabel(action.getTitle().toString());
                builder.setIntent(labeledIntent.getIntent());
                builder.setOnClickListener(TextClassification.createIntentOnClickListener(
                        TextClassification.createPendingIntent(mContext,
                                labeledIntent.getIntent(), labeledIntent.getRequestCode())));
                isPrimaryAction = false;
            }
            builder.addAction(action);
        }

        return builder.setId(createId(text, start, end)).build();
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
        private final boolean mLanguageIndependent;

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
                final boolean languageIndependent = supportedLocalesStr.equals("*");
                final List<Locale> supportedLocales = new ArrayList<>();
                for (String langTag : supportedLocalesStr.split(",")) {
                    supportedLocales.add(Locale.forLanguageTag(langTag));
                }
                closeAndLogError(modelFd);
                return new ModelFile(path, file.getName(), version, supportedLocales,
                                     languageIndependent);
            } catch (FileNotFoundException e) {
                Log.e(DEFAULT_LOG_TAG, "Failed to peek " + file.getAbsolutePath(), e);
                return null;
            }
        }

        /** The absolute path to the model file. */
        String getPath() {
            return mPath;
        }

        /** A name to use for id generation. Effectively the name of the model file. */
        String getName() {
            return mName;
        }

        /** Returns the version tag in the model's metadata. */
        int getVersion() {
            return mVersion;
        }

        /** Returns whether the language supports any language in the given ranges. */
        boolean isAnyLanguageSupported(List<Locale.LanguageRange> languageRanges) {
            return mLanguageIndependent || Locale.lookup(languageRanges, mSupportedLocales) != null;
        }

        /** All locales supported by the model. */
        List<Locale> getSupportedLocales() {
            return Collections.unmodifiableList(mSupportedLocales);
        }

        public boolean isPreferredTo(ModelFile model) {
            // A model is preferred to no model.
            if (model == null) {
                return true;
            }

            // A language-specific model is preferred to a language independent
            // model.
            if (!mLanguageIndependent && model.mLanguageIndependent) {
                return true;
            }

            // A higher-version model is preferred.
            if (getVersion() > model.getVersion()) {
                return true;
            }
            return false;
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

        private ModelFile(String path, String name, int version, List<Locale> supportedLocales,
                          boolean languageIndependent) {
            mPath = path;
            mName = name;
            mVersion = version;
            mSupportedLocales = supportedLocales;
            mLanguageIndependent = languageIndependent;
        }
    }

    /**
     * Helper class to store the information from which RemoteActions are built.
     */
    private static final class LabeledIntent {

        static final int DEFAULT_REQUEST_CODE = 0;

        private final String mTitle;
        private final String mDescription;
        private final Intent mIntent;
        private final int mRequestCode;

        /**
         * Initializes a LabeledIntent.
         *
         * <p>NOTE: {@code reqestCode} is required to not be {@link #DEFAULT_REQUEST_CODE}
         * if distinguishing info (e.g. the classified text) is represented in intent extras only.
         * In such circumstances, the request code should represent the distinguishing info
         * (e.g. by generating a hashcode) so that the generated PendingIntent is (somewhat)
         * unique. To be correct, the PendingIntent should be definitely unique but we try a
         * best effort approach that avoids spamming the system with PendingIntents.
         */
        // TODO: Fix the issue mentioned above so the behaviour is correct.
        LabeledIntent(String title, String description, Intent intent, int requestCode) {
            mTitle = title;
            mDescription = description;
            mIntent = intent;
            mRequestCode = requestCode;
        }

        String getTitle() {
            return mTitle;
        }

        String getDescription() {
            return mDescription;
        }

        Intent getIntent() {
            return mIntent;
        }

        int getRequestCode() {
            return mRequestCode;
        }

        @Nullable
        RemoteAction asRemoteAction(Context context) {
            final PackageManager pm = context.getPackageManager();
            final ResolveInfo resolveInfo = pm.resolveActivity(mIntent, 0);
            final String packageName = resolveInfo != null && resolveInfo.activityInfo != null
                    ? resolveInfo.activityInfo.packageName : null;
            Icon icon = null;
            boolean shouldShowIcon = false;
            if (packageName != null && !"android".equals(packageName)) {
                // There is a default activity handling the intent.
                mIntent.setComponent(new ComponentName(packageName, resolveInfo.activityInfo.name));
                if (resolveInfo.activityInfo.getIconResource() != 0) {
                    icon = Icon.createWithResource(
                            packageName, resolveInfo.activityInfo.getIconResource());
                    shouldShowIcon = true;
                }
            }
            if (icon == null) {
                // RemoteAction requires that there be an icon.
                icon = Icon.createWithResource("android",
                        com.android.internal.R.drawable.ic_more_items);
            }
            final PendingIntent pendingIntent =
                    TextClassification.createPendingIntent(context, mIntent, mRequestCode);
            if (pendingIntent == null) {
                return null;
            }
            final RemoteAction action = new RemoteAction(icon, mTitle, mDescription, pendingIntent);
            action.setShouldShowIcon(shouldShowIcon);
            return action;
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
        public static List<LabeledIntent> create(
                Context context,
                @Nullable Instant referenceTime,
                TextClassifierImplNative.ClassificationResult classification,
                String text) {
            final String type = classification.getCollection().trim().toLowerCase(Locale.ENGLISH);
            text = text.trim();
            switch (type) {
                case TextClassifier.TYPE_EMAIL:
                    return createForEmail(context, text);
                case TextClassifier.TYPE_PHONE:
                    return createForPhone(context, text);
                case TextClassifier.TYPE_ADDRESS:
                    return createForAddress(context, text);
                case TextClassifier.TYPE_URL:
                    return createForUrl(context, text);
                case TextClassifier.TYPE_DATE:
                case TextClassifier.TYPE_DATE_TIME:
                    if (classification.getDatetimeResult() != null) {
                        final Instant parsedTime = Instant.ofEpochMilli(
                                classification.getDatetimeResult().getTimeMsUtc());
                        return createForDatetime(context, type, referenceTime, parsedTime);
                    } else {
                        return new ArrayList<>();
                    }
                case TextClassifier.TYPE_FLIGHT_NUMBER:
                    return createForFlight(context, text);
                default:
                    return new ArrayList<>();
            }
        }

        @NonNull
        private static List<LabeledIntent> createForEmail(Context context, String text) {
            return Arrays.asList(
                    new LabeledIntent(
                            context.getString(com.android.internal.R.string.email),
                            context.getString(com.android.internal.R.string.email_desc),
                            new Intent(Intent.ACTION_SENDTO)
                                    .setData(Uri.parse(String.format("mailto:%s", text))),
                            LabeledIntent.DEFAULT_REQUEST_CODE),
                    new LabeledIntent(
                            context.getString(com.android.internal.R.string.add_contact),
                            context.getString(com.android.internal.R.string.add_contact_desc),
                            new Intent(Intent.ACTION_INSERT_OR_EDIT)
                                    .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                    .putExtra(ContactsContract.Intents.Insert.EMAIL, text),
                            text.hashCode()));
        }

        @NonNull
        private static List<LabeledIntent> createForPhone(Context context, String text) {
            final List<LabeledIntent> actions = new ArrayList<>();
            final UserManager userManager = context.getSystemService(UserManager.class);
            final Bundle userRestrictions = userManager != null
                    ? userManager.getUserRestrictions() : new Bundle();
            if (!userRestrictions.getBoolean(UserManager.DISALLOW_OUTGOING_CALLS, false)) {
                actions.add(new LabeledIntent(
                        context.getString(com.android.internal.R.string.dial),
                        context.getString(com.android.internal.R.string.dial_desc),
                        new Intent(Intent.ACTION_DIAL).setData(
                                Uri.parse(String.format("tel:%s", text))),
                        LabeledIntent.DEFAULT_REQUEST_CODE));
            }
            actions.add(new LabeledIntent(
                    context.getString(com.android.internal.R.string.add_contact),
                    context.getString(com.android.internal.R.string.add_contact_desc),
                    new Intent(Intent.ACTION_INSERT_OR_EDIT)
                            .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                            .putExtra(ContactsContract.Intents.Insert.PHONE, text),
                    text.hashCode()));
            if (!userRestrictions.getBoolean(UserManager.DISALLOW_SMS, false)) {
                actions.add(new LabeledIntent(
                        context.getString(com.android.internal.R.string.sms),
                        context.getString(com.android.internal.R.string.sms_desc),
                        new Intent(Intent.ACTION_SENDTO)
                                .setData(Uri.parse(String.format("smsto:%s", text))),
                        LabeledIntent.DEFAULT_REQUEST_CODE));
            }
            return actions;
        }

        @NonNull
        private static List<LabeledIntent> createForAddress(Context context, String text) {
            final List<LabeledIntent> actions = new ArrayList<>();
            try {
                final String encText = URLEncoder.encode(text, "UTF-8");
                actions.add(new LabeledIntent(
                        context.getString(com.android.internal.R.string.map),
                        context.getString(com.android.internal.R.string.map_desc),
                        new Intent(Intent.ACTION_VIEW)
                                .setData(Uri.parse(String.format("geo:0,0?q=%s", encText))),
                        LabeledIntent.DEFAULT_REQUEST_CODE));
            } catch (UnsupportedEncodingException e) {
                Log.e(LOG_TAG, "Could not encode address", e);
            }
            return actions;
        }

        @NonNull
        private static List<LabeledIntent> createForUrl(Context context, String text) {
            final String httpPrefix = "http://";
            final String httpsPrefix = "https://";
            if (text.toLowerCase().startsWith(httpPrefix)) {
                text = httpPrefix + text.substring(httpPrefix.length());
            } else if (text.toLowerCase().startsWith(httpsPrefix)) {
                text = httpsPrefix + text.substring(httpsPrefix.length());
            } else {
                text = httpPrefix + text;
            }
            return Arrays.asList(new LabeledIntent(
                    context.getString(com.android.internal.R.string.browse),
                    context.getString(com.android.internal.R.string.browse_desc),
                    new Intent(Intent.ACTION_VIEW, Uri.parse(text))
                            .putExtra(Browser.EXTRA_APPLICATION_ID, context.getPackageName()),
                    LabeledIntent.DEFAULT_REQUEST_CODE));
        }

        @NonNull
        private static List<LabeledIntent> createForDatetime(
                Context context, String type, @Nullable Instant referenceTime,
                Instant parsedTime) {
            if (referenceTime == null) {
                // If no reference time was given, use now.
                referenceTime = Instant.now();
            }
            List<LabeledIntent> actions = new ArrayList<>();
            actions.add(createCalendarViewIntent(context, parsedTime));
            final long millisUntilEvent = referenceTime.until(parsedTime, MILLIS);
            if (millisUntilEvent > MIN_EVENT_FUTURE_MILLIS) {
                actions.add(createCalendarCreateEventIntent(context, parsedTime, type));
            }
            return actions;
        }

        @NonNull
        private static List<LabeledIntent> createForFlight(Context context, String text) {
            return Arrays.asList(new LabeledIntent(
                    context.getString(com.android.internal.R.string.view_flight),
                    context.getString(com.android.internal.R.string.view_flight_desc),
                    new Intent(Intent.ACTION_WEB_SEARCH)
                            .putExtra(SearchManager.QUERY, text),
                    text.hashCode()));
        }

        @NonNull
        private static LabeledIntent createCalendarViewIntent(Context context, Instant parsedTime) {
            Uri.Builder builder = CalendarContract.CONTENT_URI.buildUpon();
            builder.appendPath("time");
            ContentUris.appendId(builder, parsedTime.toEpochMilli());
            return new LabeledIntent(
                    context.getString(com.android.internal.R.string.view_calendar),
                    context.getString(com.android.internal.R.string.view_calendar_desc),
                    new Intent(Intent.ACTION_VIEW).setData(builder.build()),
                    LabeledIntent.DEFAULT_REQUEST_CODE);
        }

        @NonNull
        private static LabeledIntent createCalendarCreateEventIntent(
                Context context, Instant parsedTime, @EntityType String type) {
            final boolean isAllDay = TextClassifier.TYPE_DATE.equals(type);
            return new LabeledIntent(
                    context.getString(com.android.internal.R.string.add_calendar_event),
                    context.getString(com.android.internal.R.string.add_calendar_event_desc),
                    new Intent(Intent.ACTION_INSERT)
                            .setData(CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, isAllDay)
                            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                                    parsedTime.toEpochMilli())
                            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                                    parsedTime.toEpochMilli() + DEFAULT_EVENT_DURATION),
                    parsedTime.hashCode());
        }
    }
}
