package android.content.pm;

import org.xmlpull.v1.XmlSerializer;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import android.os.Parcel;

import java.io.IOException;

/** @hide */
public interface XmlSerializerAndParser<T> {
    void writeAsXml(T item, XmlSerializer out) throws IOException;
    T createFromXml(XmlPullParser parser) throws IOException, XmlPullParserException;
}
