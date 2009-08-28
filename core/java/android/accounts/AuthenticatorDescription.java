package android.accounts;

import android.os.Parcelable;
import android.os.Parcel;

public class AuthenticatorDescription implements Parcelable {
    final public String type;
    final public int labelId;
    final public int iconId;
    final public String packageName;

    public AuthenticatorDescription(String type, String packageName, int labelId, int iconId) {
        if (type == null) throw new IllegalArgumentException("type cannot be null");
        if (packageName == null) throw new IllegalArgumentException("packageName cannot be null");
        this.type = type;
        this.packageName = packageName;
        this.labelId = labelId;
        this.iconId = iconId;
    }

    public static AuthenticatorDescription newKey(String type) {
        if (type == null) throw new IllegalArgumentException("type cannot be null");
        return new AuthenticatorDescription(type);
    }

    private AuthenticatorDescription(String type) {
        this.type = type;
        this.packageName = null;
        this.labelId = 0;
        this.iconId = 0;
    }

    private AuthenticatorDescription(Parcel source) {
        this.type = source.readString();
        this.packageName = source.readString();
        this.labelId = source.readInt();
        this.iconId = source.readInt();
    }

    public int describeContents() {
        return 0;
    }

    public int hashCode() {
        return type.hashCode();
    }

    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof AuthenticatorDescription)) return false;
        final AuthenticatorDescription other = (AuthenticatorDescription) o;
        return type.equals(other.type);
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(type);
        dest.writeString(packageName);
        dest.writeInt(labelId);
        dest.writeInt(iconId);
    }

    public static final Creator<AuthenticatorDescription> CREATOR =
            new Creator<AuthenticatorDescription>() {
        public AuthenticatorDescription createFromParcel(Parcel source) {
            return new AuthenticatorDescription(source);
        }

        public AuthenticatorDescription[] newArray(int size) {
            return new AuthenticatorDescription[size];
        }
    };
}
