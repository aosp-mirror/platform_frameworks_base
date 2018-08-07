/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import android.annotation.SystemService;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * This class provides access to the system search services.
 *
 * <p>In practice, you won't interact with this class directly, as search
 * services are provided through methods in {@link android.app.Activity Activity}
 * and the {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH}
 * {@link android.content.Intent Intent}.
 *
 * <p>
 * {@link Configuration#UI_MODE_TYPE_WATCH} does not support this system service.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using the search dialog and adding search
 * suggestions in your application, read the
 * <a href="{@docRoot}guide/topics/search/index.html">Search</a> developer guide.</p>
 * </div>
 */
@SystemService(Context.SEARCH_SERVICE)
public class SearchManager
        implements DialogInterface.OnDismissListener, DialogInterface.OnCancelListener {

    private static final boolean DBG = false;
    private static final String TAG = "SearchManager";

    /**
     * This is a shortcut definition for the default menu key to use for invoking search.
     *
     * See Menu.Item.setAlphabeticShortcut() for more information.
     */
    public final static char MENU_KEY = 's';

    /**
     * This is a shortcut definition for the default menu key to use for invoking search.
     *
     * See Menu.Item.setAlphabeticShortcut() for more information.
     */
    public final static int MENU_KEYCODE = KeyEvent.KEYCODE_S;

    /**
     * Intent extra data key: Use this key with
     * {@link android.content.Intent#getStringExtra
     *  content.Intent.getStringExtra()}
     * to obtain the query string from Intent.ACTION_SEARCH.
     */
    public final static String QUERY = "query";

    /**
     * Intent extra data key: Use this key with
     * {@link android.content.Intent#getStringExtra
     *  content.Intent.getStringExtra()}
     * to obtain the query string typed in by the user.
     * This may be different from the value of {@link #QUERY}
     * if the intent is the result of selecting a suggestion.
     * In that case, {@link #QUERY} will contain the value of
     * {@link #SUGGEST_COLUMN_QUERY} for the suggestion, and
     * {@link #USER_QUERY} will contain the string typed by the
     * user.
     */
    public final static String USER_QUERY = "user_query";

    /**
     * Intent extra data key: Use this key with Intent.ACTION_SEARCH and
     * {@link android.content.Intent#getBundleExtra
     *  content.Intent.getBundleExtra()}
     * to obtain any additional app-specific data that was inserted by the
     * activity that launched the search.
     */
    public final static String APP_DATA = "app_data";

    /**
     * Intent extra data key: Use {@link android.content.Intent#getBundleExtra
     * content.Intent.getBundleExtra(SEARCH_MODE)} to get the search mode used
     * to launch the intent.
     * The only current value for this is {@link #MODE_GLOBAL_SEARCH_SUGGESTION}.
     *
     * @hide
     */
    public final static String SEARCH_MODE = "search_mode";

    /**
     * Intent extra data key: Use this key with Intent.ACTION_SEARCH and
     * {@link android.content.Intent#getIntExtra content.Intent.getIntExtra()}
     * to obtain the keycode that the user used to trigger this query.  It will be zero if the
     * user simply pressed the "GO" button on the search UI.  This is primarily used in conjunction
     * with the keycode attribute in the actionkey element of your searchable.xml configuration
     * file.
     */
    public final static String ACTION_KEY = "action_key";

    /**
     * Intent extra data key: This key will be used for the extra populated by the
     * {@link #SUGGEST_COLUMN_INTENT_EXTRA_DATA} column.
     */
    public final static String EXTRA_DATA_KEY = "intent_extra_data_key";

    /**
     * Boolean extra data key for {@link #INTENT_ACTION_GLOBAL_SEARCH} intents. If {@code true},
     * the initial query should be selected when the global search activity is started, so
     * that the user can easily replace it with another query.
     */
    public final static String EXTRA_SELECT_QUERY = "select_query";

    /**
     * Boolean extra data key for {@link Intent#ACTION_WEB_SEARCH} intents.  If {@code true},
     * this search should open a new browser window, rather than using an existing one.
     */
    public final static String EXTRA_NEW_SEARCH = "new_search";

    /**
     * Extra data key for {@link Intent#ACTION_WEB_SEARCH}. If set, the value must be a
     * {@link PendingIntent}. The search activity handling the {@link Intent#ACTION_WEB_SEARCH}
     * intent will fill in and launch the pending intent. The data URI will be filled in with an
     * http or https URI, and {@link android.provider.Browser#EXTRA_HEADERS} may be filled in.
     */
    public static final String EXTRA_WEB_SEARCH_PENDINGINTENT = "web_search_pendingintent";

    /**
     * Boolean extra data key for a suggestion provider to return in {@link Cursor#getExtras} to
     * indicate that the search is not complete yet. This can be used by the search UI
     * to indicate that a search is in progress. The suggestion provider can return partial results
     * this way and send a change notification on the cursor when more results are available.
     */
    public final static String CURSOR_EXTRA_KEY_IN_PROGRESS = "in_progress";

    /**
     * Intent extra data key: Use this key with Intent.ACTION_SEARCH and
     * {@link android.content.Intent#getStringExtra content.Intent.getStringExtra()}
     * to obtain the action message that was defined for a particular search action key and/or
     * suggestion.  It will be null if the search was launched by typing "enter", touched the the
     * "GO" button, or other means not involving any action key.
     */
    public final static String ACTION_MSG = "action_msg";

    /**
     * Flag to specify that the entry can be used for query refinement, i.e., the query text
     * in the search field can be replaced with the text in this entry, when a query refinement
     * icon is clicked. The suggestion list should show such a clickable icon beside the entry.
     * <p>Use this flag as a bit-field for {@link #SUGGEST_COLUMN_FLAGS}.
     */
    public final static int FLAG_QUERY_REFINEMENT = 1 << 0;

    /**
     * Uri path for queried suggestions data.  This is the path that the search manager
     * will use when querying your content provider for suggestions data based on user input
     * (e.g. looking for partial matches).
     * Typically you'll use this with a URI matcher.
     */
    public final static String SUGGEST_URI_PATH_QUERY = "search_suggest_query";

    /**
     * MIME type for suggestions data.  You'll use this in your suggestions content provider
     * in the getType() function.
     */
    public final static String SUGGEST_MIME_TYPE =
            "vnd.android.cursor.dir/vnd.android.search.suggest";

    /**
     * Uri path for shortcut validation.  This is the path that the search manager will use when
     * querying your content provider to refresh a shortcutted suggestion result and to check if it
     * is still valid.  When asked, a source may return an up to date result, or no result.  No
     * result indicates the shortcut refers to a no longer valid sugggestion.
     *
     * @see #SUGGEST_COLUMN_SHORTCUT_ID
     */
    public final static String SUGGEST_URI_PATH_SHORTCUT = "search_suggest_shortcut";

    /**
     * MIME type for shortcut validation.  You'll use this in your suggestions content provider
     * in the getType() function.
     */
    public final static String SHORTCUT_MIME_TYPE =
            "vnd.android.cursor.item/vnd.android.search.suggest";

    /**
     * Column name for suggestions cursor.  <i>Unused - can be null or column can be omitted.</i>
     */
    public final static String SUGGEST_COLUMN_FORMAT = "suggest_format";
    /**
     * Column name for suggestions cursor.  <i>Required.</i>  This is the primary line of text that
     * will be presented to the user as the suggestion.
     */
    public final static String SUGGEST_COLUMN_TEXT_1 = "suggest_text_1";
    /**
     * Column name for suggestions cursor.  <i>Optional.</i>  If your cursor includes this column,
     *  then all suggestions will be provided in a two-line format.  The second line of text is in
     *  a much smaller appearance.
     */
    public final static String SUGGEST_COLUMN_TEXT_2 = "suggest_text_2";

    /**
     * Column name for suggestions cursor.  <i>Optional.</i> This is a URL that will be shown
     * as the second line of text instead of {@link #SUGGEST_COLUMN_TEXT_2}. This is a separate
     * column so that the search UI knows to display the text as a URL, e.g. by using a different
     * color. If this column is absent, or has the value {@code null},
     * {@link #SUGGEST_COLUMN_TEXT_2} will be used instead.
     */
    public final static String SUGGEST_COLUMN_TEXT_2_URL = "suggest_text_2_url";

    /**
     * Column name for suggestions cursor.  <i>Optional.</i>  If your cursor includes this column,
     *  then all suggestions will be provided in a format that includes space for two small icons,
     *  one at the left and one at the right of each suggestion.  The data in the column must
     *  be a resource ID of a drawable, or a URI in one of the following formats:
     *
     * <ul>
     * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
     * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})</li>
     * <li>file ({@link android.content.ContentResolver#SCHEME_FILE})</li>
     * </ul>
     *
     * See {@link android.content.ContentResolver#openAssetFileDescriptor(Uri, String)}
     * for more information on these schemes.
     */
    public final static String SUGGEST_COLUMN_ICON_1 = "suggest_icon_1";

    /**
     * Column name for suggestions cursor.  <i>Optional.</i>  If your cursor includes this column,
     *  then all suggestions will be provided in a format that includes space for two small icons,
     *  one at the left and one at the right of each suggestion.  The data in the column must
     *  be a resource ID of a drawable, or a URI in one of the following formats:
     *
     * <ul>
     * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
     * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})</li>
     * <li>file ({@link android.content.ContentResolver#SCHEME_FILE})</li>
     * </ul>
     *
     * See {@link android.content.ContentResolver#openAssetFileDescriptor(Uri, String)}
     * for more information on these schemes.
     */
    public final static String SUGGEST_COLUMN_ICON_2 = "suggest_icon_2";

    /**
     * Column name for suggestions cursor.  <i>Optional.</i>  If your cursor includes this column,
     * then the image will be displayed when forming the suggestion. The suggested dimension for
     * the image is 270x400 px for portrait mode and 400x225 px for landscape mode. The data in the
     * column must be a resource ID of a drawable, or a URI in one of the following formats:
     *
     * <ul>
     * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
     * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})</li>
     * <li>file ({@link android.content.ContentResolver#SCHEME_FILE})</li>
     * </ul>
     *
     * See {@link android.content.ContentResolver#openAssetFileDescriptor(Uri, String)}
     * for more information on these schemes.
     */
    public final static String SUGGEST_COLUMN_RESULT_CARD_IMAGE = "suggest_result_card_image";

    /**
     * Column name for suggestions cursor.  <i>Optional.</i>  If this column exists <i>and</i>
     * this element exists at the given row, this is the action that will be used when
     * forming the suggestion's intent.  If the element is not provided, the action will be taken
     * from the android:searchSuggestIntentAction field in your XML metadata.  <i>At least one of
     * these must be present for the suggestion to generate an intent.</i>  Note:  If your action is
     * the same for all suggestions, it is more efficient to specify it using XML metadata and omit
     * it from the cursor.
     */
    public final static String SUGGEST_COLUMN_INTENT_ACTION = "suggest_intent_action";

    /**
     * Column name for suggestions cursor.  <i>Optional.</i>  If this column exists <i>and</i>
     * this element exists at the given row, this is the data that will be used when
     * forming the suggestion's intent.  If the element is not provided, the data will be taken
     * from the android:searchSuggestIntentData field in your XML metadata.  If neither source
     * is provided, the Intent's data field will be null.  Note:  If your data is
     * the same for all suggestions, or can be described using a constant part and a specific ID,
     * it is more efficient to specify it using XML metadata and omit it from the cursor.
     */
    public final static String SUGGEST_COLUMN_INTENT_DATA = "suggest_intent_data";

    /**
     * Column name for suggestions cursor.  <i>Optional.</i>  If this column exists <i>and</i>
     * this element exists at the given row, this is the data that will be used when
     * forming the suggestion's intent. If not provided, the Intent's extra data field will be null.
     * This column allows suggestions to provide additional arbitrary data which will be included as
     * an extra under the key {@link #EXTRA_DATA_KEY}.
     */
    public final static String SUGGEST_COLUMN_INTENT_EXTRA_DATA = "suggest_intent_extra_data";

    /**
     * Column name for suggestions cursor.  <i>Optional.</i>  If this column exists <i>and</i>
     * this element exists at the given row, then "/" and this value will be appended to the data
     * field in the Intent.  This should only be used if the data field has already been set to an
     * appropriate base string.
     */
    public final static String SUGGEST_COLUMN_INTENT_DATA_ID = "suggest_intent_data_id";

    /**
     * Column name for suggestions cursor.  <i>Required if action is
     * {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH}, optional otherwise.</i>  If this
     * column exists <i>and</i> this element exists at the given row, this is the data that will be
     * used when forming the suggestion's query.
     */
    public final static String SUGGEST_COLUMN_QUERY = "suggest_intent_query";

    /**
     * Column name for suggestions cursor. <i>Optional.</i>  This column is used to indicate whether
     * a search suggestion should be stored as a shortcut, and whether it should be refreshed.  If
     * missing, the result will be stored as a shortcut and never validated.  If set to
     * {@link #SUGGEST_NEVER_MAKE_SHORTCUT}, the result will not be stored as a shortcut.
     * Otherwise, the shortcut id will be used to check back for an up to date suggestion using
     * {@link #SUGGEST_URI_PATH_SHORTCUT}.
     */
    public final static String SUGGEST_COLUMN_SHORTCUT_ID = "suggest_shortcut_id";

    /**
     * Column name for suggestions cursor. <i>Optional.</i> This column is used to specify
     * that a spinner should be shown in lieu of an icon2 while the shortcut of this suggestion
     * is being refreshed.
     */
    public final static String SUGGEST_COLUMN_SPINNER_WHILE_REFRESHING =
            "suggest_spinner_while_refreshing";

    /**
     * Column name for suggestions cursor. <i>Optional.</i>  If your content is media type, you
     * should provide this column so search app could understand more about your content. The data
     * in the column must specify the MIME type of the content.
     */
    public final static String SUGGEST_COLUMN_CONTENT_TYPE = "suggest_content_type";

    /**
     * Column name for suggestions cursor. <i>Optional.</i>  If your content is media type, you
     * should provide this column to specify whether your content is live media such as live video
     * or live audio. The value in the column is of integer type with value of either 0 indicating
     * non-live content or 1 indicating live content.
     */
    public final static String SUGGEST_COLUMN_IS_LIVE = "suggest_is_live";

    /**
     * Column name for suggestions cursor. <i>Optional.</i>  If your content is video, you should
     * provide this column to specify the number of vertical lines. The data in the column is of
     * integer type.
     */
    public final static String SUGGEST_COLUMN_VIDEO_WIDTH = "suggest_video_width";

    /**
     * Column name for suggestions cursor. <i>Optional.</i>  If your content is video, you should
     * provide this column to specify the number of horizontal lines. The data in the column is of
     * integer type.
     */
    public final static String SUGGEST_COLUMN_VIDEO_HEIGHT = "suggest_video_height";

    /**
     * Column name for suggestions cursor. <i>Optional.</i>  If your content contains audio, you
     * should provide this column to specify the audio channel configuration. The data in the
     * column is string with format like "channels.subchannels" such as "1.0" or "5.1".
     */
    public final static String SUGGEST_COLUMN_AUDIO_CHANNEL_CONFIG = "suggest_audio_channel_config";

    /**
     * Column name for suggestions cursor. <i>Optional.</i>  If your content is purchasable, you
     * should provide this column to specify the displayable string representation of the purchase
     * price of your content including the currency and the amount. If it's free, you should
     * provide localized string to specify that it's free. This column can be omitted if the content
     * is not applicable to purchase.
     */
    public final static String SUGGEST_COLUMN_PURCHASE_PRICE = "suggest_purchase_price";

    /**
     * Column name for suggestions cursor. <i>Optional.</i>  If your content is rentable, you
     * should provide this column to specify the displayable string representation of the rental
     * price of your content including the currency and the amount. If it's free, you should
     * provide localized string to specify that it's free. This column can be ommitted if the
     * content is not applicable to rent.
     */
    public final static String SUGGEST_COLUMN_RENTAL_PRICE = "suggest_rental_price";

    /**
     * Column name for suggestions cursor. <i>Optional.</i>  If your content has a rating, you
     * should provide this column to specify the rating style of your content. The data in the
     * column must be one of the constant values specified in {@link android.media.Rating}
     */
    public final static String SUGGEST_COLUMN_RATING_STYLE = "suggest_rating_style";

    /**
     * Column name for suggestions cursor. <i>Optional.</i>  If your content has a rating, you
     * should provide this column to specify the rating score of your content. The data in the
     * column is of float type. See {@link android.media.Rating} about valid rating scores for each
     * rating style.
     */
    public final static String SUGGEST_COLUMN_RATING_SCORE = "suggest_rating_score";

    /**
     * Column name for suggestions cursor. <i>Optional.</i>  If your content is video or audio and
     * has a known production year, you should provide this column to specify the production year
     * of your content. The data in the column is of integer type.
     */
    public final static String SUGGEST_COLUMN_PRODUCTION_YEAR = "suggest_production_year";

    /**
     * Column name for suggestions cursor. <i>Optional.</i>  If your content is video or audio, you
     * should provide this column to specify the duration of your content in milliseconds. The data
     * in the column is of long type.
     */
    public final static String SUGGEST_COLUMN_DURATION = "suggest_duration";

    /**
     * Column name for suggestions cursor. <i>Optional.</i> This column is used to specify
     * additional flags per item. Multiple flags can be specified.
     * <p>
     * Must be one of {@link #FLAG_QUERY_REFINEMENT} or 0 to indicate no flags.
     * </p>
     */
    public final static String SUGGEST_COLUMN_FLAGS = "suggest_flags";

    /**
     * Column name for suggestions cursor. <i>Optional.</i> This column may be
     * used to specify the time in {@link System#currentTimeMillis
     * System.currentTImeMillis()} (wall time in UTC) when an item was last
     * accessed within the results-providing application. If set, this may be
     * used to show more-recently-used items first.
     */
    public final static String SUGGEST_COLUMN_LAST_ACCESS_HINT = "suggest_last_access_hint";

    /**
     * Column value for suggestion column {@link #SUGGEST_COLUMN_SHORTCUT_ID} when a suggestion
     * should not be stored as a shortcut in global search.
     */
    public final static String SUGGEST_NEVER_MAKE_SHORTCUT = "_-1";

    /**
     * Query parameter added to suggestion queries to limit the number of suggestions returned.
     * This limit is only advisory and suggestion providers may chose to ignore it.
     */
    public final static String SUGGEST_PARAMETER_LIMIT = "limit";

    /**
     * Intent action for starting the global search activity.
     * The global search provider should handle this intent.
     *
     * Supported extra data keys: {@link #QUERY},
     * {@link #EXTRA_SELECT_QUERY},
     * {@link #APP_DATA}.
     */
    public final static String INTENT_ACTION_GLOBAL_SEARCH
            = "android.search.action.GLOBAL_SEARCH";

    /**
     * Intent action for starting the global search settings activity.
     * The global search provider should handle this intent.
     */
    public final static String INTENT_ACTION_SEARCH_SETTINGS
            = "android.search.action.SEARCH_SETTINGS";

    /**
     * Intent action for starting a web search provider's settings activity.
     * Web search providers should handle this intent if they have provider-specific
     * settings to implement.
     */
    public final static String INTENT_ACTION_WEB_SEARCH_SETTINGS
            = "android.search.action.WEB_SEARCH_SETTINGS";

    /**
     * Intent action broadcasted to inform that the searchables list or default have changed.
     * Components should handle this intent if they cache any searchable data and wish to stay
     * up to date on changes.
     */
    public final static String INTENT_ACTION_SEARCHABLES_CHANGED
            = "android.search.action.SEARCHABLES_CHANGED";

    /**
     * Intent action to be broadcast to inform that the global search provider
     * has changed.
     */
    public final static String INTENT_GLOBAL_SEARCH_ACTIVITY_CHANGED
            = "android.search.action.GLOBAL_SEARCH_ACTIVITY_CHANGED";

    /**
     * Intent action broadcasted to inform that the search settings have changed in some way.
     * Either searchables have been enabled or disabled, or a different web search provider
     * has been chosen.
     */
    public final static String INTENT_ACTION_SEARCH_SETTINGS_CHANGED
            = "android.search.action.SETTINGS_CHANGED";

    /**
     * This means that context is voice, and therefore the SearchDialog should
     * continue showing the microphone until the user indicates that he/she does
     * not want to re-speak (e.g. by typing).
     *
     * @hide
     */
    public final static String CONTEXT_IS_VOICE = "android.search.CONTEXT_IS_VOICE";

    /**
     * This means that the voice icon should not be shown at all, because the
     * current search engine does not support voice search.
     * @hide
     */
    public final static String DISABLE_VOICE_SEARCH
            = "android.search.DISABLE_VOICE_SEARCH";

    /**
     * Reference to the shared system search service.
     */
    private final ISearchManager mService;

    private final Context mContext;

    // package private since they are used by the inner class SearchManagerCallback
    /* package */ final Handler mHandler;
    /* package */ OnDismissListener mDismissListener = null;
    /* package */ OnCancelListener mCancelListener = null;

    private SearchDialog mSearchDialog;

    /*package*/ SearchManager(Context context, Handler handler) throws ServiceNotFoundException {
        mContext = context;
        mHandler = handler;
        mService = ISearchManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.SEARCH_SERVICE));
    }

    /**
     * Launch search UI.
     *
     * <p>The search manager will open a search widget in an overlapping
     * window, and the underlying activity may be obscured.  The search
     * entry state will remain in effect until one of the following events:
     * <ul>
     * <li>The user completes the search.  In most cases this will launch
     * a search intent.</li>
     * <li>The user uses the back, home, or other keys to exit the search.</li>
     * <li>The application calls the {@link #stopSearch}
     * method, which will hide the search window and return focus to the
     * activity from which it was launched.</li>
     *
     * <p>Most applications will <i>not</i> use this interface to invoke search.
     * The primary method for invoking search is to call
     * {@link android.app.Activity#onSearchRequested Activity.onSearchRequested()} or
     * {@link android.app.Activity#startSearch Activity.startSearch()}.
     *
     * @param initialQuery A search string can be pre-entered here, but this
     * is typically null or empty.
     * @param selectInitialQuery If true, the intial query will be preselected, which means that
     * any further typing will replace it.  This is useful for cases where an entire pre-formed
     * query is being inserted.  If false, the selection point will be placed at the end of the
     * inserted query.  This is useful when the inserted query is text that the user entered,
     * and the user would expect to be able to keep typing.  <i>This parameter is only meaningful
     * if initialQuery is a non-empty string.</i>
     * @param launchActivity The ComponentName of the activity that has launched this search.
     * @param appSearchData An application can insert application-specific
     * context here, in order to improve quality or specificity of its own
     * searches.  This data will be returned with SEARCH intent(s).  Null if
     * no extra data is required.
     * @param globalSearch If false, this will only launch the search that has been specifically
     * defined by the application (which is usually defined as a local search).  If no default
     * search is defined in the current application or activity, global search will be launched.
     * If true, this will always launch a platform-global (e.g. web-based) search instead.
     *
     * @see android.app.Activity#onSearchRequested
     * @see #stopSearch
     */
    public void startSearch(String initialQuery,
                            boolean selectInitialQuery,
                            ComponentName launchActivity,
                            Bundle appSearchData,
                            boolean globalSearch) {
        startSearch(initialQuery, selectInitialQuery, launchActivity,
                appSearchData, globalSearch, null);
    }

    /**
     * As {@link #startSearch(String, boolean, ComponentName, Bundle, boolean)} but including
     * source bounds for the global search intent.
     *
     * @hide
     */
    public void startSearch(String initialQuery,
                            boolean selectInitialQuery,
                            ComponentName launchActivity,
                            Bundle appSearchData,
                            boolean globalSearch,
                            Rect sourceBounds) {
        if (globalSearch) {
            startGlobalSearch(initialQuery, selectInitialQuery, appSearchData, sourceBounds);
            return;
        }

        final UiModeManager uiModeManager = mContext.getSystemService(UiModeManager.class);
        // Don't show search dialog on televisions.
        if (uiModeManager.getCurrentModeType() != Configuration.UI_MODE_TYPE_TELEVISION) {
            ensureSearchDialog();

            mSearchDialog.show(initialQuery, selectInitialQuery, launchActivity, appSearchData);
        }
    }

    private void ensureSearchDialog() {
        if (mSearchDialog == null) {
            mSearchDialog = new SearchDialog(mContext, this);
            mSearchDialog.setOnCancelListener(this);
            mSearchDialog.setOnDismissListener(this);
        }
    }

    /**
     * Starts the global search activity.
     */
    /* package */ void startGlobalSearch(String initialQuery, boolean selectInitialQuery,
            Bundle appSearchData, Rect sourceBounds) {
        ComponentName globalSearchActivity = getGlobalSearchActivity();
        if (globalSearchActivity == null) {
            Log.w(TAG, "No global search activity found.");
            return;
        }
        Intent intent = new Intent(INTENT_ACTION_GLOBAL_SEARCH);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setComponent(globalSearchActivity);
        // Make sure that we have a Bundle to put source in
        if (appSearchData == null) {
            appSearchData = new Bundle();
        } else {
            appSearchData = new Bundle(appSearchData);
        }
        // Set source to package name of app that starts global search, if not set already.
        if (!appSearchData.containsKey("source")) {
            appSearchData.putString("source", mContext.getPackageName());
        }
        intent.putExtra(APP_DATA, appSearchData);
        if (!TextUtils.isEmpty(initialQuery)) {
            intent.putExtra(QUERY, initialQuery);
        }
        if (selectInitialQuery) {
            intent.putExtra(EXTRA_SELECT_QUERY, selectInitialQuery);
        }
        intent.setSourceBounds(sourceBounds);
        try {
            if (DBG) Log.d(TAG, "Starting global search: " + intent.toUri(0));
            mContext.startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            Log.e(TAG, "Global search activity not found: " + globalSearchActivity);
        }
    }

    /**
     * Returns a list of installed apps that handle the global search
     * intent.
     *
     * @hide
     */
    public List<ResolveInfo> getGlobalSearchActivities() {
        try {
            return mService.getGlobalSearchActivities();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the name of the global search activity.
     */
    public ComponentName getGlobalSearchActivity() {
        try {
            return mService.getGlobalSearchActivity();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the name of the web search activity.
     *
     * @return The name of the default activity for web searches. This activity
     *         can be used to get web search suggestions. Returns {@code null} if
     *         there is no default web search activity.
     *
     * @hide
     */
    public ComponentName getWebSearchActivity() {
        try {
            return mService.getWebSearchActivity();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Similar to {@link #startSearch} but actually fires off the search query after invoking
     * the search dialog.  Made available for testing purposes.
     *
     * @param query The query to trigger.  If empty, request will be ignored.
     * @param launchActivity The ComponentName of the activity that has launched this search.
     * @param appSearchData An application can insert application-specific
     * context here, in order to improve quality or specificity of its own
     * searches.  This data will be returned with SEARCH intent(s).  Null if
     * no extra data is required.
     *
     * @see #startSearch
     */
    public void triggerSearch(String query,
                              ComponentName launchActivity,
                              Bundle appSearchData) {
        if (query == null || TextUtils.getTrimmedLength(query) == 0) {
            Log.w(TAG, "triggerSearch called with empty query, ignoring.");
            return;
        }
        startSearch(query, false, launchActivity, appSearchData, false);
        mSearchDialog.launchQuerySearch();
    }

    /**
     * Terminate search UI.
     *
     * <p>Typically the user will terminate the search UI by launching a
     * search or by canceling.  This function allows the underlying application
     * or activity to cancel the search prematurely (for any reason).
     *
     * <p>This function can be safely called at any time (even if no search is active.)
     *
     * <p>{@link Configuration#UI_MODE_TYPE_TELEVISION} does not support this method.
     *
     * @see #startSearch
     */
    public void stopSearch() {
        if (mSearchDialog != null) {
            mSearchDialog.cancel();
        }
    }

    /**
     * Determine if the Search UI is currently displayed.
     *
     * This is provided primarily for application test purposes.
     *
     * @return Returns true if the search UI is currently displayed.
     *
     * @hide
     */
    public boolean isVisible() {
        return mSearchDialog == null? false : mSearchDialog.isShowing();
    }

    /**
     * See {@link SearchManager#setOnDismissListener} for configuring your activity to monitor
     * search UI state.
     */
    public interface OnDismissListener {
        /**
         * This method will be called when the search UI is dismissed. To make use of it, you must
         * implement this method in your activity, and call
         * {@link SearchManager#setOnDismissListener} to register it.
         */
        public void onDismiss();
    }

    /**
     * See {@link SearchManager#setOnCancelListener} for configuring your activity to monitor
     * search UI state.
     */
    public interface OnCancelListener {
        /**
         * This method will be called when the search UI is canceled. To make use if it, you must
         * implement this method in your activity, and call
         * {@link SearchManager#setOnCancelListener} to register it.
         */
        public void onCancel();
    }

    /**
     * Set or clear the callback that will be invoked whenever the search UI is dismissed.
     *
     * <p>{@link Configuration#UI_MODE_TYPE_TELEVISION} does not support this method.
     *
     * @param listener The {@link OnDismissListener} to use, or null.
     */
    public void setOnDismissListener(final OnDismissListener listener) {
        mDismissListener = listener;
    }

    /**
     * Set or clear the callback that will be invoked whenever the search UI is canceled.
     *
     * <p>{@link Configuration#UI_MODE_TYPE_TELEVISION} does not support this method.
     *
     * @param listener The {@link OnCancelListener} to use, or null.
     */
    public void setOnCancelListener(OnCancelListener listener) {
        mCancelListener = listener;
    }

    /**
     * @deprecated This method is an obsolete internal implementation detail. Do not use.
     */
    @Deprecated
    public void onCancel(DialogInterface dialog) {
        if (mCancelListener != null) {
            mCancelListener.onCancel();
        }
    }

    /**
     * @deprecated This method is an obsolete internal implementation detail. Do not use.
     */
    @Deprecated
    public void onDismiss(DialogInterface dialog) {
        if (mDismissListener != null) {
            mDismissListener.onDismiss();
        }
    }

    /**
     * Gets information about a searchable activity.
     *
     * @param componentName The activity to get searchable information for.
     * @return Searchable information, or <code>null</code> if the activity does not
     *         exist, or is not searchable.
     */
    public SearchableInfo getSearchableInfo(ComponentName componentName) {
        try {
            return mService.getSearchableInfo(componentName);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets a cursor with search suggestions.
     *
     * @param searchable Information about how to get the suggestions.
     * @param query The search text entered (so far).
     * @return a cursor with suggestions, or <code>null</null> the suggestion query failed.
     *
     * @hide because SearchableInfo is not part of the API.
     */
    public Cursor getSuggestions(SearchableInfo searchable, String query) {
        return getSuggestions(searchable, query, -1);
    }

    /**
     * Gets a cursor with search suggestions.
     *
     * @param searchable Information about how to get the suggestions.
     * @param query The search text entered (so far).
     * @param limit The query limit to pass to the suggestion provider. This is advisory,
     *        the returned cursor may contain more rows. Pass {@code -1} for no limit.
     * @return a cursor with suggestions, or <code>null</null> the suggestion query failed.
     *
     * @hide because SearchableInfo is not part of the API.
     */
    public Cursor getSuggestions(SearchableInfo searchable, String query, int limit) {
        if (searchable == null) {
            return null;
        }

        String authority = searchable.getSuggestAuthority();
        if (authority == null) {
            return null;
        }

        Uri.Builder uriBuilder = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority)
                .query("")  // TODO: Remove, workaround for a bug in Uri.writeToParcel()
                .fragment("");  // TODO: Remove, workaround for a bug in Uri.writeToParcel()

        // if content path provided, insert it now
        final String contentPath = searchable.getSuggestPath();
        if (contentPath != null) {
            uriBuilder.appendEncodedPath(contentPath);
        }

        // append standard suggestion query path
        uriBuilder.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY);

        // get the query selection, may be null
        String selection = searchable.getSuggestSelection();
        // inject query, either as selection args or inline
        String[] selArgs = null;
        if (selection != null) {    // use selection if provided
            selArgs = new String[] { query };
        } else {                    // no selection, use REST pattern
            uriBuilder.appendPath(query);
        }

        if (limit > 0) {
            uriBuilder.appendQueryParameter(SUGGEST_PARAMETER_LIMIT, String.valueOf(limit));
        }

        Uri uri = uriBuilder.build();

        // finally, make the query
        return mContext.getContentResolver().query(uri, null, selection, selArgs, null);
    }

    /**
     * Returns a list of the searchable activities that can be included in global search.
     *
     * @return a list containing searchable information for all searchable activities
     *         that have the <code>android:includeInGlobalSearch</code> attribute set
     *         in their searchable meta-data.
     */
    public List<SearchableInfo> getSearchablesInGlobalSearch() {
        try {
            return mService.getSearchablesInGlobalSearch();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets an intent for launching installed assistant activity, or null if not available.
     * @return The assist intent.
     *
     * @hide
     */
    public Intent getAssistIntent(boolean inclContext) {
        try {
            Intent intent = new Intent(Intent.ACTION_ASSIST);
            if (inclContext) {
                IActivityManager am = ActivityManager.getService();
                Bundle extras = am.getAssistContextExtras(ActivityManager.ASSIST_CONTEXT_BASIC);
                if (extras != null) {
                    intent.replaceExtras(extras);
                }
            }
            return intent;
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Starts the assistant.
     *
     * @param args the args to pass to the assistant
     *
     * @hide
     */
    public void launchAssist(Bundle args) {
        try {
            if (mService == null) {
                return;
            }
            mService.launchAssist(args);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Starts the legacy assistant (i.e. the {@link Intent#ACTION_ASSIST}).
     *
     * @param args the args to pass to the assistant
     *
     * @hide
     */
    public boolean launchLegacyAssist(String hint, int userHandle, Bundle args) {
        try {
            if (mService == null) {
                return false;
            }
            return mService.launchLegacyAssist(hint, userHandle, args);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }
}
