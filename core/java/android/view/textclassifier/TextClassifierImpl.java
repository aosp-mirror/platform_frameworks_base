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
import android.net.Uri;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.provider.Browser;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.util.Linkify;
import android.util.Patterns;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
final class TextClassifierImpl implements TextClassifier {

    private static final String LOG_TAG = DEFAULT_LOG_TAG;
    private static final String MODEL_DIR = "/etc/textclassifier/";
    private static final String MODEL_FILE_REGEX = "textclassifier\\.smartselection\\.(.*)\\.model";
    private static final String UPDATED_MODEL_FILE_PATH =
            "/data/misc/textclassifier/textclassifier.smartselection.model";
    private static final List<String> ENTITY_TYPES_ALL =
            Collections.unmodifiableList(Arrays.asList(
                    TextClassifier.TYPE_ADDRESS,
                    TextClassifier.TYPE_EMAIL,
                    TextClassifier.TYPE_PHONE,
                    TextClassifier.TYPE_URL));
    private static final List<String> ENTITY_TYPES_BASE =
            Collections.unmodifiableList(Arrays.asList(
                    TextClassifier.TYPE_ADDRESS,
                    TextClassifier.TYPE_EMAIL,
                    TextClassifier.TYPE_PHONE,
                    TextClassifier.TYPE_URL));

    private final Context mContext;

    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    private final Object mSmartSelectionLock = new Object();
    @GuardedBy("mSmartSelectionLock") // Do not access outside this lock.
    private Map<Locale, String> mModelFilePaths;
    @GuardedBy("mSmartSelectionLock") // Do not access outside this lock.
    private Locale mLocale;
    @GuardedBy("mSmartSelectionLock") // Do not access outside this lock.
    private int mVersion;
    @GuardedBy("mSmartSelectionLock") // Do not access outside this lock.
    private SmartSelection mSmartSelection;

    private TextClassifierConstants mSettings;

    TextClassifierImpl(Context context) {
        mContext = Preconditions.checkNotNull(context);
    }

