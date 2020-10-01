/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

@RunWith(JUnit4.class)
public final class StatsDimensionsValueTest {

    @Test
    public void testConversionFromStructuredParcel() {
        int tupleField = 100; // atom id
        String stringValue = "Hello";
        int intValue = 123;
        long longValue = 123456789L;
        float floatValue = 1.1f;
        boolean boolValue = true;

        // Construct structured parcel
        StatsDimensionsValueParcel sdvp = new StatsDimensionsValueParcel();
        sdvp.field = tupleField;
        sdvp.valueType = StatsDimensionsValue.TUPLE_VALUE_TYPE;
        sdvp.tupleValue = new StatsDimensionsValueParcel[5];

        for (int i = 0; i < 5; i++) {
            sdvp.tupleValue[i] = new StatsDimensionsValueParcel();
            sdvp.tupleValue[i].field = i + 1;
        }

        sdvp.tupleValue[0].valueType = StatsDimensionsValue.STRING_VALUE_TYPE;
        sdvp.tupleValue[1].valueType = StatsDimensionsValue.INT_VALUE_TYPE;
        sdvp.tupleValue[2].valueType = StatsDimensionsValue.LONG_VALUE_TYPE;
        sdvp.tupleValue[3].valueType = StatsDimensionsValue.FLOAT_VALUE_TYPE;
        sdvp.tupleValue[4].valueType = StatsDimensionsValue.BOOLEAN_VALUE_TYPE;

        sdvp.tupleValue[0].stringValue = stringValue;
        sdvp.tupleValue[1].intValue = intValue;
        sdvp.tupleValue[2].longValue = longValue;
        sdvp.tupleValue[3].floatValue = floatValue;
        sdvp.tupleValue[4].boolValue = boolValue;

        // Convert to StatsDimensionsValue and check result
        StatsDimensionsValue sdv = new StatsDimensionsValue(sdvp);

        assertThat(sdv.getField()).isEqualTo(tupleField);
        assertThat(sdv.getValueType()).isEqualTo(StatsDimensionsValue.TUPLE_VALUE_TYPE);
        List<StatsDimensionsValue> sdvChildren = sdv.getTupleValueList();
        assertThat(sdvChildren.size()).isEqualTo(5);

        for (int i = 0; i < 5; i++) {
            assertThat(sdvChildren.get(i).getField()).isEqualTo(i + 1);
        }

        assertThat(sdvChildren.get(0).getValueType())
              .isEqualTo(StatsDimensionsValue.STRING_VALUE_TYPE);
        assertThat(sdvChildren.get(1).getValueType())
              .isEqualTo(StatsDimensionsValue.INT_VALUE_TYPE);
        assertThat(sdvChildren.get(2).getValueType())
              .isEqualTo(StatsDimensionsValue.LONG_VALUE_TYPE);
        assertThat(sdvChildren.get(3).getValueType())
              .isEqualTo(StatsDimensionsValue.FLOAT_VALUE_TYPE);
        assertThat(sdvChildren.get(4).getValueType())
              .isEqualTo(StatsDimensionsValue.BOOLEAN_VALUE_TYPE);

        assertThat(sdvChildren.get(0).getStringValue()).isEqualTo(stringValue);
        assertThat(sdvChildren.get(1).getIntValue()).isEqualTo(intValue);
        assertThat(sdvChildren.get(2).getLongValue()).isEqualTo(longValue);
        assertThat(sdvChildren.get(3).getFloatValue()).isEqualTo(floatValue);
        assertThat(sdvChildren.get(4).getBooleanValue()).isEqualTo(boolValue);

        // Ensure that StatsDimensionsValue and StatsDimensionsValueParcel are
        // parceled equivalently
        Parcel sdvpParcel = Parcel.obtain();
        Parcel sdvParcel = Parcel.obtain();
        sdvp.writeToParcel(sdvpParcel, 0);
        sdv.writeToParcel(sdvParcel, 0);
        assertThat(sdvpParcel.dataSize()).isEqualTo(sdvParcel.dataSize());
    }

    @Test
    public void testNullTupleArray() {
        int tupleField = 100; // atom id

        StatsDimensionsValueParcel parcel = new StatsDimensionsValueParcel();
        parcel.field = tupleField;
        parcel.valueType = StatsDimensionsValue.TUPLE_VALUE_TYPE;
        parcel.tupleValue = null;

        StatsDimensionsValue sdv = new StatsDimensionsValue(parcel);
        assertThat(sdv.getField()).isEqualTo(tupleField);
        assertThat(sdv.getValueType()).isEqualTo(StatsDimensionsValue.TUPLE_VALUE_TYPE);
        List<StatsDimensionsValue> sdvChildren = sdv.getTupleValueList();
        assertThat(sdvChildren.size()).isEqualTo(0);
    }
}
