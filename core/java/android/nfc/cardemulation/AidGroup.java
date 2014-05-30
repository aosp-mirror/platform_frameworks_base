package android.nfc.cardemulation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * The AidGroup class represents a group of Application Identifiers (AIDs).
 *
 * <p>An instance of this object can be used with
 * {@link CardEmulation#registerAidGroupForService(android.content.ComponentName, AidGroup)}
 * to tell the OS which AIDs are handled by your HCE- or SE-based service.
 *
 * <p>The format of AIDs is defined in the ISO/IEC 7816-4 specification. This class
 * requires the AIDs to be input as a hexadecimal string, with an even amount of
 * hexadecimal characters, e.g. "F014811481".
 *
 * @hide
 */
public final class AidGroup implements Parcelable {
    /**
     * The maximum number of AIDs that can be present in any one group.
     */
    public static final int MAX_NUM_AIDS = 256;

    static final String TAG = "AidGroup";

    final List<String> aids;
    final String category;
    final String description;

    /**
     * Creates a new AidGroup object.
     *
     * @param aids The list of AIDs present in the group
     * @param category The category of this group, e.g. {@link CardEmulation#CATEGORY_PAYMENT}
     */
    public AidGroup(List<String> aids, String category) {
        if (aids == null || aids.size() == 0) {
            throw new IllegalArgumentException("No AIDS in AID group.");
        }
        if (aids.size() > MAX_NUM_AIDS) {
            throw new IllegalArgumentException("Too many AIDs in AID group.");
        }
        if (isValidCategory(category)) {
            this.category = category;
        } else {
            this.category = CardEmulation.CATEGORY_OTHER;
        }
        this.aids = aids;
        this.description = null;
    }

    AidGroup(String category, String description) {
        this.aids = new ArrayList<String>();
        this.category = category;
        this.description = description;
    }

    /**
     * @return the category of this AID group
     */
    public String getCategory() {
        return category;
    }

    /**
     * @return the list of  AIDs in this group
     */
    public List<String> getAids() {
        return aids;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("Category: " + category +
                  ", AIDs:");
        for (String aid : aids) {
            out.append(aid);
            out.append(", ");
        }
        return out.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(category);
        dest.writeInt(aids.size());
        if (aids.size() > 0) {
            dest.writeStringList(aids);
        }
    }

    public static final Parcelable.Creator<AidGroup> CREATOR =
            new Parcelable.Creator<AidGroup>() {

        @Override
        public AidGroup createFromParcel(Parcel source) {
            String category = source.readString();
            int listSize = source.readInt();
            ArrayList<String> aidList = new ArrayList<String>();
            if (listSize > 0) {
                source.readStringList(aidList);
            }
            return new AidGroup(aidList, category);
        }

        @Override
        public AidGroup[] newArray(int size) {
            return new AidGroup[size];
        }
    };

    static public AidGroup createFromXml(XmlPullParser parser) throws XmlPullParserException, IOException {
        String category = parser.getAttributeValue(null, "category");
        ArrayList<String> aids = new ArrayList<String>();
        int eventType = parser.getEventType();
        int minDepth = parser.getDepth();
        while (eventType != XmlPullParser.END_DOCUMENT && parser.getDepth() >= minDepth) {
            if (eventType == XmlPullParser.START_TAG) {
                String tagName = parser.getName();
                if (tagName.equals("aid")) {
                    String aid = parser.getAttributeValue(null, "value");
                    if (aid != null) {
                        aids.add(aid);
                    }
                } else {
                    Log.d(TAG, "Ignorning unexpected tag: " + tagName);
                }
            }
            eventType = parser.next();
        }
        if (category != null && aids.size() > 0) {
            return new AidGroup(aids, category);
        } else {
            return null;
        }
    }

    public void writeAsXml(XmlSerializer out) throws IOException {
        out.attribute(null, "category", category);
        for (String aid : aids) {
            out.startTag(null, "aid");
            out.attribute(null, "value", aid);
            out.endTag(null, "aid");
        }
    }

    static boolean isValidCategory(String category) {
        return CardEmulation.CATEGORY_PAYMENT.equals(category) ||
                CardEmulation.CATEGORY_OTHER.equals(category);
    }
}
