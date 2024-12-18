/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.vibrator.persistence;

import static com.android.internal.vibrator.persistence.XmlConstants.TAG_VENDOR_EFFECT;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.PersistableBundle;
import android.os.VibrationEffect;
import android.text.TextUtils;
import android.util.Base64;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Serialized representation of a {@link VibrationEffect.VendorEffect}.
 *
 * <p>The vibration is represented by an opaque {@link PersistableBundle} that can be used by
 * {@link VibrationEffect#createVendorEffect(PersistableBundle)} during the {@link #deserialize()}
 * procedure.
 *
 * @hide
 */
final class SerializedVendorEffect implements XmlSerializedVibration<VibrationEffect.VendorEffect> {

    @NonNull
    private final PersistableBundle mVendorData;

    SerializedVendorEffect(@NonNull PersistableBundle vendorData) {
        requireNonNull(vendorData);
        mVendorData = vendorData;
    }

    @SuppressLint("MissingPermission")
    @NonNull
    @Override
    public VibrationEffect.VendorEffect deserialize() {
        return (VibrationEffect.VendorEffect) VibrationEffect.createVendorEffect(mVendorData);
    }

    @Override
    public void write(@NonNull TypedXmlSerializer serializer)
            throws IOException {
        serializer.startTag(XmlConstants.NAMESPACE, XmlConstants.TAG_VIBRATION_EFFECT);
        writeContent(serializer);
        serializer.endTag(XmlConstants.NAMESPACE, XmlConstants.TAG_VIBRATION_EFFECT);
    }

    @Override
    public void writeContent(@NonNull TypedXmlSerializer serializer) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        mVendorData.writeToStream(outputStream);

        serializer.startTag(XmlConstants.NAMESPACE, XmlConstants.TAG_VENDOR_EFFECT);
        serializer.text(Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP));
        serializer.endTag(XmlConstants.NAMESPACE, XmlConstants.TAG_VENDOR_EFFECT);
    }

    @Override
    public String toString() {
        return "SerializedVendorEffect{"
                + "vendorData=" + mVendorData
                + '}';
    }

    /** Parser implementation for {@link SerializedVendorEffect}. */
    static final class Parser {

        @NonNull
        static SerializedVendorEffect parseNext(@NonNull TypedXmlPullParser parser,
                @XmlConstants.Flags int flags) throws XmlParserException, IOException {
            XmlValidator.checkStartTag(parser, TAG_VENDOR_EFFECT);
            XmlValidator.checkTagHasNoUnexpectedAttributes(parser);

            PersistableBundle vendorData;
            XmlReader.readNextText(parser, TAG_VENDOR_EFFECT);

            try {
                String text = parser.getText().trim();
                XmlValidator.checkParserCondition(!text.isEmpty(),
                        "Expected tag %s to have base64 representation of vendor data, got empty",
                        TAG_VENDOR_EFFECT);

                vendorData = PersistableBundle.readFromStream(
                        new ByteArrayInputStream(Base64.decode(text, Base64.DEFAULT)));
                XmlValidator.checkParserCondition(!vendorData.isEmpty(),
                        "Expected tag %s to have non-empty vendor data, got empty bundle",
                        TAG_VENDOR_EFFECT);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new XmlParserException(
                        TextUtils.formatSimple(
                                "Expected base64 representation of vendor data in tag %s, got %s",
                                TAG_VENDOR_EFFECT, parser.getText()),
                        e);
            } catch (IOException e) {
                throw new XmlParserException("Error reading vendor data from decoded bytes", e);
            }

            // Consume tag
            XmlReader.readEndTag(parser);

            return new SerializedVendorEffect(vendorData);
        }
    }
}
