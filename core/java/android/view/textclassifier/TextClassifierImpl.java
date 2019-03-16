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
import android.annotation.WorkerThread;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.icu.util.ULocale;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.view.textclassifier.intent.ClassificationIntentFactory;
import android.view.textclassifier.intent.LabeledIntent;
import android.view.textclassifier.intent.LegacyClassificationIntentFactory;
import android.view.textclassifier.intent.TemplateClassificationIntentFactory;
import android.view.textclassifier.intent.TemplateIntentFactory;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import com.google.android.textclassifier.ActionsSuggestionsModel;
import com.google.android.textclassifier.AnnotatorModel;
import com.google.android.textclassifier.LangIdModel;
import com.google.android.textclassifier.LangIdModel.LanguageResult;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private static final boolean DEBUG = false;

    private static final File FACTORY_MODEL_DIR = new File("/etc/textclassifier/");
    // Annotator
    private static final String ANNOTATOR_FACTORY_MODEL_FILENAME_REGEX =
            "textclassifier\\.(.*)\\.model";
    private static final File ANNOTATOR_UPDATED_MODEL_FILE =
            new File("/data/misc/textclassifier/textclassifier.model");

    // LangID
    private static final String LANG_ID_FACTORY_MODEL_FILENAME_REGEX = "lang_id.model";
    private static final File UPDATED_LANG_ID_MODEL_FILE =
            new File("/data/misc/textclassifier/lang_id.model");

    // Actions
    private static final String ACTIONS_FACTORY_MODEL_FILENAME_REGEX = "actions_suggestions.model";
    private static final File UPDATED_ACTIONS_MODEL =
            new File("/data/misc/textclassifier/actions_suggestions.model");

    private final Context mContext;
    private final TextClassifier mFallback;
    private final GenerateLinksLogger mGenerateLinksLogger;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private ModelFileManager.ModelFile mAnnotatorModelInUse;
    @GuardedBy("mLock")
    private AnnotatorModel mAnnotatorImpl;

    @GuardedBy("mLock")
    private ModelFileManager.ModelFile mLangIdModelInUse;
    @GuardedBy("mLock")
    private LangIdModel mLangIdImpl;

    @GuardedBy("mLock")
    private ModelFileManager.ModelFile mActionModelInUse;
    @GuardedBy("mLock")
    private ActionsSuggestionsModel mActionsImpl;

    private final SelectionSessionLogger mSessionLogger = new SelectionSessionLogger();
    private final TextClassifierEventTronLogger mTextClassifierEventTronLogger =
            new TextClassifierEventTronLogger();

    private final TextClassificationConstants mSettings;

    private final ModelFileManager mAnnotatorModelFileManager;
    private final ModelFileManager mLangIdModelFileManager;
    private final ModelFileManager mActionsModelFileManager;

    private final ClassificationIntentFactory mClassificationIntentFactory;
    private final TemplateIntentFactory mTemplateIntentFactory;

    public TextClassifierImpl(
            Context context, TextClassificationConstants settings, TextClassifier fallback) {
        mContext = Preconditions.checkNotNull(context);
        mFallback = Preconditions.checkNotNull(fallback);
        mSettings = Preconditions.checkNotNull(settings);
        mGenerateLinksLogger = new GenerateLinksLogger(mSettings.getGenerateLinksLogSampleRate());
        mAnnotatorModelFileManager = new ModelFileManager(
                new ModelFileManager.ModelFileSupplierImpl(
                        FACTORY_MODEL_DIR,
                        ANNOTATOR_FACTORY_MODEL_FILENAME_REGEX,
                        ANNOTATOR_UPDATED_MODEL_FILE,
                        AnnotatorModel::getVersion,
                        AnnotatorModel::getLocales));
        mLangIdModelFileManager = new ModelFileManager(
                new ModelFileManager.ModelFileSupplierImpl(
                        FACTORY_MODEL_DIR,
                        LANG_ID_FACTORY_MODEL_FILENAME_REGEX,
                        UPDATED_LANG_ID_MODEL_FILE,
                        LangIdModel::getVersion,
                        fd -> ModelFileManager.ModelFile.LANGUAGE_INDEPENDENT));
        mActionsModelFileManager = new ModelFileManager(
                new ModelFileManager.ModelFileSupplierImpl(
                        FACTORY_MODEL_DIR,
                        ACTIONS_FACTORY_MODEL_FILENAME_REGEX,
                        UPDATED_ACTIONS_MODEL,
                        ActionsSuggestionsModel::getVersion,
                        ActionsSuggestionsModel::getLocales));

        mTemplateIntentFactory = new TemplateIntentFactory();
        mClassificationIntentFactory = mSettings.isTemplateIntentFactoryEnabled()
                ? new TemplateClassificationIntentFactory(
                mTemplateIntentFactory, new LegacyClassificationIntentFactory())
                : new LegacyClassificationIntentFactory();
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
                final String detectLanguageTags = detectLanguageTagsFromText(request.getText());
                final ZonedDateTime refTime = ZonedDateTime.now();
                final AnnotatorModel annotatorImpl =
                        getAnnotatorImpl(request.getDefaultLocales());
                final int start;
                final int end;
                if (mSettings.isModelDarkLaunchEnabled() && !request.isDarkLaunchAllowed()) {
                    start = request.getStartIndex();
                    end = request.getEndIndex();
                } else {
                    final int[] startEnd = annotatorImpl.suggestSelection(
                            string, request.getStartIndex(), request.getEndIndex(),
                            new AnnotatorModel.SelectionOptions(localesString, detectLanguageTags));
                    start = startEnd[0];
                    end = startEnd[1];
                }
                if (start < end
                        && start >= 0 && end <= string.length()
                        && start <= request.getStartIndex() && end >= request.getEndIndex()) {
                    final TextSelection.Builder tsBuilder = new TextSelection.Builder(start, end);
                    final AnnotatorModel.ClassificationResult[] results =
                            annotatorImpl.classifyText(
                                    string, start, end,
                                    new AnnotatorModel.ClassificationOptions(
                                            refTime.toInstant().toEpochMilli(),
                                            refTime.getZone().getId(),
                                            localesString,
                                            detectLanguageTags),
                                    // Passing null here to suppress intent generation
                                    // TODO: Use an explicit flag to suppress it.
                                    /* appContext */ null,
                                    /* deviceLocales */null);
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
                final String detectLanguageTags = detectLanguageTagsFromText(request.getText());
                final ZonedDateTime refTime = request.getReferenceTime() != null
                        ? request.getReferenceTime() : ZonedDateTime.now();
                final AnnotatorModel.ClassificationResult[] results =
                        getAnnotatorImpl(request.getDefaultLocales())
                                .classifyText(
                                        string, request.getStartIndex(), request.getEndIndex(),
                                        new AnnotatorModel.ClassificationOptions(
                                                refTime.toInstant().toEpochMilli(),
                                                refTime.getZone().getId(),
                                                localesString,
                                                detectLanguageTags),
                                        mContext,
                                        getResourceLocalesString()
                                );
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
            final String localesString = concatenateLocales(request.getDefaultLocales());
            final String detectLanguageTags = detectLanguageTagsFromText(request.getText());
            final AnnotatorModel annotatorImpl =
                    getAnnotatorImpl(request.getDefaultLocales());
            final AnnotatorModel.AnnotatedSpan[] annotations =
                    annotatorImpl.annotate(
                            textString,
                            new AnnotatorModel.AnnotationOptions(
                                    refTime.toInstant().toEpochMilli(),
                                    refTime.getZone().getId(),
                                    localesString,
                                    detectLanguageTags));
            for (AnnotatorModel.AnnotatedSpan span : annotations) {
                final AnnotatorModel.ClassificationResult[] results =
                        span.getClassification();
                if (results.length == 0
                        || !entitiesToIdentify.contains(results[0].getCollection())) {
                    continue;
                }
                final Map<String, Float> entityScores = new ArrayMap<>();
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

    /** @inheritDoc */
    @Override
    public void onSelectionEvent(SelectionEvent event) {
        Preconditions.checkNotNull(event);
        mSessionLogger.writeEvent(event);
    }

    @Override
    public void onTextClassifierEvent(TextClassifierEvent event) {
        if (DEBUG) {
            Log.d(DEFAULT_LOG_TAG, "onTextClassifierEvent() called with: event = [" + event + "]");
        }
        try {
            mTextClassifierEventTronLogger.writeEvent(event);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error writing event", e);
        }
    }

    /** @inheritDoc */
    @Override
    public TextLanguage detectLanguage(@NonNull TextLanguage.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();
        try {
            final TextLanguage.Builder builder = new TextLanguage.Builder();
            final LangIdModel.LanguageResult[] langResults =
                    getLangIdImpl().detectLanguages(request.getText().toString());
            for (int i = 0; i < langResults.length; i++) {
                builder.putLocale(
                        ULocale.forLanguageTag(langResults[i].getLanguage()),
                        langResults[i].getScore());
            }
            return builder.build();
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error detecting text language.", t);
        }
        return mFallback.detectLanguage(request);
    }

    @Override
    public ConversationActions suggestConversationActions(ConversationActions.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();
        try {
            ActionsSuggestionsModel actionsImpl = getActionsImpl();
            if (actionsImpl == null) {
                // Actions model is optional, fallback if it is not available.
                return mFallback.suggestConversationActions(request);
            }
            ActionsSuggestionsModel.ConversationMessage[] nativeMessages =
                    ActionsSuggestionsHelper.toNativeMessages(
                            request.getConversation(), this::detectLanguageTagsFromText);
            if (nativeMessages.length == 0) {
                return mFallback.suggestConversationActions(request);
            }
            ActionsSuggestionsModel.Conversation nativeConversation =
                    new ActionsSuggestionsModel.Conversation(nativeMessages);

            ActionsSuggestionsModel.ActionSuggestion[] nativeSuggestions =
                    actionsImpl.suggestActionsWithIntents(
                            nativeConversation,
                            null,
                            mContext,
                            getResourceLocalesString());
            return createConversationActionResult(request, nativeSuggestions);
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error suggesting conversation actions.", t);
        }
        return mFallback.suggestConversationActions(request);
    }

    /**
     * Returns the {@link ConversationAction} result, with a non-null extras.
     * <p>
     * Whenever the RemoteAction is non-null, you can expect its corresponding intent
     * with a non-null component name is in the extras.
     */
    private ConversationActions createConversationActionResult(
            ConversationActions.Request request,
            ActionsSuggestionsModel.ActionSuggestion[] nativeSuggestions) {
        Collection<String> expectedTypes = resolveActionTypesFromRequest(request);
        List<ConversationAction> conversationActions = new ArrayList<>();
        for (ActionsSuggestionsModel.ActionSuggestion nativeSuggestion : nativeSuggestions) {
            if (request.getMaxSuggestions() >= 0
                    && conversationActions.size() == request.getMaxSuggestions()) {
                break;
            }
            String actionType = nativeSuggestion.getActionType();
            if (!expectedTypes.contains(actionType)) {
                continue;
            }
            LabeledIntent.Result labeledIntentResult =
                    ActionsSuggestionsHelper.createLabeledIntentResult(
                            mContext,
                            mTemplateIntentFactory,
                            nativeSuggestion);
            RemoteAction remoteAction = null;
            Bundle extras = new Bundle();
            if (labeledIntentResult != null) {
                remoteAction = labeledIntentResult.remoteAction;
                ExtrasUtils.putActionIntent(extras, labeledIntentResult.resolvedIntent);
            }
            ExtrasUtils.putEntitiesExtras(
                    extras,
                    TemplateIntentFactory.nameVariantsToBundle(nativeSuggestion.getEntityData()));
            conversationActions.add(
                    new ConversationAction.Builder(actionType)
                            .setConfidenceScore(nativeSuggestion.getScore())
                            .setTextReply(nativeSuggestion.getResponseText())
                            .setAction(remoteAction)
                            .setExtras(extras)
                            .build());
        }
        conversationActions =
                ActionsSuggestionsHelper.removeActionsWithDuplicates(conversationActions);
        String resultId = ActionsSuggestionsHelper.createResultId(
                mContext,
                request.getConversation(),
                mActionModelInUse.getVersion(),
                mActionModelInUse.getSupportedLocales());
        return new ConversationActions(conversationActions, resultId);
    }

    @Nullable
    private String detectLanguageTagsFromText(CharSequence text) {
        if (!mSettings.isDetectLanguagesFromTextEnabled()) {
            return null;
        }
        final float threshold = getLangIdThreshold();
        if (threshold < 0 || threshold > 1) {
            Log.w(LOG_TAG,
                    "[detectLanguageTagsFromText] unexpected threshold is found: " + threshold);
            return null;
        }
        TextLanguage.Request request = new TextLanguage.Request.Builder(text).build();
        TextLanguage textLanguage = detectLanguage(request);
        int localeHypothesisCount = textLanguage.getLocaleHypothesisCount();
        List<String> languageTags = new ArrayList<>();
        for (int i = 0; i < localeHypothesisCount; i++) {
            ULocale locale = textLanguage.getLocale(i);
            if (textLanguage.getConfidenceScore(locale) < threshold) {
                break;
            }
            languageTags.add(locale.toLanguageTag());
        }
        if (languageTags.isEmpty()) {
            return null;
        }
        return String.join(",", languageTags);
    }

    private Collection<String> resolveActionTypesFromRequest(ConversationActions.Request request) {
        List<String> defaultActionTypes =
                request.getHints().contains(ConversationActions.Request.HINT_FOR_NOTIFICATION)
                        ? mSettings.getNotificationConversationActionTypes()
                        : mSettings.getInAppConversationActionTypes();
        return request.getTypeConfig().resolveEntityListModifications(defaultActionTypes);
    }

    private AnnotatorModel getAnnotatorImpl(LocaleList localeList)
            throws FileNotFoundException {
        synchronized (mLock) {
            localeList = localeList == null ? LocaleList.getDefault() : localeList;
            final ModelFileManager.ModelFile bestModel =
                    mAnnotatorModelFileManager.findBestModelFile(localeList);
            if (bestModel == null) {
                throw new FileNotFoundException(
                        "No annotator model for " + localeList.toLanguageTags());
            }
            if (mAnnotatorImpl == null || !Objects.equals(mAnnotatorModelInUse, bestModel)) {
                Log.d(DEFAULT_LOG_TAG, "Loading " + bestModel);
                final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                        new File(bestModel.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
                try {
                    if (pfd != null) {
                        // The current annotator model may be still used by another thread / model.
                        // Do not call close() here, and let the GC to clean it up when no one else
                        // is using it.
                        mAnnotatorImpl = new AnnotatorModel(pfd.getFd());
                        mAnnotatorModelInUse = bestModel;
                    }
                } finally {
                    maybeCloseAndLogError(pfd);
                }
            }
            return mAnnotatorImpl;
        }
    }

    private LangIdModel getLangIdImpl() throws FileNotFoundException {
        synchronized (mLock) {
            final ModelFileManager.ModelFile bestModel =
                    mLangIdModelFileManager.findBestModelFile(null);
            if (bestModel == null) {
                throw new FileNotFoundException("No LangID model is found");
            }
            if (mLangIdImpl == null || !Objects.equals(mLangIdModelInUse, bestModel)) {
                Log.d(DEFAULT_LOG_TAG, "Loading " + bestModel);
                final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                        new File(bestModel.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
                try {
                    if (pfd != null) {
                        mLangIdImpl = new LangIdModel(pfd.getFd());
                        mLangIdModelInUse = bestModel;
                    }
                } finally {
                    maybeCloseAndLogError(pfd);
                }
            }
            return mLangIdImpl;
        }
    }

    @Nullable
    private ActionsSuggestionsModel getActionsImpl() throws FileNotFoundException {
        synchronized (mLock) {
            // TODO: Use LangID to determine the locale we should use here?
            final ModelFileManager.ModelFile bestModel =
                    mActionsModelFileManager.findBestModelFile(LocaleList.getDefault());
            if (bestModel == null) {
                return null;
            }
            if (mActionsImpl == null || !Objects.equals(mActionModelInUse, bestModel)) {
                Log.d(DEFAULT_LOG_TAG, "Loading " + bestModel);
                final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                        new File(bestModel.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
                try {
                    if (pfd != null) {
                        mActionsImpl = new ActionsSuggestionsModel(
                                pfd.getFd(), getAnnotatorImpl(LocaleList.getDefault()));
                        mActionModelInUse = bestModel;
                    }
                } finally {
                    maybeCloseAndLogError(pfd);
                }
            }
            return mActionsImpl;
        }
    }

    private String createId(String text, int start, int end) {
        synchronized (mLock) {
            return SelectionSessionLogger.createId(text, start, end, mContext,
                    mAnnotatorModelInUse.getVersion(),
                    mAnnotatorModelInUse.getSupportedLocales());
        }
    }

    private static String concatenateLocales(@Nullable LocaleList locales) {
        return (locales == null) ? "" : locales.toLanguageTags();
    }

    private TextClassification createClassificationResult(
            AnnotatorModel.ClassificationResult[] classifications,
            String text, int start, int end, @Nullable Instant referenceTime) {
        final String classifiedText = text.substring(start, end);
        final TextClassification.Builder builder = new TextClassification.Builder()
                .setText(classifiedText);

        final int typeCount = classifications.length;
        AnnotatorModel.ClassificationResult highestScoringResult =
                typeCount > 0 ? classifications[0] : null;
        for (int i = 0; i < typeCount; i++) {
            builder.setEntityType(
                    classifications[i].getCollection(),
                    classifications[i].getScore());
            if (classifications[i].getScore() > highestScoringResult.getScore()) {
                highestScoringResult = classifications[i];
            }
        }

        final Pair<Bundle, Bundle> languagesBundles = generateLanguageBundles(text, start, end);
        final Bundle textLanguagesBundle = languagesBundles.first;
        final Bundle foreignLanguageBundle = languagesBundles.second;
        builder.setForeignLanguageExtra(foreignLanguageBundle);

        boolean isPrimaryAction = true;
        final List<LabeledIntent> labeledIntents = mClassificationIntentFactory.create(
                mContext,
                classifiedText,
                foreignLanguageBundle != null,
                referenceTime,
                highestScoringResult);
        final LabeledIntent.TitleChooser titleChooser =
                (labeledIntent, resolveInfo) -> labeledIntent.titleWithoutEntity;

        for (LabeledIntent labeledIntent : labeledIntents) {
            final LabeledIntent.Result result =
                    labeledIntent.resolve(mContext, titleChooser, textLanguagesBundle);
            if (result == null) {
                continue;
            }

            final Intent intent = result.resolvedIntent;
            final RemoteAction action = result.remoteAction;
            if (isPrimaryAction) {
                // For O backwards compatibility, the first RemoteAction is also written to the
                // legacy API fields.
                builder.setIcon(action.getIcon().loadDrawable(mContext));
                builder.setLabel(action.getTitle().toString());
                builder.setIntent(intent);
                builder.setOnClickListener(TextClassification.createIntentOnClickListener(
                        TextClassification.createPendingIntent(
                                mContext, intent, labeledIntent.requestCode)));
                isPrimaryAction = false;
            }
            builder.addAction(action, intent);
        }

        return builder.setId(createId(text, start, end)).build();
    }

    /**
     * Returns a bundle pair with language detection information for extras.
     * <p>
     * Pair.first = textLanguagesBundle - A bundle containing information about all detected
     * languages in the text. May be null if language detection fails or is disabled. This is
     * typically expected to be added to a textClassifier generated remote action intent.
     * See {@link ExtrasUtils#putTextLanguagesExtra(Bundle, Bundle)}.
     * See {@link ExtrasUtils#getTopLanguage(Intent)}.
     * <p>
     * Pair.second = foreignLanguageBundle - A bundle with the language and confidence score if the
     * system finds the text to be in a foreign language. Otherwise is null.
     * See {@link TextClassification.Builder#setForeignLanguageExtra(Bundle)}.
     *
     * @param context the context of the text to detect languages for
     * @param start the start index of the text
     * @param end the end index of the text
     */
    // TODO: Revisit this algorithm.
    // TODO: Consider making this public API.
    private Pair<Bundle, Bundle> generateLanguageBundles(String context, int start, int end) {
        if (!mSettings.isTranslateInClassificationEnabled()) {
            return null;
        }
        try {
            final float threshold = getLangIdThreshold();
            if (threshold < 0 || threshold > 1) {
                Log.w(LOG_TAG,
                        "[detectForeignLanguage] unexpected threshold is found: " + threshold);
                return Pair.create(null, null);
            }

            final EntityConfidence languageScores = detectLanguages(context, start, end);
            if (languageScores.getEntities().isEmpty()) {
                return Pair.create(null, null);
            }

            final Bundle textLanguagesBundle = new Bundle();
            ExtrasUtils.putTopLanguageScores(textLanguagesBundle, languageScores);

            final String language = languageScores.getEntities().get(0);
            final float score = languageScores.getConfidenceScore(language);
            if (score < threshold) {
                return Pair.create(textLanguagesBundle, null);
            }

            Log.v(LOG_TAG, String.format(
                    Locale.US, "Language detected: <%s:%.2f>", language, score));

            final Locale detected = new Locale(language);
            final LocaleList deviceLocales = LocaleList.getDefault();
            final int size = deviceLocales.size();
            for (int i = 0; i < size; i++) {
                if (deviceLocales.get(i).getLanguage().equals(detected.getLanguage())) {
                    return Pair.create(textLanguagesBundle, null);
                }
            }
            final Bundle foreignLanguageBundle = ExtrasUtils.createForeignLanguageExtra(
                    detected.getLanguage(), score, getLangIdImpl().getVersion());
            return Pair.create(textLanguagesBundle, foreignLanguageBundle);
        } catch (Throwable t) {
            Log.e(LOG_TAG, "Error generating language bundles.", t);
        }
        return Pair.create(null, null);
    }

    /**
     * Detect the language of a piece of text by taking surrounding text into consideration.
     *
     * @param text text providing context for the text for which its language is to be detected
     * @param start the start index of the text to detect its language
     * @param end the end index of the text to detect its language
     */
    // TODO: Revisit this algorithm.
    private EntityConfidence detectLanguages(String text, int start, int end)
            throws FileNotFoundException {
        Preconditions.checkArgument(start >= 0);
        Preconditions.checkArgument(end <= text.length());
        Preconditions.checkArgument(start <= end);

        final float[] langIdContextSettings = mSettings.getLangIdContextSettings();
        // The minimum size of text to prefer for detection.
        final int minimumTextSize = (int) langIdContextSettings[0];
        // For reducing the score when text is less than the preferred size.
        final float penalizeRatio = langIdContextSettings[1];
        // Original detection score to surrounding text detection score ratios.
        final float subjectTextScoreRatio = langIdContextSettings[2];
        final float moreTextScoreRatio = 1f - subjectTextScoreRatio;
        Log.v(LOG_TAG,
                String.format(Locale.US, "LangIdContextSettings: "
                        + "minimumTextSize=%d, penalizeRatio=%.2f, "
                        + "subjectTextScoreRatio=%.2f, moreTextScoreRatio=%.2f",
                        minimumTextSize, penalizeRatio, subjectTextScoreRatio, moreTextScoreRatio));

        if (end - start < minimumTextSize && penalizeRatio <= 0) {
            return new EntityConfidence(Collections.emptyMap());
        }

        final String subject = text.substring(start, end);
        final EntityConfidence scores = detectLanguages(subject);

        if (subject.length() >= minimumTextSize
                || subject.length() == text.length()
                || subjectTextScoreRatio * penalizeRatio >= 1) {
            return scores;
        }

        final EntityConfidence moreTextScores;
        if (moreTextScoreRatio >= 0) {
            // Attempt to grow the detection text to be at least minimumTextSize long.
            final String moreText = Utils.getSubString(text, start, end, minimumTextSize);
            moreTextScores = detectLanguages(moreText);
        } else {
            moreTextScores = new EntityConfidence(Collections.emptyMap());
        }

        // Combine the original detection scores with the those returned after including more text.
        final Map<String, Float> newScores = new ArrayMap<>();
        final Set<String> languages = new ArraySet<>();
        languages.addAll(scores.getEntities());
        languages.addAll(moreTextScores.getEntities());
        for (String language : languages) {
            final float score = (subjectTextScoreRatio * scores.getConfidenceScore(language)
                    + moreTextScoreRatio * moreTextScores.getConfidenceScore(language))
                    * penalizeRatio;
            newScores.put(language, score);
        }
        return new EntityConfidence(newScores);
    }

    /**
     * Detect languages for the specified text.
     */
    private EntityConfidence detectLanguages(String text) throws FileNotFoundException {
        final LangIdModel langId = getLangIdImpl();
        final LangIdModel.LanguageResult[] langResults = langId.detectLanguages(text);
        final Map<String, Float> languagesMap = new ArrayMap<>();
        for (LanguageResult langResult : langResults) {
            languagesMap.put(langResult.getLanguage(), langResult.getScore());
        }
        return new EntityConfidence(languagesMap);
    }

    private float getLangIdThreshold() {
        try {
            return mSettings.getLangIdThresholdOverride() >= 0
                    ? mSettings.getLangIdThresholdOverride()
                    : getLangIdImpl().getLangIdThreshold();
        } catch (FileNotFoundException e) {
            final float defaultThreshold = 0.5f;
            Log.v(LOG_TAG, "Using default foreign language threshold: " + defaultThreshold);
            return defaultThreshold;
        }
    }

    @Override
    public void dump(@NonNull IndentingPrintWriter printWriter) {
        synchronized (mLock) {
            printWriter.println("TextClassifierImpl:");
            printWriter.increaseIndent();
            printWriter.println("Annotator model file(s):");
            printWriter.increaseIndent();
            for (ModelFileManager.ModelFile modelFile :
                    mAnnotatorModelFileManager.listModelFiles()) {
                printWriter.println(modelFile.toString());
            }
            printWriter.decreaseIndent();
            printWriter.println("LangID model file(s):");
            printWriter.increaseIndent();
            for (ModelFileManager.ModelFile modelFile :
                    mLangIdModelFileManager.listModelFiles()) {
                printWriter.println(modelFile.toString());
            }
            printWriter.decreaseIndent();
            printWriter.println("Actions model file(s):");
            printWriter.increaseIndent();
            for (ModelFileManager.ModelFile modelFile :
                    mActionsModelFileManager.listModelFiles()) {
                printWriter.println(modelFile.toString());
            }
            printWriter.decreaseIndent();
            printWriter.printPair("mFallback", mFallback);
            printWriter.decreaseIndent();
            printWriter.println();
        }
    }

    /**
     * Closes the ParcelFileDescriptor, if non-null, and logs any errors that occur.
     */
    private static void maybeCloseAndLogError(@Nullable ParcelFileDescriptor fd) {
        if (fd == null) {
            return;
        }

        try {
            fd.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error closing file.", e);
        }
    }

    /**
     * Returns the locales string for the current resources configuration.
     */
    private String getResourceLocalesString() {
        try {
            return mContext.getResources().getConfiguration().getLocales().toLanguageTags();
        } catch (NullPointerException e) {
            // NPE is unexpected. Erring on the side of caution.
            return LocaleList.getDefault().toLanguageTags();
        }
    }
}

