package android.app.assist;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Holds information about the content an application is viewing, to hand to an
 * assistant at the user's request.  This is filled in by
 * {@link android.app.Activity#onProvideAssistContent Activity.onProvideAssistContent}.
 */
public class AssistContent implements Parcelable {
    private boolean mIsAppProvidedIntent = false;
    private boolean mIsAppProvidedWebUri = false;
    private Intent mIntent;
    private String mStructuredData;
    private ClipData mClipData;
    private Uri mUri;
    private final Bundle mExtras;

    public AssistContent() {
        mExtras = new Bundle();
    }

    /**
     * @hide
     * Called by {@link android.app.ActivityThread} to set the default Intent based on
     * {@link android.app.Activity#getIntent Activity.getIntent}.
     *
     * <p>Automatically populates {@link #mUri} if that Intent is an {@link Intent#ACTION_VIEW}
     * of a web (http or https scheme) URI.</p>
     */
    public void setDefaultIntent(Intent intent) {
        mIntent = intent;
        mIsAppProvidedIntent = false;
        mIsAppProvidedWebUri = false;
        mUri = null;
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (uri != null) {
                if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                    mUri = uri;
                }
            }
        }
    }

    /**
     * Sets the Intent associated with the content, describing the current top-level context of
     * the activity.  If this contains a reference to a piece of data related to the activity,
     * be sure to set {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} so the accessibility
     * service can access it.
     */
    public void setIntent(Intent intent) {
        mIsAppProvidedIntent = true;
        mIntent = intent;
    }

    /**
     * Returns the current {@link #setIntent} if one is set, else the default Intent obtained from
     * {@link android.app.Activity#getIntent Activity.getIntent}. Can be modified in-place.
     */
    public Intent getIntent() {
        return mIntent;
    }

    /**
     * Returns whether or not the current Intent was explicitly provided in
     * {@link android.app.Activity#onProvideAssistContent Activity.onProvideAssistContent}. If not,
     * the Intent was automatically set based on
     * {@link android.app.Activity#getIntent Activity.getIntent}.
     */
    public boolean isAppProvidedIntent() {
        return mIsAppProvidedIntent;
    }

    /**
     * Optional additional content items that are involved with
     * the current UI.  Access to this content will be granted to the assistant as if you
     * are sending it through an Intent with {@link Intent#FLAG_GRANT_READ_URI_PERMISSION}.
     */
    public void setClipData(ClipData clip) {
        mClipData = clip;
    }

    /**
     * Return the current {@link #setClipData}, which you can modify in-place.
     */
    public ClipData getClipData() {
        return mClipData;
    }

    /**
     * Sets optional structured data regarding the content being viewed. The provided data
     * must be a string represented with <a href="http://json-ld.org/">JSON-LD</a> using the
     * <a href="http://schema.org/">schema.org</a> vocabulary.
     */
    public void setStructuredData(String structuredData) {
        mStructuredData = structuredData;
    }

    /**
     * Returns the current {@link #setStructuredData}.
     */
    public String getStructuredData() {
        return mStructuredData;
    }

    /**
     * Set a web URI associated with the current data being shown to the user.
     * This URI could be opened in a web browser, or in the app as an
     * {@link Intent#ACTION_VIEW} Intent, to show the same data that is currently
     * being displayed by it.  The URI here should be something that is transportable
     * off the device into other environments to acesss the same data as is currently
     * being shown in the app; if the app does not have such a representation, it should
     * leave the null and only report the local intent and clip data.
     */
    public void setWebUri(Uri uri) {
        mIsAppProvidedWebUri = true;
        mUri = uri;
    }

    /**
     * Return the content's web URI as per {@link #setWebUri(android.net.Uri)}, or null if
     * there is none.
     */
    public Uri getWebUri() {
        return mUri;
    }

    /**
     * Returns whether or not the current {@link #getWebUri} was explicitly provided in
     * {@link android.app.Activity#onProvideAssistContent Activity.onProvideAssistContent}. If not,
     * the Intent was automatically set based on
     * {@link android.app.Activity#getIntent Activity.getIntent}.
     */
    public boolean isAppProvidedWebUri() {
        return mIsAppProvidedWebUri;
    }

    /**
     * Return Bundle for extra vendor-specific data that can be modified and examined.
     */
    public Bundle getExtras() {
        return mExtras;
    }

    AssistContent(Parcel in) {
        if (in.readInt() != 0) {
            mIntent = Intent.CREATOR.createFromParcel(in);
        }
        if (in.readInt() != 0) {
            mClipData = ClipData.CREATOR.createFromParcel(in);
        }
        if (in.readInt() != 0) {
            mUri = Uri.CREATOR.createFromParcel(in);
        }
        if (in.readInt() != 0) {
            mStructuredData = in.readString();
        }
        mIsAppProvidedIntent = in.readInt() == 1;
        mExtras = in.readBundle();
        mIsAppProvidedWebUri = in.readInt() == 1;
    }

    void writeToParcelInternal(Parcel dest, int flags) {
        if (mIntent != null) {
            dest.writeInt(1);
            mIntent.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        if (mClipData != null) {
            dest.writeInt(1);
            mClipData.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        if (mUri != null) {
            dest.writeInt(1);
            mUri.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        if (mStructuredData != null) {
            dest.writeInt(1);
            dest.writeString(mStructuredData);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(mIsAppProvidedIntent ? 1 : 0);
        dest.writeBundle(mExtras);
        dest.writeInt(mIsAppProvidedWebUri ? 1 : 0);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    public static final Parcelable.Creator<AssistContent> CREATOR
            = new Parcelable.Creator<AssistContent>() {
        public AssistContent createFromParcel(Parcel in) {
            return new AssistContent(in);
        }

        public AssistContent[] newArray(int size) {
            return new AssistContent[size];
        }
    };
}
