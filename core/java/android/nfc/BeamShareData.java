package android.nfc;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Class to IPC data to be shared over Android Beam.
 * Allows bundling NdefMessage, Uris and flags in a single
 * IPC call. This is important as we want to reduce the
 * amount of IPC calls at "touch time".
 * @hide
 */
public final class BeamShareData implements Parcelable {
    public final NdefMessage ndefMessage;
    public final Uri[] uris;
    public final int flags;

    public BeamShareData(NdefMessage msg, Uri[] uris, int flags) {
        this.ndefMessage = msg;
        this.uris = uris;
        this.flags = flags;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        int urisLength = (uris != null) ? uris.length : 0;
        dest.writeParcelable(ndefMessage, 0);
        dest.writeInt(urisLength);
        if (urisLength > 0) {
            dest.writeTypedArray(uris, 0);
        }
        dest.writeInt(this.flags);
    }

    public static final Parcelable.Creator<BeamShareData> CREATOR =
            new Parcelable.Creator<BeamShareData>() {
        @Override
        public BeamShareData createFromParcel(Parcel source) {
            Uri[] uris = null;
            NdefMessage msg = source.readParcelable(NdefMessage.class.getClassLoader());
            int numUris = source.readInt();
            if (numUris > 0) {
                uris = new Uri[numUris];
                source.readTypedArray(uris, Uri.CREATOR);
            }
            int flags = source.readInt();

            return new BeamShareData(msg, uris, flags);
        }

        @Override
        public BeamShareData[] newArray(int size) {
            return new BeamShareData[size];
        }
    };
}
