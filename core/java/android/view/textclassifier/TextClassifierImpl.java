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
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.icu.util.ULocale;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import com.google.android.textclassifier.ActionsSuggestionsModel;
import com.google.android.textclassifier.AnnotatorModel;
import com.google.android.textclassifier.LangIdModel;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
    @GuardedBy("mLock") // Do not access outside this lock.
    private ModelFileManager.ModelFile mAnnotatorModelInUse;
    @GuardedBy("mLock") // Do not access outside this lock.
    private AnnotatorModel mAnnotatorImpl;
    @GuardedBy("mLock") // Do not access outside this lock.
    private LangIdModel mLangIdImpl;
    @GuardedBy("mLock") // Do not access outside this lock.
    private ModelFileManager.ModelFile mActionModelInUse;
    @GuardedBy("mLock") // Do not access outside this lock.
    private ActionsSuggestionsModel mActionsImpl;

    private final SelectionSessionLogger mSessionLogger = new SelectionSessionLogger();
    private final TextClassifierEventTronLogger mTextClassifierEventTronLogger =
            new TextClassifierEventTronLogger();

    private final TextClassificationConstants mSettings;

    private final ModelFileManager mAnnotatorModelFileManager;
    private final ModelFileManager mLangIdModelFileManager;
    private final ModelFileManager mActionsModelFileManager;

    private final IntentFactory mIntentFactory;
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
        mIntentFactory = mSettings.isTemplateIntentFactoryEnabled()
                ? new TemplateClassificationIntentFactory(
                mTemplateIntentFactory, new LegacyIntentFactory())
                : new LegacyIntentFactory();
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
                            new AnnotatorModel.SelectionOptions(localesString));
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
                                            localesString),
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
                final ZonedDateTime refTime = request.getReferenceTime() != null
                        ? request.getReferenceTime() : ZonedDateTime.now();
                final AnnotatorModel.ClassificationResult[] results =
                        getAnnotatorImpl(request.getDefaultLocales())
                                .classifyText(
                                        string, request.getStartIndex(), request.getEndIndex(),
                                        new AnnotatorModel.ClassificationOptions(
                                                refTime.toInstant().toEpochMilli(),
                                                refTime.getZone().getId(),
                                                localesString),
                                        mContext,
                                        // TODO: Pass the locale list once it is supported in
                                        //  native side.
                                        LocaleList.getDefault().get(0).toLanguageTag()
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
            final AnnotatorModel annotatorImpl =
                    getAnnotatorImpl(request.getDefaultLocales());
            final AnnotatorModel.AnnotatedSpan[] annotations =
                    annotatorImpl.annotate(
                            textString,
                            new AnnotatorModel.AnnotationOptions(
                                    refTime.toInstant().toEpochMilli(),
                                    refTime.getZone().getId(),
                                    concatenateLocales(request.getDefaultLocales())));
            for (AnnotatorModel.AnnotatedSpan span : annotations) {
                final AnnotatorModel.ClassificationResult[] results =
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
                    ActionsSuggestionsHelper.toNativeMessages(request.getConversation(),
                            this::detectLanguageTagsFromText);
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
                            // TODO: Pass the locale list once it is supported in native side.
                            LocaleList.getDefault().get(0).toLanguageTag());
            return createConversationActionResult(request, nativeSuggestions);
        } catch (Throwable t) {
            // Avoid throwing from this method. Log the error.
            Log.e(LOG_TAG, "Error suggesting conversation actions.", t);
        }
        return mFallback.suggestConversationActions(request);
    }

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
            List<LabeledIntent> labeledIntents =
                    mTemplateIntentFactory.create(nativeSuggestion.getRemoteActionTemplates());
            RemoteAction remoteAction = null;
            // Given that we only support implicit intent here, we should expect there is just one
            // intent for each action type.
            if (!labeledIntents.isEmpty()) {
                remoteAction = labeledIntents.get(0).asRemoteAction(mContext);
            }
            conversationActions.add(
                    new ConversationAction.Builder(actionType)
                            .setConfidenceScore(nativeSuggestion.getScore())
                            .setTextReply(nativeSuggestion.getResponseText())
                            .setAction(remoteAction)
                            .build());
        }
        String resultId = ActionsSuggestionsHelper.createResultId(
                mContext,
                request.getConversation(),
                mActionModelInUse.getVersion(),
                mActionModelInUse.getSupportedLocales());
        return new ConversationActions(conversationActions, resultId);
    }

    @Nullable
    private String detectLanguageTagsFromText(CharSequence text) {
        TextLanguage.Request request = new TextLanguage.Request.Builder(text).build();
        TextLanguage textLanguage = detectLanguage(request);
        int localeHypothesisCount = textLanguage.getLocaleHypothesisCount();
        List<String> languageTags = new ArrayList<>();
        // TODO: Reconsider this and probably make the score threshold configurable.
        for (int i = 0; i < localeHypothesisCount; i++) {
            ULocale locale = textLanguage.getLocale(i);
            if (textLanguage.getConfidenceScore(locale) < 0.5) {
                break;
            }
            languageTags.add(locale.toLanguageTag());
        }
        if (languageTags.isEmpty()) {
            return LocaleList.getDefault().toLanguageTags();
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
            if (mLangIdImpl == null) {
                final ModelFileManager.ModelFile bestModel =
                        mLangIdModelFileManager.findBestModelFile(null);
                if (bestModel == null) {
                    throw new FileNotFoundException("No LangID model is found");
                }
                final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                        new File(bestModel.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
                try {
                    if (pfd != null) {
                        mLangIdImpl = new LangIdModel(pfd.getFd());
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
            if (mActionsImpl == null) {
                // TODO: Use LangID to determine the locale we should use here?
                final ModelFileManager.ModelFile bestModel =
                        mActionsModelFileManager.findBestModelFile(LocaleList.getDefault());
                if (bestModel == null) {
                    return null;
                }
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

        final float foreignTextThreshold = mSettings.getLangIdThresholdOverride() >= 0
                ? mSettings.getLangIdThresholdOverride()
                : 0.5f /* TODO: Load this from the langId model. */;
        final Bundle foreignLanguageBundle =
                detectForeignLanguage(classifiedText, foreignTextThreshold);
        builder.setForeignLanguageExtra(foreignLanguageBundle);

        boolean isPrimaryAction = true;
        final ArrayList<Intent> sourceIntents = new ArrayList<>();
        List<LabeledIntent> labeledIntents = mIntentFactory.create(
                mContext,
                classifiedText,
                foreignLanguageBundle != null,
                referenceTime,
                highestScoringResult);
        for (LabeledIntent labeledIntent : labeledIntents) {
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
            builder.addAction(action, labeledIntent.getIntent());
        }

        return builder.setId(createId(text, start, end)).build();
    }

    /**
     * Returns a bundle with the language and confidence score if it finds the text to be
     * in a foreign language. Otherwise returns null.
     */
    @Nullable
    private Bundle detectForeignLanguage(String text, float threshold) {
        if (threshold > 1) {
            return null;
        }

        // TODO: Revisit this algorithm.
        try {
            final LangIdModel langId = getLangIdImpl();
            final LangIdModel.LanguageResult[] langResults = langId.detectLanguages(text);
            if (langResults.length <= 0) {
                return null;
            }

            LangIdModel.LanguageResult highestScoringResult = langResults[0];
            for (int i = 1; i < langResults.length; i++) {
                if (langResults[i].getScore() > highestScoringResult.getScore()) {
                    highestScoringResult = langResults[i];
                }
            }
            if (highestScoringResult.getScore() < threshold) {
                return null;
            }
            // TODO: Remove
            Log.d(LOG_TAG, String.format("Language detected: <%s:%s>",
                    highestScoringResult.getLanguage(), highestScoringResult.getScore()));

            final Locale detected = new Locale(highestScoringResult.getLanguage());
            final LocaleList deviceLocales = LocaleList.getDefault();
            final int size = deviceLocales.size();
            for (int i = 0; i < size; i++) {
                if (deviceLocales.get(i).getLanguage().equals(detected.getLanguage())) {
                    return null;
                }
            }
            return ExtrasUtils.createForeignLanguageExtra(
                    detected.getLanguage(), highestScoringResult.getScore(), langId.getVersion());
        } catch (Throwable t) {
            Log.e(LOG_TAG, "Error detecting foreign text. Ignored.", t);
        }
        return null;
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
     * Helper class to store the information from which RemoteActions are built.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public static final class LabeledIntent {

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

        @VisibleForTesting
        public String getTitle() {
            return mTitle;
        }

        @VisibleForTesting
        public String getDescription() {
            return mDescription;
        }

        @VisibleForTesting
        public Intent getIntent() {
            return mIntent;
        }

        @VisibleForTesting
        public int getRequestCode() {
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
}

