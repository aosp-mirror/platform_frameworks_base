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

import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.ServiceManager;
import android.view.KeyEvent;

/**
 * This class provides access to the system search services.
 * 
 * <p>In practice, you won't interact with this class directly, as search
 * services are provided through methods in {@link android.app.Activity Activity}
 * methods and the the {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH}
 * {@link android.content.Intent Intent}.  This class does provide a basic
 * overview of search services and how to integrate them with your activities.
 * If you do require direct access to the Search Manager, do not instantiate 
 * this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService
 * context.getSystemService(Context.SEARCH_SERVICE)}.
 * 
 * <p>Topics covered here:
 * <ol>
 * <li><a href="#DeveloperGuide">Developer Guide</a>
 * <li><a href="#HowSearchIsInvoked">How Search Is Invoked</a>
 * <li><a href="#QuerySearchApplications">Query-Search Applications</a>
 * <li><a href="#FilterSearchApplications">Filter-Search Applications</a>
 * <li><a href="#Suggestions">Search Suggestions</a>
 * <li><a href="#ActionKeys">Action Keys</a>
 * <li><a href="#SearchabilityMetadata">Searchability Metadata</a>
 * <li><a href="#PassingSearchContext">Passing Search Context</a>
 * <li><a href="#ProtectingUserPrivacy">Protecting User Privacy</a>
 * </ol>
 * 
 * <a name="DeveloperGuide"></a>
 * <h3>Developer Guide</h3>
 * 
 * <p>The ability to search for user, system, or network based data is considered to be
 * a core user-level feature of the android platform.  At any time, the user should be
 * able to use a familiar command, button, or keystroke to invoke search, and the user
 * should be able to search any data which is available to them.  The goal is to make search 
 * appear to the user as a seamless, system-wide feature.
 * 
 * <p>In terms of implementation, there are three broad classes of Applications:
 * <ol>
 * <li>Applications that are not inherently searchable</li>
 * <li>Query-Search Applications</li>
 * <li>Filter-Search Applications</li>
 * </ol>
 * <p>These categories, as well as related topics, are discussed in
 * the sections below.
 *
 * <p>Even if your application is not <i>searchable</i>, it can still support the invocation of
 * search.  Please review the section <a href="#HowSearchIsInvoked">How Search Is Invoked</a>
 * for more information on how to support this.
 * 
 * <p>Many applications are <i>searchable</i>.  These are 
 * the applications which can convert a query string into a list of results.  
 * Within this subset, applications can be grouped loosely into two families:  
 * <ul><li><i>Query Search</i> applications perform batch-mode searches - each query string is 
 * converted to a list of results.</li>
 * <li><i>Filter Search</i> applications provide live filter-as-you-type searches.</li></ul>
 * <p>Generally speaking, you would use query search for network-based data, and filter 
 * search for local data, but this is not a hard requirement and applications 
 * are free to use the model that fits them best (or invent a new model).
 * <p>It should be clear that the search implementation decouples "search 
 * invocation" from "searchable".  This satisfies the goal of making search appear
 * to be "universal".  The user should be able to launch any search from 
 * almost any context.
 * 
 * <a name="HowSearchIsInvoked"></a>
 * <h3>How Search Is Invoked</h3>
 * 
 * <p>Unless impossible or inapplicable, all applications should support
 * invoking the search UI.  This means that when the user invokes the search command, 
 * a search UI will be presented to them.  The search command is currently defined as a menu
 * item called "Search" (with an alphabetic shortcut key of "S"), or on some devices, a dedicated
 * search button key.
 * <p>If your application is not inherently searchable, you can also allow the search UI
 * to be invoked in a "web search" mode.  If the user enters a search term and clicks the 
 * "Search" button, this will bring the browser to the front and will launch a web-based
 * search.  The user will be able to click the "Back" button and return to your application.
 * <p>In general this is implemented by your activity, or the {@link android.app.Activity Activity}
 * base class, which captures the search command and invokes the Search Manager to 
 * display and operate the search UI.  You can also cause the search UI to be presented in response
 * to user keystrokes in your activity (for example, to instantly start filter searching while
 * viewing a list and typing any key).
 * <p>The search UI is presented as a floating 
 * window and does not cause any change in the activity stack.  If the user 
 * cancels search, the previous activity re-emerges.  If the user launches a 
 * search, this will be done by sending a search {@link android.content.Intent Intent} (see below), 
 * and the normal intent-handling sequence will take place (your activity will pause,
 * etc.)
 * <p><b>What you need to do:</b> First, you should consider the way in which you want to
 * handle invoking search.  There are four broad (and partially overlapping) categories for 
 * you to choose from.
 * <ul><li>You can capture the search command yourself, by including a <i>search</i>
 * button or menu item - and invoking the search UI directly.</li>
 * <li>You can provide a <i>type-to-search</i> feature, in which search is invoked automatically
 * when the user enters any characters.</li>
 * <li>Even if your application is not inherently searchable, you can allow web search, 
 * via the search key (or even via a search menu item).
 * <li>You can disable search entirely.  This should only be used in very rare circumstances,
 * as search is a system-wide feature and users will expect it to be available in all contexts.</li>
 * </ul>
 * 
 * <p><b>How to define a search menu.</b>  The system provides the following resources which may
 * be useful when adding a search item to your menu:
 * <ul><li>android.R.drawable.ic_search_category_default is an icon you can use in your menu.</li>
 * <li>{@link #MENU_KEY SearchManager.MENU_KEY} is the recommended alphabetic shortcut.</li>
 * </ul>
 * 
 * <p><b>How to invoke search directly.</b>  In order to invoke search directly, from a button
 * or menu item, you can launch a generic search by calling
 * {@link android.app.Activity#onSearchRequested onSearchRequested} as shown:
 * <pre class="prettyprint">
 * onSearchRequested();</pre>
 * 
 * <p><b>How to implement type-to-search.</b>  While setting up your activity, call
 * {@link android.app.Activity#setDefaultKeyMode setDefaultKeyMode}:
 * <pre class="prettyprint">
 * setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);   // search within your activity
 * setDefaultKeyMode(DEFAULT_KEYS_SEARCH_GLOBAL);  // search using platform global search</pre>
 * 
 * <p><b>How to enable web-based search.</b>  In addition to searching within your activity or
 * application, you can also use the Search Manager to invoke a platform-global search, typically
 * a web search.  There are two ways to do this:
 * <ul><li>You can simply define "search" within your application or activity to mean global search.
 * This is described in more detail in the 
 * <a href="#SearchabilityMetadata">Searchability Metadata</a> section.  Briefly, you will
 * add a single meta-data entry to your manifest, declaring that the default search
 * for your application is "*".  This indicates to the system that no application-specific
 * search activity is provided, and that it should launch web-based search instead.</li>
 * <li>You can specify this at invocation time via default keys (see above), overriding
 * {@link android.app.Activity#onSearchRequested}, or via a direct call to 
 * {@link android.app.Activity#startSearch}.  This is most useful if you wish to provide local
 * searchability <i>and</i> access to global search.</li></ul> 
 * 
 * <p><b>How to disable search from your activity.</b>  search is a system-wide feature and users
 * will expect it to be available in all contexts.  If your UI design absolutely precludes
 * launching search, override {@link android.app.Activity#onSearchRequested onSearchRequested}
 * as shown:
 * <pre class="prettyprint">
 * &#64;Override
 * public boolean onSearchRequested() {
 *    return false;
 * }</pre> 
 * 
 * <p><b>Managing focus and knowing if Search is active.</b>  The search UI is not a separate
 * activity, and when the UI is invoked or dismissed, your activity will not typically be paused,
 * resumed, or otherwise notified by the methods defined in 
 * <a href="android.app.Activity#ActivityLifecycle">Activity Lifecycle</a>.  The search UI is
 * handled in the same way as other system UI elements which may appear from time to time, such as 
 * notifications, screen locks, or other system alerts:  
 * <p>When the search UI appears, your activity will lose input focus.
 * <p>When the search activity is dismissed, there are three possible outcomes:
 * <ul><li>If the user simply canceled the search UI, your activity will regain input focus and
 * proceed as before.  See {@link #setOnDismissListener} and {@link #setOnCancelListener} if you 
 * required direct notification of search dialog dismissals.</li>
 * <li>If the user launched a search, and this required switching to another activity to receive
 * and process the search {@link android.content.Intent Intent}, your activity will receive the 
 * normal sequence of activity pause or stop notifications.</li>
 * <li>If the user launched a search, and the current activity is the recipient of the search 
 * {@link android.content.Intent Intent}, you will receive notification via the 
 * {@link android.app.Activity#onNewIntent onNewIntent()} method.</li></ul>
 * <p>This list is provided in order to clarify the ways in which your activities will interact with
 * the search UI.  More details on searchable activities and search intents are provided in the
 * sections below.
 *
 * <a name="QuerySearchApplications"></a>
 * <h3>Query-Search Applications</h3>
 * 
 * <p>Query-search applications are those that take a single query (e.g. a search
 * string) and present a set of results that may fit.  Primary examples include
 * web queries, map lookups, or email searches (with the common thread being
 * network query dispatch).  It may also be the case that certain local searches
 * are treated this way.  It's up to the application to decide.
 *
 * <p><b>What you need to do:</b>  The following steps are necessary in order to
 * implement query search.
 * <ul>
 * <li>Implement search invocation as described above.  (Strictly speaking, 
 * these are decoupled, but it would make little sense to be "searchable" but not 
 * "search-invoking".)</li>
 * <li>Your application should have an activity that takes a search string and
 * converts it to a list of results.  This could be your primary display activity
 * or it could be a dedicated search results activity.  This is your <i>searchable</i>
 * activity and every query-search application must have one.</li>
 * <li>In the searchable activity, in onCreate(), you must receive and handle the 
 * {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH}
 * {@link android.content.Intent Intent}.  The text to search (query string) for is provided by 
 * calling 
 * {@link #QUERY getStringExtra(SearchManager.QUERY)}.</li>
 * <li>To identify and support your searchable activity, you'll need to 
 * provide an XML file providing searchability configuration parameters, a reference to that 
 * in your searchable activity's <a href="../../../devel/bblocks-manifest.html">manifest</a>
 * entry, and an intent-filter declaring that you can 
 * receive ACTION_SEARCH intents.  This is described in more detail in the 
 * <a href="#SearchabilityMetadata">Searchability Metadata</a> section.</li>
 * <li>Your <a href="../../../devel/bblocks-manifest.html">manifest</a> also needs a metadata entry
 * providing a global reference to the searchable activity.  This is the "glue" directing the search
 * UI, when invoked from any of your <i>other</i> activities, to use your application as the
 * default search context.  This is also described in more detail in the 
 * <a href="#SearchabilityMetadata">Searchability Metadata</a> section.</li> 
 * <li>Finally, you may want to define your search results activity as with the 
 * {@link android.R.attr#launchMode singleTop} launchMode flag.  This allows the system 
 * to launch searches from/to the same activity without creating a pile of them on the 
 * activity stack.  If you do this, be sure to also override 
 * {@link android.app.Activity#onNewIntent onNewIntent} to handle the
 * updated intents (with new queries) as they arrive.</li>
 * </ul>
 *
 * <p>Code snippet showing handling of intents in your search activity:
 * <pre class="prettyprint">
 * &#64;Override
 * protected void onCreate(Bundle icicle) {
 *     super.onCreate(icicle);
 *     
 *     final Intent queryIntent = getIntent();
 *     final String queryAction = queryIntent.getAction();
 *     if (Intent.ACTION_SEARCH.equals(queryAction)) {
 *         doSearchWithIntent(queryIntent);
 *     }
 * }
 * 
 * private void doSearchWithIntent(final Intent queryIntent) {
 *     final String queryString = queryIntent.getStringExtra(SearchManager.QUERY);
 *     doSearchWithQuery(queryString);
 * }</pre>
 * 
 * <a name="FilterSearchApplications"></a>
 * <h3>Filter-Search Applications</h3>
 * 
 * <p>Filter-search applications are those that use live text entry (e.g. keystrokes)) to
 * display and continuously update a list of results.  Primary examples include applications
 * that use locally-stored data.
 * 
 * <p>Filter search is not directly supported by the Search Manager.  Most filter search
 * implementations will use variants of {@link android.widget.Filterable}, such as a 
 * {@link android.widget.ListView} bound to a {@link android.widget.SimpleCursorAdapter}.  However,
 * you may find it useful to mix them together, by declaring your filtered view searchable.  With
 * this configuration, you can still present the standard search dialog in all activities
 * within your application, but transition to a filtered search when you enter the activity
 * and display the results.
 * 
 * <a name="Suggestions"></a>
 * <h3>Search Suggestions</h3>
 * 
 * <p>A powerful feature of the Search Manager is the ability of any application to easily provide
 * live "suggestions" in order to prompt the user.  Each application implements suggestions in a 
 * different, unique, and appropriate way.  Suggestions be drawn from many sources, including but 
 * not limited to:
 * <ul>
 * <li>Actual searchable results (e.g. names in the address book)</li>
 * <li>Recently entered queries</li>
 * <li>Recently viewed data or results</li>
 * <li>Contextually appropriate queries or results</li>
 * <li>Summaries of possible results</li>
 * </ul>
 * 
 * <p>Another feature of suggestions is that they can expose queries or results before the user
 * ever visits the application.  This reduces the amount of context switching required, and helps
 * the user access their data quickly and with less context shifting.  In order to provide this
 * capability, suggestions are accessed via a 
 * {@link android.content.ContentProvider Content Provider}.  
 * 
 * <p>The primary form of suggestions is known as <i>queried suggestions</i> and is based on query
 * text that the user has already typed.  This would generally be based on partial matches in
 * the available data.  In certain situations - for example, when no query text has been typed yet -
 * an application may also opt to provide <i>zero-query suggestions</i>.
 * These would typically be drawn from the same data source, but because no partial query text is 
 * available, they should be weighted based on other factors - for example, most recent queries 
 * or most recent results.
 * 
 * <p><b>Overview of how suggestions are provided.</b>  When the search manager identifies a 
 * particular activity as searchable, it will check for certain metadata which indicates that
 * there is also a source of suggestions.  If suggestions are provided, the following steps are
 * taken.
 * <ul><li>Using formatting information found in the metadata, the user's query text (whatever
 * has been typed so far) will be formatted into a query and sent to the suggestions 
 * {@link android.content.ContentProvider Content Provider}.</li>
 * <li>The suggestions {@link android.content.ContentProvider Content Provider} will create a
 * {@link android.database.Cursor Cursor} which can iterate over the possible suggestions.</li>
 * <li>The search manager will populate a list using display data found in each row of the cursor,
 * and display these suggestions to the user.</li>
 * <li>If the user types another key, or changes the query in any way, the above steps are repeated
 * and the suggestions list is updated or repopulated.</li>
 * <li>If the user clicks or touches the "GO" button, the suggestions are ignored and the search is
 * launched using the normal {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH} type of 
 * {@link android.content.Intent Intent}.</li>
 * <li>If the user uses the directional controls to navigate the focus into the suggestions list,
 * the query text will be updated while the user navigates from suggestion to suggestion.  The user
 * can then click or touch the updated query and edit it further.  If the user navigates back to
 * the edit field, the original typed query is restored.</li>
 * <li>If the user clicks or touches a particular suggestion, then a combination of data from the 
 * cursor and
 * values found in the metadata are used to synthesize an Intent and send it to the application.
 * Depending on the design of the activity and the way it implements search, this might be a
 * {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH} (in order to launch a query), or it
 * might be a {@link android.content.Intent#ACTION_VIEW ACTION_VIEW}, in order to proceed directly
 * to display of specific data.</li>
 * </ul>
 *  
 * <p><b>Simple Recent-Query-Based Suggestions.</b>  The Android framework provides a simple Search
 * Suggestions provider, which simply records and replays recent queries.  For many applications,
 * this will be sufficient.  The basic steps you will need to
 * do, in order to use the built-in recent queries suggestions provider, are as follows:
 * <ul>
 * <li>Implement and test query search, as described in the previous sections.</li>
 * <li>Create a Provider within your application by extending 
 * {@link android.content.SearchRecentSuggestionsProvider}.</li>
 * <li>Create a manifest entry describing your provider.</li>
 * <li>Update your searchable activity's XML configuration file with information about your
 * provider.</li>
 * <li>In your searchable activities, capture any user-generated queries and record them
 * for future searches by calling {@link android.provider.SearchRecentSuggestions#saveRecentQuery}.
 * </li>
 * </ul>
 * <p>For complete implementation details, please refer to 
 * {@link android.content.SearchRecentSuggestionsProvider}.  The rest of the information in this
 * section should not be necessary, as it refers to custom suggestions providers.
 * 
 * <p><b>Creating a Customized Suggestions Provider:</b>  In order to create more sophisticated
 * suggestion providers, you'll need to take the following steps:
 * <ul>
 * <li>Implement and test query search, as described in the previous sections.</li>
 * <li>Decide how you wish to <i>receive</i> suggestions.  Just like queries that the user enters,
 * suggestions will be delivered to your searchable activity as 
 * {@link android.content.Intent Intent} messages;  Unlike simple queries, you have quite a bit of
 * flexibility in forming those intents.  A query search application will probably
 * wish to continue receiving the {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH} 
 * {@link android.content.Intent Intent}, which will launch a query search using query text as
 * provided by the suggestion.  A filter search application will probably wish to 
 * receive the {@link android.content.Intent#ACTION_VIEW ACTION_VIEW} 
 * {@link android.content.Intent Intent}, which will take the user directly to a selected entry.
 * Other interesting suggestions, including hybrids, are possible, and the suggestion provider
 * can easily mix-and-match results to provide a richer set of suggestions for the user.  Finally,
 * you'll need to update your searchable activity (or other activities) to receive the intents
 * as you've defined them.</li>
 * <li>Implement a Content Provider that provides suggestions.  If you already have one, and it 
 * has access to your suggestions data.  If not, you'll have to create one.
 * You'll also provide information about your Content Provider in your 
 * package's <a href="../../../devel/bblocks-manifest.html">manifest</a>.</li>
 * <li>Update your searchable activity's XML configuration file.  There are two categories of
 * information used for suggestions:
 * <ul><li>The first is (required) data that the search manager will
 * use to format the queries which are sent to the Content Provider.</li>
 * <li>The second is (optional) parameters to configure structure
 * if intents generated by suggestions.</li></li>
 * </ul>
 * </ul>
 * 
 * <p><b>Configuring your Content Provider to Receive Suggestion Queries.</b>  The basic job of
 * a search suggestions {@link android.content.ContentProvider Content Provider} is to provide
 * "live" (while-you-type) conversion of the user's query text into a set of zero or more 
 * suggestions.  Each application is free to define the conversion, and as described above there are
 * many possible solutions.  This section simply defines how to communicate with the suggestion
 * provider.  
 * 
 * <p>The Search Manager must first determine if your package provides suggestions.  This is done
 * by examination of your searchable meta-data XML file.  The android:searchSuggestAuthority
 * attribute, if provided, is the signal to obtain & display suggestions.
 * 
 * <p>Every query includes a Uri, and the Search Manager will format the Uri as shown:
 * <p><pre class="prettyprint">
 * content:// your.suggest.authority / your.suggest.path / SearchManager.SUGGEST_URI_PATH_QUERY</pre>
 * 
 * <p>Your Content Provider can receive the query text in one of two ways.
 * <ul>
 * <li><b>Query provided as a selection argument.</b>  If you define the attribute value
 * android:searchSuggestSelection and include a string, this string will be passed as the 
 * <i>selection</i> parameter to your Content Provider's query function.  You must define a single
 * selection argument, using the '?' character.  The user's query text will be passed to you
 * as the first element of the selection arguments array.</li>
 * <li><b>Query provided with Data Uri.</b>  If you <i>do not</i> define the attribute value
 * android:searchSuggestSelection, then the Search Manager will append another "/" followed by
 * the user's query to the query Uri.  The query will be encoding using Uri encoding rules - don't
 * forget to decode it.  (See {@link android.net.Uri#getPathSegments} and
 * {@link android.net.Uri#getLastPathSegment} for helpful utilities you can use here.)</li>
 * </ul>
 * 
 * <p><b>Handling empty queries.</b>  Your application should handle the "empty query"
 * (no user text entered) case properly, and generate useful suggestions in this case.  There are a
 * number of ways to do this;  Two are outlined here:
 * <ul><li>For a simple filter search of local data, you could simply present the entire dataset,
 * unfiltered.  (example: People)</li>
 * <li>For a query search, you could simply present the most recent queries.  This allows the user
 * to quickly repeat a recent search.</li></ul>
 * 
 * <p><b>The Format of Individual Suggestions.</b>  Your suggestions are communicated back to the
 * Search Manager by way of a {@link android.database.Cursor Cursor}.  The Search Manager will
 * usually pass a null Projection, which means that your provider can simply return all appropriate
 * columns for each suggestion.  The columns currently defined are:
 * 
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *
 *     <thead>
 *     <tr><th>Column Name</th> <th>Description</th> <th>Required?</th></tr>
 *     </thead>
 *
 *     <tbody>
 *     <tr><th>{@link #SUGGEST_COLUMN_FORMAT}</th>
 *         <td><i>Unused - can be null.</i></td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>{@link #SUGGEST_COLUMN_TEXT_1}</th>
 *         <td>This is the line of text that will be presented to the user as the suggestion.</td>
 *         <td align="center">Yes</td>
 *     </tr>
 *     
 *     <tr><th>{@link #SUGGEST_COLUMN_TEXT_2}</th>
 *         <td>If your cursor includes this column, then all suggestions will be provided in a 
 *             two-line format.  The data in this column will be displayed as a second, smaller
 *             line of text below the primary suggestion, or it can be null or empty to indicate no
 *             text in this row's suggestion.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>{@link #SUGGEST_COLUMN_ICON_1}</th>
 *         <td>If your cursor includes this column, then all suggestions will be provided in an
 *             icons+text format.  This value should be a reference (resource ID) of the icon to
 *             draw on the left side, or it can be null or zero to indicate no icon in this row.
 *             You must provide both cursor columns, or neither.
 *             </td>
 *         <td align="center">No, but required if you also have {@link #SUGGEST_COLUMN_ICON_2}</td>
 *     </tr>
 *     
 *     <tr><th>{@link #SUGGEST_COLUMN_ICON_2}</th>
 *         <td>If your cursor includes this column, then all suggestions will be provided in an
 *             icons+text format.  This value should be a reference (resource ID) of the icon to
 *             draw on the right side, or it can be null or zero to indicate no icon in this row.
 *             You must provide both cursor columns, or neither.
 *             </td>
 *         <td align="center">No, but required if you also have {@link #SUGGEST_COLUMN_ICON_1}</td>
 *     </tr>
 *     
 *     <tr><th>{@link #SUGGEST_COLUMN_INTENT_ACTION}</th>
 *         <td>If this column exists <i>and</i> this element exists at the given row, this is the 
 *             action that will be used when forming the suggestion's intent.  If the element is 
 *             not provided, the action will be taken from the android:searchSuggestIntentAction 
 *             field in your XML metadata.  <i>At least one of these must be present for the 
 *             suggestion to generate an intent.</i>  Note:  If your action is the same for all 
 *             suggestions, it is more efficient to specify it using XML metadata and omit it from 
 *             the cursor.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>{@link #SUGGEST_COLUMN_INTENT_DATA}</th>
 *         <td>If this column exists <i>and</i> this element exists at the given row, this is the 
 *             data that will be used when forming the suggestion's intent.  If the element is not 
 *             provided, the data will be taken from the android:searchSuggestIntentData field in 
 *             your XML metadata.  If neither source is provided, the Intent's data field will be 
 *             null.  Note:  If your data is the same for all suggestions, or can be described 
 *             using a constant part and a specific ID, it is more efficient to specify it using 
 *             XML metadata and omit it from the cursor.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>{@link #SUGGEST_COLUMN_INTENT_DATA_ID}</th>
 *         <td>If this column exists <i>and</i> this element exists at the given row, then "/" and 
 *             this value will be appended to the data field in the Intent.  This should only be 
 *             used if the data field has already been set to an appropriate base string.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>{@link #SUGGEST_COLUMN_QUERY}</th>
 *         <td>If this column exists <i>and</i> this element exists at the given row, this is the 
 *             data that will be used when forming the suggestion's query.</td>
 *         <td align="center">Required if suggestion's action is 
 *             {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH}, optional otherwise.</td>
 *     </tr>
 *     
 *     <tr><th><i>Other Columns</i></th>
 *         <td>Finally, if you have defined any <a href="#ActionKeys">Action Keys</a> and you wish 
 *             for them to have suggestion-specific definitions, you'll need to define one 
 *             additional column per action key.  The action key will only trigger if the 
 *             currently-selection suggestion has a non-empty string in the corresponding column.  
 *             See the section on <a href="#ActionKeys">Action Keys</a> for additional details and 
 *             implementation steps.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     </tbody>
 * </table>
 *
 * <p>Clearly there are quite a few permutations of your suggestion data, but in the next section
 * we'll look at a few simple combinations that you'll select from. 
 *
 * <p><b>The Format Of Intents Sent By Search Suggestions.</b>  Although there are many ways to 
 * configure these intents, this document will provide specific information on just a few of them.  
 * <ul><li><b>Launch a query.</b>  In this model, each suggestion represents a query that your
 * searchable activity can perform, and the {@link android.content.Intent Intent} will be formatted
 * exactly like those sent when the user enters query text and clicks the "GO" button:
 *   <ul>
 *   <li><b>Action:</b> {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH} provided
 *   using your XML metadata (android:searchSuggestIntentAction).</li>
 *   <li><b>Data:</b> empty (not used).</li>
 *   <li><b>Query:</b> query text supplied by the cursor.</li>
 *   </ul>
 * </li>
 * <li><b>Go directly to a result, using a complete Data Uri.</b>  In this model, the user will be 
 * taken directly to a specific result.
 *   <ul>
 *   <li><b>Action:</b> {@link android.content.Intent#ACTION_VIEW ACTION_VIEW}</li>
 *   <li><b>Data:</b> a complete Uri, supplied by the cursor, that identifies the desired data.</li>
 *   <li><b>Query:</b> query text supplied with the suggestion (probably ignored)</li>
 *   </ul>
 * </li>
 * <li><b>Go directly to a result, using a synthesized Data Uri.</b>  This has the same result
 * as the previous suggestion, but provides the Data Uri in a different way.
 *   <ul>
 *   <li><b>Action:</b> {@link android.content.Intent#ACTION_VIEW ACTION_VIEW}</li>
 *   <li><b>Data:</b> The search manager will assemble a Data Uri using the following elements:  
 *   a Uri fragment provided in your XML metadata (android:searchSuggestIntentData), followed by 
 *   a single "/", followed by the value found in the {@link #SUGGEST_COLUMN_INTENT_DATA_ID} 
 *   entry in your cursor.</li>
 *   <li><b>Query:</b> query text supplied with the suggestion (probably ignored)</li>
 *   </ul>
 * </li>
 * </ul>
 * <p>This list is not meant to be exhaustive.  Applications should feel free to define other types
 * of suggestions.  For example, you could reduce long lists of results to summaries, and use one
 * of the above intents (or one of your own) with specially formatted Data Uri's to display more
 * detailed results.  Or you could display textual shortcuts as suggestions, but launch a display
 * in a more data-appropriate format such as media artwork.
 * 
 * <p><b>Suggestion Rewriting.</b>  If the user navigates through the suggestions list, the UI
 * may temporarily rewrite the user's query with a query that matches the currently selected 
 * suggestion.  This enables the user to see what query is being suggested, and also allows the user
 * to click or touch in the entry EditText element and make further edits to the query before
 * dispatching it.  In order to perform this correctly, the Search UI needs to know exactly what
 * text to rewrite the query with.
 * 
 * <p>For each suggestion, the following logic is used to select a new query string:
 * <ul><li>If the suggestion provides an explicit value in the {@link #SUGGEST_COLUMN_QUERY} 
 * column, this value will be used.</li>
 * <li>If the metadata includes the queryRewriteFromData flag, and the suggestion provides an 
 * explicit value for the intent Data field, this Uri will be used.  Note that this should only be
 * used with Uri's that are intended to be user-visible, such as HTTP.  Internal Uri schemes should
 * not be used in this way.</li>
 * <li>If the metadata includes the queryRewriteFromText flag, the text in 
 * {@link #SUGGEST_COLUMN_TEXT_1} will be used.  This should be used for suggestions in which no
 * query text is provided and the SUGGEST_COLUMN_INTENT_DATA values are not suitable for user 
 * inspection and editing.</li></ul>
 *
 * <a name="ActionKeys"></a>
 * <h3>Action Keys</h3>
 * 
 * <p>Searchable activities may also wish to provide shortcuts based on the various action keys
 * available on the device.  The most basic example of this is the contacts app, which enables the
 * green "dial" key for quick access during searching.  Not all action keys are available on 
 * every device, and not all are allowed to be overriden in this way.  (For example, the "Home"
 * key must always return to the home screen, with no exceptions.)
 * 
 * <p>In order to define action keys for your searchable application, you must do two things.
 * 
 * <ul>
 * <li>You'll add one or more <i>actionkey</i> elements to your searchable metadata configuration
 * file.  Each element defines one of the keycodes you are interested in, 
 * defines the conditions under which they are sent, and provides details
 * on how to communicate the action key event back to your searchable activity.</li>
 * <li>In your broadcast receiver, if you wish, you can check for action keys by checking the 
 * extras field of the {@link android.content.Intent Intent}.</li>
 * </ul>
 * 
 * <p><b>Updating metadata.</b>  For each keycode of interest, you must add an &lt;actionkey&gt;
 * element.  Within this element you must define two or three attributes.  The first attribute,
 * &lt;android:keycode&gt;, is required;  It is the key code of the action key event, as defined in 
 * {@link android.view.KeyEvent}.  The remaining two attributes define the value of the actionkey's
 * <i>message</i>, which will be passed to your searchable activity in the 
 * {@link android.content.Intent Intent} (see below for more details).  Although each of these 
 * attributes is optional, you must define one or both for the action key to have any effect.
 * &lt;android:queryActionMsg&gt; provides the message that will be sent if the action key is 
 * pressed while the user is simply entering query text.  &lt;android:suggestActionMsgColumn&gt;
 * is used when action keys are tied to specific suggestions.  This attribute provides the name
 * of a <i>column</i> in your suggestion cursor;  The individual suggestion, in that column,
 * provides the message.  (If the cell is empty or null, that suggestion will not work with that
 * action key.)
 * <p>See the <a href="#SearchabilityMetadata">Searchability Metadata</a> section for more details 
 * and examples.
 * 
 * <p><b>Receiving Action Keys</b>  Intents launched by action keys will be specially marked
 * using a combination of values.  This enables your searchable application to examine the intent,
 * if necessary, and perform special processing.  For example, clicking a suggested contact might
 * simply display them;  Selecting a suggested contact and clicking the dial button might
 * immediately call them.
 * 
 * <p>When a search {@link android.content.Intent Intent} is launched by an action key, two values
 * will be added to the extras field.
 * <ul>
 * <li>To examine the key code, use {@link android.content.Intent#getIntExtra 
 * getIntExtra(SearchManager.ACTION_KEY)}.</li>
 * <li>To examine the message string, use {@link android.content.Intent#getStringExtra 
 * getStringExtra(SearchManager.ACTION_MSG)}</li>
 * </ul>
 * 
 * <a name="SearchabilityMetadata"></a>
 * <h3>Searchability Metadata</h3>
 * 
 * <p>Every activity that is searchable must provide a small amount of additional information
 * in order to properly configure the search system.  This controls the way that your search
 * is presented to the user, and controls for the various modalities described previously.
 * 
 * <p>If your application is not searchable,
 * then you do not need to provide any search metadata, and you can skip the rest of this section.
 * When this search metadata cannot be found, the search manager will assume that the activity 
 * does not implement search.  (Note: to implement web-based search, you will need to add
 * the android.app.default_searchable metadata to your manifest, as shown below.)
 * 
 * <p>Values you supply in metadata apply only to each local searchable activity.  Each
 * searchable activity can define a completely unique search experience relevant to its own
 * capabilities and user experience requirements, and a single application can even define multiple
 * searchable activities.
 *
 * <p><b>Metadata for searchable activity.</b>  As with your search implementations described 
 * above, you must first identify which of your activities is searchable.  In the 
 * <a href="../../../devel/bblocks-manifest.html">manifest</a> entry for this activity, you must 
 * provide two elements:
 * <ul><li>An intent-filter specifying that you can receive and process the 
 * {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH} {@link android.content.Intent Intent}.
 * </li>
 * <li>A reference to a small XML file (typically called "searchable.xml") which contains the
 * remaining configuration information for how your application implements search.</li></ul>
 * 
 * <p>Here is a snippet showing the necessary elements in the 
 * <a href="../../../devel/bblocks-manifest.html">manifest</a> entry for your searchable activity.
 * <pre class="prettyprint">
 *        &lt;!-- Search Activity - searchable --&gt;
 *        &lt;activity android:name="MySearchActivity" 
 *                  android:label="Search"
 *                  android:launchMode="singleTop"&gt;
 *            &lt;intent-filter&gt;
 *                &lt;action android:name="android.intent.action.SEARCH" /&gt;
 *                &lt;category android:name="android.intent.category.DEFAULT" /&gt;
 *            &lt;/intent-filter&gt;
 *            &lt;meta-data android:name="android.app.searchable" 
 *                       android:resource="@xml/searchable" /&gt;
 *        &lt;/activity&gt;</pre>
 *
 * <p>Next, you must provide the rest of the searchability configuration in 
 * the small XML file, stored in the ../xml/ folder in your build.  The XML file is a 
 * simple enumeration of the search configuration parameters for searching within this activity,
 * application, or package.  Here is a sample XML file (named searchable.xml, for use with
 * the above manifest) for a query-search activity.
 *
 * <pre class="prettyprint">
 * &lt;searchable xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:label="@string/search_label"
 *     android:hint="@string/search_hint" &gt;
 * &lt;/searchable&gt;</pre>
 *
 * <p>Note that all user-visible strings <i>must</i> be provided in the form of "@string" 
 * references.  Hard-coded strings, which cannot be localized, will not work properly in search
 * metadata.
 * 
 * <p>Attributes you can set in search metadata:
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *
 *     <thead>
 *     <tr><th>Attribute</th> <th>Description</th> <th>Required?</th></tr>
 *     </thead>
 *
 *     <tbody>
 *     <tr><th>android:label</th>
 *         <td>This is the name for your application that will be presented to the user in a 
 *             list of search targets, or in the search box as a label.</td>
 *         <td align="center">Yes</td>
 *     </tr>
 *     
 *     <tr><th>android:icon</th>
 *         <td>If provided, this icon will be used <i>in place</i> of the label string.  This
 *         is provided in order to present logos or other non-textual banners.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>android:hint</th>
 *         <td>This is the text to display in the search text field when no user text has been 
 *             entered.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>android:searchButtonText</th>
 *         <td>If provided, this text will replace the default text in the "Search" button.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>android:searchMode</th>
 *         <td>If provided and non-zero, sets additional modes for control of the search 
 *             presentation.  The following mode bits are defined:
 *             <table border="2" align="center" frame="hsides" rules="rows">
 *                 <tbody>
 *                 <tr><th>showSearchLabelAsBadge</th>
 *                     <td>If set, this flag enables the display of the search target (label) 
 *                         within the search bar.  If this flag and showSearchIconAsBadge
 *                         (see below) are both not set, no badge will be shown.</td>
 *                 </tr>
 *                 <tr><th>showSearchIconAsBadge</th>
 *                     <td>If set, this flag enables the display of the search target (icon) within
 *                         the search bar.  If this flag and showSearchLabelAsBadge
 *                         (see above) are both not set, no badge will be shown.  If both flags
 *                         are set, showSearchIconAsBadge has precedence and the icon will be
 *                         shown.</td>
 *                 </tr>
 *                 <tr><th>queryRewriteFromData</th>
 *                     <td>If set, this flag causes the suggestion column SUGGEST_COLUMN_INTENT_DATA
 *                         to be considered as the text for suggestion query rewriting.  This should
 *                         only be used when the values in SUGGEST_COLUMN_INTENT_DATA are suitable
 *                         for user inspection and editing - typically, HTTP/HTTPS Uri's.</td>
 *                 </tr>
 *                 <tr><th>queryRewriteFromText</th>
 *                     <td>If set, this flag causes the suggestion column SUGGEST_COLUMN_TEXT_1 to 
 *                         be considered as the text for suggestion query rewriting.  This should 
 *                         be used for suggestions in which no query text is provided and the 
 *                         SUGGEST_COLUMN_INTENT_DATA values are not suitable for user inspection 
 *                         and editing.</td>
 *                 </tr>
 *                 </tbody>
 *            </table></td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>android:inputType</th>
 *         <td>If provided, supplies a hint about the type of search text the user will be
 *             entering.  For most searches, in which free form text is expected, this attribute
 *             need not be provided.  Suitable values for this attribute are described in the
 *             <a href="../R.attr.html#inputType">inputType</a> attribute.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     </tbody>
 * </table>
 * 
 * <p><b>Styleable Resources in your Metadata.</b>  It's possible to provide alternate strings
 * for your searchable application, in order to provide localization and/or to better visual 
 * presentation on different device configurations.  Each searchable activity has a single XML 
 * metadata file, but any resource references can be replaced at runtime based on device
 * configuration, language setting, and other system inputs.
 * 
 * <p>A concrete example is the "hint" text you supply using the android:searchHint attribute.
 * In portrait mode you'll have less screen space and may need to provide a shorter string, but
 * in landscape mode you can provide a longer, more descriptive hint.  To do this, you'll need to
 * define two or more strings.xml files, in the following directories:
 * <ul><li>.../res/values-land/strings.xml</li>
 * <li>.../res/values-port/strings.xml</li>
 * <li>.../res/values/strings.xml</li></ul>
 * 
 * <p>For more complete documentation on this capability, see
 * <a href="../../../devel/resources-i18n.html#AlternateResources">Resources and 
 * Internationalization: Supporting Alternate Resources for Alternate Languages and Configurations
 * </a>.
 *
 * <p><b>Metadata for non-searchable activities.</b>  Activities which are part of a searchable
 * application, but don't implement search itself, require a bit of "glue" in order to cause
 * them to invoke search using your searchable activity as their primary context.  If this is not
 * provided, then searches from these activities will use the system default search context.
 * 
 * <p>The simplest way to specify this is to add a <i>search reference</i> element to the
 * application entry in the <a href="../../../devel/bblocks-manifest.html">manifest</a> file.  
 * The value of this reference can be either of:
 * <ul><li>The name of your searchable activity.  
 * It is typically prefixed by '.' to indicate that it's in the same package.</li>
 * <li>A "*" indicates that the system may select a default searchable activity, in which
 * case it will typically select web-based search.</li>
 * </ul>
 *
 * <p>Here is a snippet showing the necessary addition to the manifest entry for your 
 * non-searchable activities.
 * <pre class="prettyprint">
 *        &lt;application&gt;
 *            &lt;meta-data android:name="android.app.default_searchable"
 *                       android:value=".MySearchActivity" /&gt;
 *            
 *            &lt;!-- followed by activities, providers, etc... --&gt;
 *        &lt;/application&gt;</pre>
 *
 * <p>You can also specify android.app.default_searchable on a per-activity basis, by including
 * the meta-data element (as shown above) in one or more activity sections.  If found, these will
 * override the reference in the application section.  The only reason to configure your application
 * this way would be if you wish to partition it into separate sections with different search 
 * behaviors;  Otherwise this configuration is not recommended.
 * 
 * <p><b>Additional Metadata for search suggestions.</b>  If you have defined a content provider
 * to generate search suggestions, you'll need to publish it to the system, and you'll need to 
 * provide a bit of additional XML metadata in order to configure communications with it.
 * 
 * <p>First, in your <a href="../../../devel/bblocks-manifest.html">manifest</a>, you'll add the
 * following lines.
 * <pre class="prettyprint">
 *        &lt;!-- Content provider for search suggestions --&gt;
 *        &lt;provider android:name="YourSuggestionProviderClass"
 *                android:authorities="your.suggestion.authority" /&gt;</pre>
 * 
 * <p>Next, you'll add a few lines to your XML metadata file, as shown:
 * <pre class="prettyprint">
 *     &lt;!-- Required attribute for any suggestions provider --&gt;
 *     android:searchSuggestAuthority="your.suggestion.authority"
 *     
 *     &lt;!-- Optional attribute for configuring queries --&gt;
 *     android:searchSuggestSelection="field =?"
 *     
 *     &lt;!-- Optional attributes for configuring intent construction --&gt;
 *     android:searchSuggestIntentAction="intent action string"
 *     android:searchSuggestIntentData="intent data Uri" /&gt;</pre>
 * 
 * <p>Elements of search metadata that support suggestions:
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *
 *     <thead>
 *     <tr><th>Attribute</th> <th>Description</th> <th>Required?</th></tr>
 *     </thead>
 *
 *     <tbody>
 *     <tr><th>android:searchSuggestAuthority</th>
 *         <td>This value must match the authority string provided in the <i>provider</i> section 
 *             of your <a href="../../../devel/bblocks-manifest.html">manifest</a>.</td>
 *         <td align="center">Yes</td>
 *     </tr>
 *     
 *     <tr><th>android:searchSuggestPath</th>
 *         <td>If provided, this will be inserted in the suggestions query Uri, after the authority
 *             you have provide but before the standard suggestions path.  This is only required if
 *             you have a single content provider issuing different types of suggestions (e.g. for
 *             different data types) and you need a way to disambiguate the suggestions queries
 *             when they are received.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>android:searchSuggestSelection</th>
 *         <td>If provided, this value will be passed into your query function as the 
 *             <i>selection</i> parameter.  Typically this will be a WHERE clause for your database, 
 *             and will contain a single question mark, which represents the actual query string 
 *             that has been typed by the user.  However, you can also use any non-null value
 *             to simply trigger the delivery of the query text (via selection arguments), and then
 *             use the query text in any way appropriate for your provider (ignoring the actual
 *             text of the selection parameter.)</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>android:searchSuggestIntentAction</th>
 *         <td>If provided, and not overridden by the selected suggestion, this value will be 
 *             placed in the action field of the {@link android.content.Intent Intent} when the 
 *             user clicks a suggestion.</td>
 *         <td align="center">No</td>
 *     
 *     <tr><th>android:searchSuggestIntentData</th>
 *         <td>If provided, and not overridden by the selected suggestion, this value will be 
 *             placed in the data field of the {@link android.content.Intent Intent} when the user 
 *             clicks a suggestion.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     </tbody>
 * </table>
 * 
 * <p><b>Additional Metadata for search action keys.</b>  For each action key that you would like to
 * define, you'll need to add an additional element defining that key, and using the attributes
 * discussed in <a href="#ActionKeys">Action Keys</a>.  A simple example is shown here:
 * 
 * <pre class="prettyprint">&lt;actionkey
 *     android:keycode="KEYCODE_CALL"
 *     android:queryActionMsg="call"
 *     android:suggestActionMsg="call"
 *     android:suggestActionMsgColumn="call_column" /&gt;</pre>
 *
 * <p>Elements of search metadata that support search action keys.  Note that although each of the
 * action message elements are marked as <i>optional</i>, at least one must be present for the 
 * action key to have any effect.
 * 
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *
 *     <thead>
 *     <tr><th>Attribute</th> <th>Description</th> <th>Required?</th></tr>
 *     </thead>
 *
 *     <tbody>
 *     <tr><th>android:keycode</th>
 *         <td>This attribute denotes the action key you wish to respond to.  Note that not
 *             all action keys are actually supported using this mechanism, as many of them are
 *             used for typing, navigation, or system functions.  This will be added to the 
 *             {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH} intent that is passed to 
 *             your searchable activity.  To examine the key code, use 
 *             {@link android.content.Intent#getIntExtra getIntExtra(SearchManager.ACTION_KEY)}.  
 *             <p>Note, in addition to the keycode, you must also provide one or more of the action
 *             specifier attributes.</td>
 *         <td align="center">Yes</td>
 *     </tr>
 *     
 *     <tr><th>android:queryActionMsg</th>
 *         <td>If you wish to handle an action key during normal search query entry, you
 *          must define an action string here.  This will be added to the 
 *          {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH} intent that is passed to your
 *          searchable activity.  To examine the string, use 
 *          {@link android.content.Intent#getStringExtra 
 *          getStringExtra(SearchManager.ACTION_MSG)}.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>android:suggestActionMsg</th>
 *         <td>If you wish to handle an action key while a suggestion is being displayed <i>and
 *             selected</i>, there are two ways to handle this.  If <i>all</i> of your suggestions
 *             can handle the action key, you can simply define the action message using this 
 *             attribute.  This will be added to the 
 *             {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH} intent that is passed to
 *             your searchable activity.  To examine the string, use 
 *             {@link android.content.Intent#getStringExtra 
 *             getStringExtra(SearchManager.ACTION_MSG)}.</td>
 *         <td align="center">No</td>
 *     </tr>
 *     
 *     <tr><th>android:suggestActionMsgColumn</th>
 *         <td>If you wish to handle an action key while a suggestion is being displayed <i>and
 *             selected</i>, but you do not wish to enable this action key for every suggestion, 
 *             then you can use this attribute to control it on a suggestion-by-suggestion basis.
 *             First, you must define a column (and name it here) where your suggestions will 
 *             include the action string.  Then, in your content provider, you must provide this
 *             column, and when desired, provide data in this column.
 *             The search manager will look at your suggestion cursor, using the string 
 *             provided here in order to select a column, and will use that to select a string from 
 *             the cursor.  That string will be added to the 
 *             {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH} intent that is passed to 
 *             your searchable activity.  To examine the string, use 
 *             {@link android.content.Intent#getStringExtra 
 *             getStringExtra(SearchManager.ACTION_MSG)}.  <i>If the data does not exist for the
 *             selection suggestion, the action key will be ignored.</i></td>
 *         <td align="center">No</td>
 *     </tr>
 * 
 *     </tbody>
 * </table>
 * 
 * <a name="PassingSearchContext"></a>
 * <h3>Passing Search Context</h3>
 * 
 * <p>In order to improve search experience, an application may wish to specify
 * additional data along with the search, such as local history or context.  For
 * example, a maps search would be improved by including the current location.  
 * In order to simplify the structure of your activities, this can be done using 
 * the search manager.
 *
 * <p>Any data can be provided at the time the search is launched, as long as it
 * can be stored in a {@link android.os.Bundle Bundle} object.
 *
 * <p>To pass application data into the Search Manager, you'll need to override
 * {@link android.app.Activity#onSearchRequested onSearchRequested} as follows:
 *
 * <pre class="prettyprint">
 * &#64;Override
 * public boolean onSearchRequested() {
 *     Bundle appData = new Bundle();
 *     appData.put...();
 *     appData.put...();
 *     startSearch(null, false, appData);
 *     return true;
 * }</pre> 
 *
 * <p>To receive application data from the Search Manager, you'll extract it from
 * the {@link android.content.Intent#ACTION_SEARCH ACTION_SEARCH}
 * {@link android.content.Intent Intent} as follows:
 *
 * <pre class="prettyprint">
 * final Bundle appData = queryIntent.getBundleExtra(SearchManager.APP_DATA);
 * if (appData != null) {
 *     appData.get...();
 *     appData.get...();
 * }</pre>
 * 
 * <a name="ProtectingUserPrivacy"></a>
 * <h3>Protecting User Privacy</h3>
 * 
 * <p>Many users consider their activities on the phone, including searches, to be private 
 * information.  Applications that implement search should take steps to protect users' privacy
 * wherever possible.  This section covers two areas of concern, but you should consider your search
 * design carefully and take any additional steps necessary.
 * 
 * <p><b>Don't send personal information to servers, and if you do, don't log it.</b>
 * "Personal information" is information that can personally identify your users, such as name, 
 * email address or billing information, or other data which can be reasonably linked to such 
 * information.  If your application implements search with the assistance of a server, try to 
 * avoid sending personal information with your searches.  For example, if you are searching for 
 * businesses near a zip code, you don't need to send the user ID as well - just send the zip code
 * to the server.  If you do need to send personal information, you should take steps to avoid 
 * logging it.  If you must log it, you should protect that data very carefully, and erase it as 
 * soon as possible.
 * 
 * <p><b>Provide the user with a way to clear their search history.</b>  The Search Manager helps
 * your application provide context-specific suggestions.  Sometimes these suggestions are based
 * on previous searches, or other actions taken by the user in an earlier session.  A user may not
 * wish for previous searches to be revealed to other users, for instance if they share their phone
 * with a friend.  If your application provides suggestions that can reveal previous activities,
 * you should implement a "Clear History" menu, preference, or button.  If you are using 
 * {@link android.provider.SearchRecentSuggestions}, you can simply call its 
 * {@link android.provider.SearchRecentSuggestions#clearHistory() clearHistory()} method from
 * your "Clear History" UI.  If you are implementing your own form of recent suggestions, you'll 
 * need to provide a similar a "clear history" API in your provider, and call it from your
 * "Clear History" UI.
 */
