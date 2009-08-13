/*
 * Copyright (C) 2008 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.speech;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * RecognitionResult is a passive object that stores a single recognized
 * query and its search result.
 * TODO: revisit and improve. May be we should have a separate result
 * object for each type, and put them (type/value) in bundle?
 * 
 * {@hide}
 */
public class RecognitionResult implements Parcelable {
    /**
     * Status of the recognize request.
     */
    public static final int NETWORK_TIMEOUT = 1;  // Network operation timed out.
    public static final int NETWORK_ERROR = 2;  // Other networkrelated errors.
    public static final int AUDIO_ERROR = 3;  // Audio recording error.
    public static final int SERVER_ERROR = 4;  // Server sends error status.
    public static final int CLIENT_ERROR = 5;  // Other client side errors.
    public static final int SPEECH_TIMEOUT = 6;  // No speech input
    public static final int NO_MATCH = 7;  // No recognition result matched.
    public static final int SERVICE_BUSY = 8;  // RecognitionService busy.

    /**
     * Type of the recognition results. 
     */
    public static final int RAW_RECOGNITION_RESULT  = 0;
    public static final int WEB_SEARCH_RESULT  = 1;
    public static final int CONTACT_RESULT = 2;

    /**
     * A factory method to create a raw RecognitionResult
     * 
     * @param sentence the recognized text.
     */
    public static RecognitionResult newRawRecognitionResult(String sentence) {
        return new RecognitionResult(RAW_RECOGNITION_RESULT, sentence, null, null);
    }

    /**
     * A factory method to create RecognitionResult for contacts.
     * 
     * @param contact the contact name.
     * @param phoneType the phone type.
     * @param callAction whether this result included a command to "call", or just the contact name.
     */
    public static RecognitionResult newContactResult(String contact, int phoneType,
            boolean callAction) {
        return new RecognitionResult(CONTACT_RESULT, contact, phoneType, callAction);
    }

    /**
     * A factory method to create a RecognitionResult for Web Search Query.
     * 
     * @param query the query string. 
     * @param html the html page of the search result.
     * @param url  the url that performs the search with the query.
     */
    public static RecognitionResult newWebResult(String query, String html, String url) {
        return new RecognitionResult(WEB_SEARCH_RESULT, query, html, url);
    }
    
    public static final Parcelable.Creator<RecognitionResult> CREATOR
            = new Parcelable.Creator<RecognitionResult>() {

        public RecognitionResult createFromParcel(Parcel in) {
            return new RecognitionResult(in);
        }
        
        public RecognitionResult[] newArray(int size) {
            return new RecognitionResult[size];
        }
    };

    /**
     * Result type.
     */
    public final int mResultType;

     /**
     * The recognized string when mResultType is WEB_SEARCH_RESULT.
     * The name of the contact when mResultType is CONTACT_RESULT.
     */
    public final String mText;

    /**
     * The HTML result page for the query. If this is null, then the
     * application must use the url field to get the HTML result page.
     */
    public final String mHtml;

    /**
     * The url to get the result page for the query string.  The
     * application must use this url instead of performing the search
     * with the query.
     */
    public final String mUrl;

    /**
     * Phone number type. This is valid only when mResultType == CONTACT_RESULT.
     */
    public final int mPhoneType;
    
    /**
     * Whether a contact recognition result included a command to "call". This is valid only
     * when mResultType == CONTACT_RESULT.
     */
    public final boolean mCallAction;

    private RecognitionResult(int type, String query, String html, String url) {
        mResultType = type;
        mText = query;
        mHtml = html;
        mUrl = url;
        mPhoneType = -1;
        mCallAction = false;
    }

    private RecognitionResult(int type, String query, int phoneType, boolean callAction) {
        mResultType = type;
        mText = query;
        mPhoneType = phoneType;
        mHtml = null;
        mUrl = null;
        mCallAction = callAction;
    }
    
    private RecognitionResult(Parcel in) {
        mResultType = in.readInt();
        mText = in.readString();
        mHtml= in.readString();
        mUrl= in.readString();
        mPhoneType = in.readInt();
        mCallAction = (in.readInt() == 1);
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mResultType);
        out.writeString(mText);
        out.writeString(mHtml);
        out.writeString(mUrl);
        out.writeInt(mPhoneType);
        out.writeInt(mCallAction ? 1 : 0);
    }
    
    
    @Override
    public String toString() {
        String resultType[] = { "RAW", "WEB", "CONTACT" };
        return "[type=" +  resultType[mResultType] +
                ", text=" + mText+ ", mUrl=" + mUrl + ", html=" + mHtml + "]";
    }

    public int describeContents() {
        // no special description
        return 0;
    }
}
