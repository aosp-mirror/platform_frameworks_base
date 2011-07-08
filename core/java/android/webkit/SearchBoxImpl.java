/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.webkit;

import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebViewCore.EventHub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;

/**
 * The default implementation of the SearchBox interface. Implemented
 * as a java bridge object and a javascript adapter that is called into
 * by the page hosted in the frame.
 */
final class SearchBoxImpl implements SearchBox {
    private static final String TAG = "WebKit.SearchBoxImpl";

    /* package */ static final String JS_INTERFACE_NAME = "searchBoxJavaBridge_";

    /* package */ static final String JS_BRIDGE
            = "(function()"
            + "{"
            + "if (!window.chrome) {"
            + "  window.chrome = {};"
            + "}"
            + "if (!window.chrome.searchBox) {"
            + "  var sb = window.chrome.searchBox = {};"
            + "  sb.setSuggestions = function(suggestions) {"
            + "    if (window.searchBoxJavaBridge_) {"
            + "      window.searchBoxJavaBridge_.setSuggestions(JSON.stringify(suggestions));"
            + "    }"
            + "  };"
            + "  sb.setValue = function(valueArray) { sb.value = valueArray[0]; };"
            + "  sb.value = '';"
            + "  sb.x = 0;"
            + "  sb.y = 0;"
            + "  sb.width = 0;"
            + "  sb.height = 0;"
            + "  sb.selectionStart = 0;"
            + "  sb.selectionEnd = 0;"
            + "  sb.verbatim = false;"
            + "}"
            + "})();";

    private static final String SET_QUERY_SCRIPT
            = "if (window.chrome && window.chrome.searchBox) {"
            + "  window.chrome.searchBox.setValue(%s);"
            + "}";

    private static final String SET_VERBATIM_SCRIPT
            =  "if (window.chrome && window.chrome.searchBox) {"
            + "  window.chrome.searchBox.verbatim = %1$s;"
            + "}";

    private static final String SET_SELECTION_SCRIPT
            = "if (window.chrome && window.chrome.searchBox) {"
            + "  var f = window.chrome.searchBox;"
            + "  f.selectionStart = %d"
            + "  f.selectionEnd = %d"
            + "}";

    private static final String SET_DIMENSIONS_SCRIPT
            = "if (window.chrome && window.chrome.searchBox) { "
            + "  var f = window.chrome.searchBox;"
            + "  f.x = %d;"
            + "  f.y = %d;"
            + "  f.width = %d;"
            + "  f.height = %d;"
            + "}";

    private static final String DISPATCH_EVENT_SCRIPT
            = "if (window.chrome && window.chrome.searchBox && window.chrome.searchBox.on%1$s) {"
            + "  window.chrome.searchBox.on%1$s();"
            + "  window.searchBoxJavaBridge_.dispatchCompleteCallback('%1$s', %2$d, true);"
            + "} else {"
            + "  window.searchBoxJavaBridge_.dispatchCompleteCallback('%1$s', %2$d, false);"
            + "}";

    private static final String EVENT_CHANGE = "change";
    private static final String EVENT_SUBMIT = "submit";
    private static final String EVENT_RESIZE = "resize";
    private static final String EVENT_CANCEL = "cancel";

    private static final String IS_SUPPORTED_SCRIPT
            = "if (window.searchBoxJavaBridge_) {"
            + "  if (window.chrome && window.chrome.sv) {"
            + "    window.searchBoxJavaBridge_.isSupportedCallback(true);"
            + "  } else {"
            + "    window.searchBoxJavaBridge_.isSupportedCallback(false);"
            + "  }}";

    private final List<SearchBoxListener> mListeners;
    private final WebViewCore mWebViewCore;
    private final CallbackProxy mCallbackProxy;
    private IsSupportedCallback mSupportedCallback;
    private int mNextEventId = 1;
    private final HashMap<Integer, SearchBoxListener> mEventCallbacks;

    SearchBoxImpl(WebViewCore webViewCore, CallbackProxy callbackProxy) {
        mListeners = new ArrayList<SearchBoxListener>();
        mWebViewCore = webViewCore;
        mCallbackProxy = callbackProxy;
        mEventCallbacks = new HashMap<Integer, SearchBoxListener>();
    }

    @Override
    public void setQuery(String query) {
        final String formattedQuery = jsonSerialize(query);
        if (formattedQuery != null) {
            final String js = String.format(SET_QUERY_SCRIPT, formattedQuery);
            dispatchJs(js);
        }
    }

    @Override
    public void setVerbatim(boolean verbatim) {
        final String js = String.format(SET_VERBATIM_SCRIPT, String.valueOf(verbatim));
        dispatchJs(js);
    }


    @Override
    public void setSelection(int selectionStart, int selectionEnd) {
        final String js = String.format(SET_SELECTION_SCRIPT, selectionStart, selectionEnd);
        dispatchJs(js);
    }