public class SearchManager 
        implements DialogInterface.OnDismissListener, DialogInterface.OnCancelListener
{
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
     * Intent extra data key: Use this key with Intent.ACTION_SEARCH and
     * {@link android.content.Intent#getBundleExtra
     *  content.Intent.getBundleExtra()}
     * to obtain any additional app-specific data that was inserted by the 
     * activity that launched the search.
     */
    public final static String APP_DATA = "app_data";

    /**
     * Intent app_data bundle key: Use this key with the bundle from
     * {@link android.content.Intent#getBundleExtra
     * content.Intent.getBundleExtra(APP_DATA)} to obtain the source identifier
     * set by the activity that launched the search.
     *
     * @hide
     */
    public final static String SOURCE = "source";
    
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
     * Intent extra data key: Use this key with Intent.ACTION_SEARCH and
     * {@link android.content.Intent#getStringExtra content.Intent.getStringExtra()}
     * to obtain the action message that was defined for a particular search action key and/or
     * suggestion.  It will be null if the search was launched by typing "enter", touched the the 
     * "GO" button, or other means not involving any action key. 
     */
    public final static String ACTION_MSG = "action_msg";
    
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
     * Column name for suggestions cursor.  <i>Optional.</i>  If your cursor includes this column,
     *  then all suggestions will be provided in format that includes space for two small icons,
     *  one at the left and one at the right of each suggestion.  The data in the column must
     *  be a a resource ID for the icon you wish to have displayed.  If you include this column,
     *  you must also include {@link #SUGGEST_COLUMN_ICON_2}.
     */
    public final static String SUGGEST_COLUMN_ICON_1 = "suggest_icon_1";
    /**
     * Column name for suggestions cursor.  <i>Optional.</i>  If your cursor includes this column,
     *  then all suggestions will be provided in format that includes space for two small icons,
     *  one at the left and one at the right of each suggestion.  The data in the column must
     *  be a a resource ID for the icon you wish to have displayed.  If you include this column,
     *  you must also include {@link #SUGGEST_COLUMN_ICON_1}.
     */
    public final static String SUGGEST_COLUMN_ICON_2 = "suggest_icon_2";
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


    private final Context mContext;
    private final Handler mHandler;
    
    private SearchDialog mSearchDialog;
    
    private OnDismissListener mDismissListener = null;
    private OnCancelListener mCancelListener = null;

    /*package*/ SearchManager(Context context, Handler handler)  {
        mContext = context;
        mHandler = handler;
    }
    private static ISearchManager mService;

    static {
        mService = ISearchManager.Stub.asInterface(
                    ServiceManager.getService(Context.SEARCH_SERVICE));
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
     * search is defined in the current application or activity, no search will be launched.
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
        
        if (mSearchDialog == null) {
            mSearchDialog = new SearchDialog(mContext);
        }

        // activate the search manager and start it up!
        mSearchDialog.show(initialQuery, selectInitialQuery, launchActivity, appSearchData, 
                globalSearch);
        
        mSearchDialog.setOnCancelListener(this);
        mSearchDialog.setOnDismissListener(this);
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
     * @see #startSearch
     */
    public void stopSearch()  {
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
    public boolean isVisible()  {
        if (mSearchDialog != null) {
            return mSearchDialog.isShowing();
        }
        return false;
    }
    
    /**
     * See {@link #setOnDismissListener} for configuring your activity to monitor search UI state.
     */
    public interface OnDismissListener {
        /**
         * This method will be called when the search UI is dismissed. To make use if it, you must
         * implement this method in your activity, and call {@link #setOnDismissListener} to 
         * register it.
         */
        public void onDismiss();
    }
    
    /**
     * See {@link #setOnCancelListener} for configuring your activity to monitor search UI state.
     */
    public interface OnCancelListener {
        /**
         * This method will be called when the search UI is canceled. To make use if it, you must
         * implement this method in your activity, and call {@link #setOnCancelListener} to 
         * register it.
         */
        public void onCancel();
    }

    /**
     * Set or clear the callback that will be invoked whenever the search UI is dismissed.
     * 
     * @param listener The {@link OnDismissListener} to use, or null.
     */
    public void setOnDismissListener(final OnDismissListener listener) {
        mDismissListener = listener;
    }
    
    /**
     * The callback from the search dialog when dismissed
     * @hide
     */
    public void onDismiss(DialogInterface dialog) {
        if (dialog == mSearchDialog) {
            if (mDismissListener != null) {
                mDismissListener.onDismiss();
            }
        }
    }

    /**
     * Set or clear the callback that will be invoked whenever the search UI is canceled.
     * 
     * @param listener The {@link OnCancelListener} to use, or null.
     */
    public void setOnCancelListener(final OnCancelListener listener) {
        mCancelListener = listener;
    }
    
    
    /**
     * The callback from the search dialog when canceled
     * @hide
     */
    public void onCancel(DialogInterface dialog) {
        if (dialog == mSearchDialog) {
            if (mCancelListener != null) {
                mCancelListener.onCancel();
            }
        }
    }

    /**
     * Save instance state so we can recreate after a rotation.
     * 
     * @hide
     */
    void saveSearchDialog(Bundle outState, String key) {
        if (mSearchDialog != null && mSearchDialog.isShowing()) {
            Bundle searchDialogState = mSearchDialog.onSaveInstanceState();
            outState.putBundle(key, searchDialogState);
        }
    }

    /**
     * Restore instance state after a rotation.
     * 
     * @hide
     */
    void restoreSearchDialog(Bundle inState, String key) {        
        Bundle searchDialogState = inState.getBundle(key);
        if (searchDialogState != null) {
            if (mSearchDialog == null) {
                mSearchDialog = new SearchDialog(mContext);
            }
            mSearchDialog.onRestoreInstanceState(searchDialogState);
        }
    }
    
    /**
     * Hook for updating layout on a rotation
     * 
     * @hide
     */
    void onConfigurationChanged(Configuration newConfig) {
        if (mSearchDialog != null && mSearchDialog.isShowing()) {
            mSearchDialog.onConfigurationChanged(newConfig);
        }
    }
      
}
