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

    /**
     * This is the opposite signal to {@link #dismissSuggestion}, indicating a suggestion has been
     * launched.
     */
    void launchSuggestion(in Suggestion suggestion) = 3;
}