    @Override
    public void setDimensions(int x, int y, int width, int height) {
        final String js = String.format(SET_DIMENSIONS_SCRIPT, x, y, width, height);
        dispatchJs(js);
    }

    @Override
    public void onchange(SearchBoxListener callback) {
        dispatchEvent(EVENT_CHANGE, callback);
    }

    @Override
    public void onsubmit(SearchBoxListener callback) {
        dispatchEvent(EVENT_SUBMIT, callback);
    }

    @Override
    public void onresize(SearchBoxListener callback) {
        dispatchEvent(EVENT_RESIZE, callback);
    }

    @Override
    public void oncancel(SearchBoxListener callback) {
        dispatchEvent(EVENT_CANCEL, callback);
    }

    private void dispatchEvent(String eventName, SearchBoxListener callback) {
        int eventId;
        if (callback != null) {
            synchronized(this) {
                eventId = mNextEventId++;
                mEventCallbacks.put(eventId, callback);
            }
        } else {
            eventId = 0;
        }
        final String js = String.format(DISPATCH_EVENT_SCRIPT, eventName, eventId);
        dispatchJs(js);
    }

    private void dispatchJs(String js) {
        mWebViewCore.sendMessage(EventHub.EXECUTE_JS, js);
    }

    @Override
    public void addSearchBoxListener(SearchBoxListener l) {
        synchronized (mListeners) {
            mListeners.add(l);
        }
    }

    @Override
    public void removeSearchBoxListener(SearchBoxListener l) {
        synchronized (mListeners) {
            mListeners.remove(l);
        }
    }

    @Override
    public void isSupported(IsSupportedCallback callback) {
        mSupportedCallback = callback;
        dispatchJs(IS_SUPPORTED_SCRIPT);
    }

    // Called by Javascript through the Java bridge.
    public void isSupportedCallback(boolean isSupported) {
        mCallbackProxy.onIsSupportedCallback(isSupported);
    }

    public void handleIsSupportedCallback(boolean isSupported) {
        IsSupportedCallback callback = mSupportedCallback;
        mSupportedCallback = null;
        if (callback != null) {
            callback.searchBoxIsSupported(isSupported);
        }
    }

    // Called by Javascript through the Java bridge.
    public void dispatchCompleteCallback(String function, int id, boolean successful) {
        mCallbackProxy.onSearchboxDispatchCompleteCallback(function, id, successful);
    }

    public void handleDispatchCompleteCallback(String function, int id, boolean successful) {
        if (id != 0) {
            SearchBoxListener listener;
            synchronized(this) {
                listener = mEventCallbacks.get(id);
                mEventCallbacks.remove(id);
            }
            if (listener != null) {
                if (TextUtils.equals(EVENT_CHANGE, function)) {
                    listener.onChangeComplete(successful);
                } else if (TextUtils.equals(EVENT_SUBMIT, function)) {
                    listener.onSubmitComplete(successful);
                } else if (TextUtils.equals(EVENT_RESIZE, function)) {
                    listener.onResizeComplete(successful);
                } else if (TextUtils.equals(EVENT_CANCEL, function)) {
                    listener.onCancelComplete(successful);
                }
            }
        }
    }

    // This is used as a hackish alternative to javascript escaping.
    // There appears to be no such functionality in the core framework.
    private static String jsonSerialize(String query) {
        JSONStringer stringer = new JSONStringer();
        try {
            stringer.array().value(query).endArray();
        } catch (JSONException e) {
            Log.w(TAG, "Error serializing query : " + query);
            return null;
        }
        return stringer.toString();
    }

    // Called by Javascript through the Java bridge.
    public void setSuggestions(String jsonArguments) {
        if (jsonArguments == null) {
            return;
        }

        String query = null;
        List<String> suggestions = new ArrayList<String>();
        try {
            JSONObject suggestionsJson = new JSONObject(jsonArguments);
            query = suggestionsJson.getString("query");

            final JSONArray suggestionsArray = suggestionsJson.getJSONArray("suggestions");
            for (int i = 0; i < suggestionsArray.length(); ++i) {
                final JSONObject suggestion = suggestionsArray.getJSONObject(i);
                final String value = suggestion.getString("value");
                if (value != null) {
                    suggestions.add(value);
                }
                // We currently ignore the "type" of the suggestion. This isn't
                // documented anywhere in the API documents.
                // final String type = suggestions.getString("type");
            }
        } catch (JSONException je) {
            Log.w(TAG, "Error parsing json [" + jsonArguments + "], exception = " + je);
            return;
        }

        mCallbackProxy.onSearchboxSuggestionsReceived(query, suggestions);
    }

    /* package */ void handleSuggestions(String query, List<String> suggestions) {
        synchronized (mListeners) {
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                mListeners.get(i).onSuggestionsReceived(query, suggestions);
            }
        }
    }
}