    @Override
    public TextSelection suggestSelection(
            @NonNull CharSequence text, int selectionStartIndex, int selectionEndIndex,
            @NonNull TextSelection.Options options) {
        Utils.validateInput(text, selectionStartIndex, selectionEndIndex);
        try {
            if (text.length() > 0) {
                final LocaleList locales = (options == null) ? null : options.getDefaultLocales();
                final SmartSelection smartSelection = getSmartSelection(locales);
                final String string = text.toString();
                final int start;
                final int end;
                if (getSettings().isDarkLaunch() && !options.isDarkLaunchAllowed()) {
                    start = selectionStartIndex;
                    end = selectionEndIndex;
                } else {
                    final int[] startEnd = smartSelection.suggest(
                            string, selectionStartIndex, selectionEndIndex);
                    start = startEnd[0];
                    end = startEnd[1];
                }
                if (start <= end
                        && start >= 0 && end <= string.length()
                        && start <= selectionStartIndex && end >= selectionEndIndex) {
                    final TextSelection.Builder tsBuilder = new TextSelection.Builder(start, end);
                    final SmartSelection.ClassificationResult[] results =
                            smartSelection.classifyText(
                                    string, start, end,
                                    getHintFlags(string, start, end));
                    final int size = results.length;
                    for (int i = 0; i < size; i++) {
                        tsBuilder.setEntityType(results[i].mCollection, results[i].mScore);
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
        return TextClassifier.NO_OP.suggestSelection(
                text, selectionStartIndex, selectionEndIndex, options);
    }

    @Override
    public TextClassification classifyText(
            @NonNull CharSequence text, int startIndex, int endIndex,
            @NonNull TextClassification.Options options) {
        Utils.validateInput(text, startIndex, endIndex);
        try {
            if (text.length() > 0) {
                final String string = text.toString();
                final LocaleList locales = (options == null) ? null : options.getDefaultLocales();
                final SmartSelection.ClassificationResult[] results = getSmartSelection(locales)
                        .classifyText(string, startIndex, endIndex,
                                getHintFlags(string, startIndex, endIndex));
                if (results.length > 0) {
                    final TextClassification classificationResult =
                            createClassificationResult(results, string, startIndex, endIndex);
                    return classificationResult;
                }
            }
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error getting text classification info.", t);
        }
        // Getting here means something went wrong, return a NO_OP result.
        return TextClassifier.NO_OP.classifyText(text, startIndex, endIndex, options);
    }

    @Override
    public TextLinks generateLinks(
            @NonNull CharSequence text, @Nullable TextLinks.Options options) {
        Utils.validateInput(text);
        final String textString = text.toString();
        final TextLinks.Builder builder = new TextLinks.Builder(textString);

        if (!getSettings().isSmartLinkifyEnabled()) {
            return builder.build();
        }

        try {
            final LocaleList defaultLocales = options != null ? options.getDefaultLocales() : null;
            final Collection<String> entitiesToIdentify =
                    options != null && options.getEntityConfig() != null
                            ? options.getEntityConfig().getEntities(this) : ENTITY_TYPES_ALL;
            final SmartSelection smartSelection = getSmartSelection(defaultLocales);
            final SmartSelection.AnnotatedSpan[] annotations = smartSelection.annotate(textString);
            for (SmartSelection.AnnotatedSpan span : annotations) {
                final SmartSelection.ClassificationResult[] results = span.getClassification();
                if (results.length == 0 || !entitiesToIdentify.contains(results[0].mCollection)) {
                    continue;
                }
                final Map<String, Float> entityScores = new HashMap<>();
                for (int i = 0; i < results.length; i++) {
                    entityScores.put(results[i].mCollection, results[i].mScore);
                }
                builder.addLink(new TextLinks.TextLink(
                        textString, span.getStartIndex(), span.getEndIndex(), entityScores));
            }
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error getting links info.", t);
        }
        return builder.build();
    }

    @Override
    public Collection<String> getEntitiesForPreset(@TextClassifier.EntityPreset int entityPreset) {
        switch (entityPreset) {
            case TextClassifier.ENTITY_PRESET_NONE:
                return Collections.emptyList();
            case TextClassifier.ENTITY_PRESET_BASE:
                return ENTITY_TYPES_BASE;
            case TextClassifier.ENTITY_PRESET_ALL:
                // fall through
            default:
                return ENTITY_TYPES_ALL;
        }
    }

    @Override
    public void logEvent(String source, String event) {
        if (LOG_TAG.equals(source)) {
            mMetricsLogger.count(event, 1);
        }
    }

    @Override
    public TextClassifierConstants getSettings() {
        if (mSettings == null) {
            mSettings = TextClassifierConstants.loadFromString(Settings.Global.getString(
                    mContext.getContentResolver(), Settings.Global.TEXT_CLASSIFIER_CONSTANTS));
        }
        return mSettings;
    }

    private SmartSelection getSmartSelection(LocaleList localeList) throws FileNotFoundException {
        synchronized (mSmartSelectionLock) {
            localeList = localeList == null ? LocaleList.getEmptyLocaleList() : localeList;
            final Locale locale = findBestSupportedLocaleLocked(localeList);
            if (locale == null) {
                throw new FileNotFoundException("No file for null locale");
            }
            if (mSmartSelection == null || !Objects.equals(mLocale, locale)) {
                destroySmartSelectionIfExistsLocked();
                final ParcelFileDescriptor fd = getFdLocked(locale);
                final int modelFd = fd.getFd();
                mVersion = SmartSelection.getVersion(modelFd);
                mSmartSelection = new SmartSelection(modelFd);
                closeAndLogError(fd);
                mLocale = locale;
            }
            return mSmartSelection;
        }
    }

    private String getSignature(String text, int start, int end) {
        synchronized (mSmartSelectionLock) {
            final String versionInfo = (mLocale != null)
                    ? String.format(Locale.US, "%s_v%d", mLocale.toLanguageTag(), mVersion)
                    : "";
            final int hash = Objects.hash(text, start, end, mContext.getPackageName());
            return String.format(Locale.US, "%s|%s|%d", LOG_TAG, versionInfo, hash);
        }
    }

    @GuardedBy("mSmartSelectionLock") // Do not call outside this lock.
    private ParcelFileDescriptor getFdLocked(Locale locale) throws FileNotFoundException {
        ParcelFileDescriptor updateFd;
        int updateVersion = -1;
        try {
            updateFd = ParcelFileDescriptor.open(
                    new File(UPDATED_MODEL_FILE_PATH), ParcelFileDescriptor.MODE_READ_ONLY);
            if (updateFd != null) {
                updateVersion = SmartSelection.getVersion(updateFd.getFd());
            }
        } catch (FileNotFoundException e) {
            updateFd = null;
        }
        ParcelFileDescriptor factoryFd;
        int factoryVersion = -1;
        try {
            final String factoryModelFilePath = getFactoryModelFilePathsLocked().get(locale);
            if (factoryModelFilePath != null) {
                factoryFd = ParcelFileDescriptor.open(
                        new File(factoryModelFilePath), ParcelFileDescriptor.MODE_READ_ONLY);
                if (factoryFd != null) {
                    factoryVersion = SmartSelection.getVersion(factoryFd.getFd());
                }
            } else {
                factoryFd = null;
            }
        } catch (FileNotFoundException e) {
            factoryFd = null;
        }

        if (updateFd == null) {
            if (factoryFd != null) {
                return factoryFd;
            } else {
                throw new FileNotFoundException(
                        String.format("No model file found for %s", locale));
            }
        }

        final int updateFdInt = updateFd.getFd();
        final boolean localeMatches = Objects.equals(
                locale.getLanguage().trim().toLowerCase(),
                SmartSelection.getLanguage(updateFdInt).trim().toLowerCase());
        if (factoryFd == null) {
            if (localeMatches) {
                return updateFd;
            } else {
                closeAndLogError(updateFd);
                throw new FileNotFoundException(
                        String.format("No model file found for %s", locale));
            }
        }

        if (!localeMatches) {
            closeAndLogError(updateFd);
            return factoryFd;
        }

        if (updateVersion > factoryVersion) {
            closeAndLogError(factoryFd);
            return updateFd;
        } else {
            closeAndLogError(updateFd);
            return factoryFd;
        }
    }

    @GuardedBy("mSmartSelectionLock") // Do not call outside this lock.
    private void destroySmartSelectionIfExistsLocked() {
        if (mSmartSelection != null) {
            mSmartSelection.close();
            mSmartSelection = null;
        }
    }

    @GuardedBy("mSmartSelectionLock") // Do not call outside this lock.
    @Nullable
    private Locale findBestSupportedLocaleLocked(LocaleList localeList) {
        // Specified localeList takes priority over the system default, so it is listed first.
        final String languages = localeList.isEmpty()
                ? LocaleList.getDefault().toLanguageTags()
                : localeList.toLanguageTags() + "," + LocaleList.getDefault().toLanguageTags();
        final List<Locale.LanguageRange> languageRangeList = Locale.LanguageRange.parse(languages);

        final List<Locale> supportedLocales =
                new ArrayList<>(getFactoryModelFilePathsLocked().keySet());
        final Locale updatedModelLocale = getUpdatedModelLocale();
        if (updatedModelLocale != null) {
            supportedLocales.add(updatedModelLocale);
        }
        return Locale.lookup(languageRangeList, supportedLocales);
    }

    @GuardedBy("mSmartSelectionLock") // Do not call outside this lock.
    private Map<Locale, String> getFactoryModelFilePathsLocked() {
        if (mModelFilePaths == null) {
            final Map<Locale, String> modelFilePaths = new HashMap<>();
            final File modelsDir = new File(MODEL_DIR);
            if (modelsDir.exists() && modelsDir.isDirectory()) {
                final File[] models = modelsDir.listFiles();
                final Pattern modelFilenamePattern = Pattern.compile(MODEL_FILE_REGEX);
                final int size = models.length;
                for (int i = 0; i < size; i++) {
                    final File modelFile = models[i];
                    final Matcher matcher = modelFilenamePattern.matcher(modelFile.getName());
                    if (matcher.matches() && modelFile.isFile()) {
                        final String language = matcher.group(1);
                        final Locale locale = Locale.forLanguageTag(language);
                        modelFilePaths.put(locale, modelFile.getAbsolutePath());
                    }
                }
            }
            mModelFilePaths = modelFilePaths;
        }
        return mModelFilePaths;
    }

    @Nullable
    private Locale getUpdatedModelLocale() {
        try {
            final ParcelFileDescriptor updateFd = ParcelFileDescriptor.open(
                    new File(UPDATED_MODEL_FILE_PATH), ParcelFileDescriptor.MODE_READ_ONLY);
            final Locale locale = Locale.forLanguageTag(
                    SmartSelection.getLanguage(updateFd.getFd()));
            closeAndLogError(updateFd);
            return locale;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    private TextClassification createClassificationResult(
            SmartSelection.ClassificationResult[] classifications,
            String text, int start, int end) {
        final String classifiedText = text.substring(start, end);
        final TextClassification.Builder builder = new TextClassification.Builder()
                .setText(classifiedText);

        final int size = classifications.length;
        for (int i = 0; i < size; i++) {
            builder.setEntityType(classifications[i].mCollection, classifications[i].mScore);
        }

        final String type = getHighestScoringType(classifications);
        addActions(builder, IntentFactory.create(mContext, type, classifiedText));

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
                CharSequence label;
                Drawable icon;
                if ("android".equals(packageName)) {
                    // Requires the chooser to find an activity to handle the intent.
                    label = IntentFactory.getLabel(mContext, intent);
                    icon = null;
                } else {
                    // A default activity will handle the intent.
                    intent.setComponent(
                            new ComponentName(packageName, resolveInfo.activityInfo.name));
                    icon = resolveInfo.activityInfo.loadIcon(pm);
                    if (icon == null) {
                        icon = resolveInfo.loadIcon(pm);
                    }
                    label = resolveInfo.activityInfo.loadLabel(pm);
                    if (label == null) {
                        label = resolveInfo.loadLabel(pm);
                    }
                }
                final String labelString = (label != null) ? label.toString() : null;
                if (i == 0) {
                    builder.setPrimaryAction(intent, labelString, icon);
                } else {
                    builder.addSecondaryAction(intent, labelString, icon);
                }
            }
        }
    }

    private static int getHintFlags(CharSequence text, int start, int end) {
        int flag = 0;
        final CharSequence subText = text.subSequence(start, end);
        if (Patterns.AUTOLINK_EMAIL_ADDRESS.matcher(subText).matches()) {
            flag |= SmartSelection.HINT_FLAG_EMAIL;
        }
        if (Patterns.AUTOLINK_WEB_URL.matcher(subText).matches()
                && Linkify.sUrlMatchFilter.acceptMatch(text, start, end)) {
            flag |= SmartSelection.HINT_FLAG_URL;
        }
        return flag;
    }

    private static String getHighestScoringType(SmartSelection.ClassificationResult[] types) {
        if (types.length < 1) {
            return "";
        }

        String type = types[0].mCollection;
        float highestScore = types[0].mScore;
        final int size = types.length;
        for (int i = 1; i < size; i++) {
            if (types[i].mScore > highestScore) {
                type = types[i].mCollection;
                highestScore = types[i].mScore;
            }
        }
        return type;
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
     * Creates intents based on the classification type.
     */
    private static final class IntentFactory {

        private IntentFactory() {}

        @NonNull
        public static List<Intent> create(Context context, String type, String text) {
            final List<Intent> intents = new ArrayList<>();
            type = type.trim().toLowerCase(Locale.ENGLISH);
            text = text.trim();
            switch (type) {
                case TextClassifier.TYPE_EMAIL:
                    intents.add(new Intent(Intent.ACTION_SENDTO)
                            .setData(Uri.parse(String.format("mailto:%s", text))));
                    intents.add(new Intent(Intent.ACTION_INSERT_OR_EDIT)
                            .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                            .putExtra(ContactsContract.Intents.Insert.EMAIL, text));
                    break;
                case TextClassifier.TYPE_PHONE:
                    intents.add(new Intent(Intent.ACTION_DIAL)
                            .setData(Uri.parse(String.format("tel:%s", text))));
                    intents.add(new Intent(Intent.ACTION_INSERT_OR_EDIT)
                            .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                            .putExtra(ContactsContract.Intents.Insert.PHONE, text));
                    intents.add(new Intent(Intent.ACTION_SENDTO)
                            .setData(Uri.parse(String.format("smsto:%s", text))));
                    break;
                case TextClassifier.TYPE_ADDRESS:
                    intents.add(new Intent(Intent.ACTION_VIEW)
                            .setData(Uri.parse(String.format("geo:0,0?q=%s", text))));
                    break;
                case TextClassifier.TYPE_URL:
                    final String httpPrefix = "http://";
                    final String httpsPrefix = "https://";
                    if (text.toLowerCase().startsWith(httpPrefix)) {
                        text = httpPrefix + text.substring(httpPrefix.length());
                    } else if (text.toLowerCase().startsWith(httpsPrefix)) {
                        text = httpsPrefix + text.substring(httpsPrefix.length());
                    } else {
                        text = httpPrefix + text;
                    }
                    intents.add(new Intent(Intent.ACTION_VIEW, Uri.parse(text))
                            .putExtra(Browser.EXTRA_APPLICATION_ID, context.getPackageName()));
                    break;
            }
            return intents;
        }

        @Nullable
        public static String getLabel(Context context, @Nullable Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return null;
            }
            switch (intent.getAction()) {
                case Intent.ACTION_DIAL:
                    return context.getString(com.android.internal.R.string.dial);
                case Intent.ACTION_SENDTO:
                    switch (intent.getScheme()) {
                        case "mailto":
                            return context.getString(com.android.internal.R.string.email);
                        case "smsto":
                            return context.getString(com.android.internal.R.string.sms);
                        default:
                            return null;
                    }
                case Intent.ACTION_INSERT_OR_EDIT:
                    switch (intent.getDataString()) {
                        case ContactsContract.Contacts.CONTENT_ITEM_TYPE:
                            return context.getString(com.android.internal.R.string.add_contact);
                        default:
                            return null;
                    }
                case Intent.ACTION_VIEW:
                    switch (intent.getScheme()) {
                        case "geo":
                            return context.getString(com.android.internal.R.string.map);
                        case "http": // fall through
                        case "https":
                            return context.getString(com.android.internal.R.string.browse);
                        default:
                            return null;
                    }
                default:
                    return null;
            }
        }
    }
}
