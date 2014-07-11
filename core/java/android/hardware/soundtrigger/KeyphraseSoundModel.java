package android.hardware.soundtrigger;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.UUID;

/**
 * A KeyphraseSoundModel is a sound model capable of detecting voice keyphrases.
 * It contains data needed by the hardware to detect a given number of key phrases
 * and the list of corresponding {@link Keyphrase}s.
 *
 * @hide
 */
public class KeyphraseSoundModel implements Parcelable {

    /** Key phrases in this sound model */
    public final Keyphrase[] keyphrases;
    public final byte[] data;
    public final UUID uuid;

    public static final Parcelable.Creator<KeyphraseSoundModel> CREATOR
            = new Parcelable.Creator<KeyphraseSoundModel>() {
        public KeyphraseSoundModel createFromParcel(Parcel in) {
            return KeyphraseSoundModel.fromParcel(in);
        }

        public KeyphraseSoundModel[] newArray(int size) {
            return new KeyphraseSoundModel[size];
        }
    };

    public KeyphraseSoundModel(UUID uuid, byte[] data,Keyphrase[] keyPhrases) {
        this.uuid = uuid;
        this.data = data;
        this.keyphrases = keyPhrases;
    }

    private static KeyphraseSoundModel fromParcel(Parcel in) {
        UUID uuid = UUID.fromString(in.readString());
        int dataLength = in.readInt();
        byte[] data = null;
        if (dataLength > 0) {
            data = new byte[in.readInt()];
            in.readByteArray(data);
        }
        Keyphrase[] keyphrases =
                (Keyphrase[]) in.readParcelableArray(Keyphrase.class.getClassLoader());
        return new KeyphraseSoundModel(uuid, data, keyphrases);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(uuid.toString());
        if (data != null) {
            dest.writeInt(data.length);
            dest.writeByteArray(data);
        } else {
            dest.writeInt(0);
        }
        dest.writeParcelableArray(keyphrases, 0);
    }
}
