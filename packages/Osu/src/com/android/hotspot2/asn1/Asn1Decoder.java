package com.android.hotspot2.asn1;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class Asn1Decoder {
    public static final int TAG_UNIVZERO = 0x00;
    public static final int TAG_BOOLEAN = 0x01;
    public static final int TAG_INTEGER = 0x02;
    public static final int TAG_BITSTRING = 0x03;
    public static final int TAG_OCTET_STRING = 0x04;
    public static final int TAG_NULL = 0x05;
    public static final int TAG_OID = 0x06;
    public static final int TAG_ObjectDescriptor = 0x07;
    public static final int TAG_EXTERNAL = 0x08;
    public static final int TAG_REAL = 0x09;
    public static final int TAG_ENUMERATED = 0x0a;
    public static final int TAG_UTF8String = 0x0c;      // * (*) are X.509 DirectoryString's
    public static final int TAG_RelativeOID = 0x0d;
    public static final int TAG_SEQ = 0x10;             //   30 if constructed
    public static final int TAG_SET = 0x11;
    public static final int TAG_NumericString = 0x12;   //   [UNIVERSAL 18]
    public static final int TAG_PrintableString = 0x13; // * [UNIVERSAL 19]
    public static final int TAG_T61String = 0x14;       // * TeletexString [UNIVERSAL 20]
    public static final int TAG_VideotexString = 0x15;  //   [UNIVERSAL 21]
    public static final int TAG_IA5String = 0x16;       //   [UNIVERSAL 22]
    public static final int TAG_UTCTime = 0x17;
    public static final int TAG_GeneralizedTime = 0x18;
    public static final int TAG_GraphicString = 0x19;   //   [UNIVERSAL 25]
    public static final int TAG_VisibleString = 0x1a;   //   ISO64String [UNIVERSAL 26]
    public static final int TAG_GeneralString = 0x1b;   //   [UNIVERSAL 27]
    public static final int TAG_UniversalString = 0x1c; // * [UNIVERSAL 28]
    public static final int TAG_BMPString = 0x1e;       // * [UNIVERSAL 30]

    public static final int IntOverflow = 0xffff0000;
    public static final int MoreBit = 0x80;
    public static final int MoreData = 0x7f;
    public static final int ConstructedBit = 0x20;
    public static final int ClassShift = 6;
    public static final int ClassMask = 0x3;
    public static final int MoreWidth = 7;
    public static final int ByteWidth = 8;
    public static final int ByteMask = 0xff;
    public static final int ContinuationTag = 31;

    public static final int IndefiniteLength = -1;

    private static final Map<Integer, Asn1Tag> sTagMap = new HashMap<>();

    static {
        sTagMap.put(TAG_UNIVZERO, Asn1Tag.UNIVZERO);
        sTagMap.put(TAG_BOOLEAN, Asn1Tag.BOOLEAN);
        sTagMap.put(TAG_INTEGER, Asn1Tag.INTEGER);
        sTagMap.put(TAG_BITSTRING, Asn1Tag.BITSTRING);
        sTagMap.put(TAG_OCTET_STRING, Asn1Tag.OCTET_STRING);
        sTagMap.put(TAG_NULL, Asn1Tag.NULL);
        sTagMap.put(TAG_OID, Asn1Tag.OID);
        sTagMap.put(TAG_ObjectDescriptor, Asn1Tag.ObjectDescriptor);
        sTagMap.put(TAG_EXTERNAL, Asn1Tag.EXTERNAL);
        sTagMap.put(TAG_REAL, Asn1Tag.REAL);
        sTagMap.put(TAG_ENUMERATED, Asn1Tag.ENUMERATED);
        sTagMap.put(TAG_UTF8String, Asn1Tag.UTF8String);
        sTagMap.put(TAG_RelativeOID, Asn1Tag.RelativeOID);
        sTagMap.put(TAG_SEQ, Asn1Tag.SEQUENCE);
        sTagMap.put(TAG_SET, Asn1Tag.SET);
        sTagMap.put(TAG_NumericString, Asn1Tag.NumericString);
        sTagMap.put(TAG_PrintableString, Asn1Tag.PrintableString);
        sTagMap.put(TAG_T61String, Asn1Tag.T61String);
        sTagMap.put(TAG_VideotexString, Asn1Tag.VideotexString);
        sTagMap.put(TAG_IA5String, Asn1Tag.IA5String);
        sTagMap.put(TAG_UTCTime, Asn1Tag.UTCTime);
        sTagMap.put(TAG_GeneralizedTime, Asn1Tag.GeneralizedTime);
        sTagMap.put(TAG_GraphicString, Asn1Tag.GraphicString);
        sTagMap.put(TAG_VisibleString, Asn1Tag.VisibleString);
        sTagMap.put(TAG_GeneralString, Asn1Tag.GeneralString);
        sTagMap.put(TAG_UniversalString, Asn1Tag.UniversalString);
        sTagMap.put(TAG_BMPString, Asn1Tag.BMPString);
    }

    public static Asn1Tag mapTag(int tag) {
        return sTagMap.get(tag);
    }

    public static Collection<Asn1Object> decode(ByteBuffer data) throws DecodeException {
        Asn1Constructed root =
                new Asn1Constructed(0, null, data.remaining(), data, data.position());
        decode(0, root);
        return root.getChildren();
    }

    private static void decode(int level, Asn1Constructed parent) throws DecodeException {
        ByteBuffer data = parent.getPayload();
        while (data.hasRemaining()) {
            int tagPosition = data.position();
            int propMask = data.get(tagPosition) & ByteMask;
            if (propMask == 0 && parent.isIndefiniteLength() && data.get(tagPosition + 1) == 0) {
                parent.setEndOfData(tagPosition);
                return;
            }
            Asn1Class asn1Class = Asn1Class.values()[(propMask >> ClassShift) & ClassMask];
            boolean constructed = (propMask & ConstructedBit) != 0;

            int tag = decodeTag(data);
            int length = decodeLength(data);

            if (constructed) {
                ByteBuffer payload = peelOff(data, length);
                Asn1Constructed root =
                        new Asn1Constructed(tag, asn1Class, length, payload, tagPosition);
                decode(level + 1, root);
                if (length == IndefiniteLength) {
                    data.position(root.getEndOfData() + 2);     // advance past '00'
                }
                parent.addChild(root);
            } else {
                if (asn1Class != Asn1Class.Universal) {
                    parent.addChild(new Asn1Octets(tag, asn1Class, length, data));
                } else {
                    parent.addChild(buildScalar(tag, asn1Class, length, data));
                }
            }
        }
    }

    private static ByteBuffer peelOff(ByteBuffer base, int length) {
        ByteBuffer copy = base.duplicate();
        if (length == IndefiniteLength) {
            return copy;
        }
        copy.limit(copy.position() + length);
        base.position(base.position() + length);
        return copy;
    }

    private static Asn1Object buildScalar(int tag, Asn1Class asn1Class, int length, ByteBuffer data)
            throws DecodeException {
        switch (tag) {
            case TAG_BOOLEAN:
                return new Asn1Boolean(tag, asn1Class, length, data);
            case TAG_INTEGER:
            case TAG_ENUMERATED:
                return new Asn1Integer(tag, asn1Class, length, data);
            case TAG_BITSTRING:
                int bitResidual = data.get() & ByteMask;
                return new Asn1Octets(tag, asn1Class, length, data, bitResidual);
            case TAG_OCTET_STRING:
                return new Asn1Octets(tag, asn1Class, length, data);
            case TAG_OID:
                return new Asn1Oid(tag, asn1Class, length, data);
            case TAG_UTF8String:
            case TAG_NumericString:
            case TAG_PrintableString:
            case TAG_T61String:
            case TAG_VideotexString:
            case TAG_IA5String:
            case TAG_GraphicString:
            case TAG_VisibleString:
            case TAG_GeneralString:
            case TAG_UniversalString:
            case TAG_BMPString:
                return new Asn1String(tag, asn1Class, length, data);
            case TAG_GeneralizedTime:
            case TAG_UTCTime:
                // Should really be a dedicated time object
                return new Asn1String(tag, asn1Class, length, data);
            default:
                return new Asn1Octets(tag, asn1Class, length, data);
        }
    }

    private static int decodeTag(ByteBuffer data) throws DecodeException {
        int tag;
        byte tag0 = data.get();

        if ((tag = (tag0 & ContinuationTag)) == ContinuationTag) {
            int tagByte;
            tag = 0;
            while (((tagByte = data.get() & ByteMask) & MoreBit) != 0) {
                tag = (tag << MoreWidth) | (tagByte & MoreData);
                if ((tag & IntOverflow) != 0)
                    throw new DecodeException("Tag overflow", data.position());
            }
            tag = (tag << MoreWidth) | tagByte;
        }
        return tag;
    }

    private static int decodeLength(ByteBuffer data) throws DecodeException {
        int length;
        int lenlen = data.get() & ByteMask;

        if ((lenlen & MoreBit) == 0)    // One byte encoding
            length = lenlen;
        else {
            lenlen &= MoreData;
            if (lenlen == 0) {
                return IndefiniteLength;
            }
            length = 0;
            while (lenlen-- > 0) {
                length = (length << ByteWidth) | (data.get() & ByteMask);
                if ((length & IntOverflow) != 0 && lenlen > 0)
                    throw new DecodeException("Length overflow", data.position());
            }
        }
        return length;
    }

}
