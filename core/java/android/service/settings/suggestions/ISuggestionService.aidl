package android.service.settings.suggestions;

import android.service.settings.suggestions.Suggestion;

import java.util.List;

/** @hide */
interface ISuggestionService {

    /**
     * Return all available suggestions.
     */
    List<Suggestion> getSuggestions() = 1;

    /**
     * Dismiss a suggestion. The suggestion will not be included in future {@link #getSuggestions)
     * calls.
     */
    void dismissSuggestion(in Suggestion suggestion) = 2;
}