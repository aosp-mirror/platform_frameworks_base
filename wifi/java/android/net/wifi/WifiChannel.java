
package android.net.wifi;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class representing one wifi channel or frequency
 * @hide
 */
public class WifiChannel implements Parcelable {
    public int channel;
    public int frequency;
    public boolean ibssAllowed;

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append(" channel: ").append(channel);
        sbuf.append(" freq: ").append(frequency);
        sbuf.append(" MHz");
        sbuf.append(" IBSS: ").append(ibssAllowed ? "allowed" : "not allowed");
        sbuf.append('\n');
        return sbuf.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof WifiChannel) {
            WifiChannel w = (WifiChannel)o;
            return (this.channel == w.channel &&
                    this.frequency == w.frequency &&
                    this.ibssAllowed == w.ibssAllowed);
        }
        return false;
    }

    /** Implement the Parcelable interface */
    public int describeContents() {
        return 0;
    }

    public WifiChannel() {
        channel = 0;
        frequency = 0;
        ibssAllowed = false;
    }

    public WifiChannel(int ch, int freq, boolean ibss) {
        channel = ch;
        frequency = freq;
        ibssAllowed = ibss;
    }

    /* Copy constructor */
    public WifiChannel(WifiChannel source) {
        if (source != null) {
            channel = source.channel;
            frequency = source.frequency;
            ibssAllowed = source.ibssAllowed;
        }
    }

    /** Implement the Parcelable interface */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(channel);
        dest.writeInt(frequency);
        dest.writeInt(ibssAllowed ? 1 : 0);
    }

    /** Implement the Parcelable interface */
    public static final Creator<WifiChannel> CREATOR =
            new Creator<WifiChannel>() {
                public WifiChannel createFromParcel(Parcel in) {
                    WifiChannel ch = new WifiChannel();
                    ch.channel = in.readInt();
                    ch.frequency = in.readInt();
                    ch.ibssAllowed = (in.readInt() == 1);
                    return ch;
                }

                public WifiChannel[] newArray(int size) {
                    return new WifiChannel[size];
                }
            };
}
