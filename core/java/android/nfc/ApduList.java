package android.nfc;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * @hide
 */
public class ApduList implements Parcelable {

    private ArrayList<byte[]> commands = new ArrayList<byte[]>();

    public ApduList() {
    }

    public void add(byte[] command) {
        commands.add(command);
    }

    public List<byte[]> get() {
        return commands;
    }

    public static final Parcelable.Creator<ApduList> CREATOR =
        new Parcelable.Creator<ApduList>() {
        @Override
        public ApduList createFromParcel(Parcel in) {
            return new ApduList(in);
        }

        @Override
        public ApduList[] newArray(int size) {
            return new ApduList[size];
        }
    };

    private ApduList(Parcel in) {
        int count = in.readInt();

        for (int i = 0 ; i < count ; i++) {

            int length = in.readInt();
            byte[] cmd = new byte[length];
            in.readByteArray(cmd);
            commands.add(cmd);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(commands.size());

        for (byte[] cmd : commands) {
            dest.writeInt(cmd.length);
            dest.writeByteArray(cmd);
        }
    }
}


