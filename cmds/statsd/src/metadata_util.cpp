/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "FieldValue.h"
#include "metadata_util.h"

namespace android {
namespace os {
namespace statsd {

using google::protobuf::RepeatedPtrField;

void writeValueToProto(metadata::FieldValue* metadataFieldValue, const Value& value) {
    std::string storage_value;
    switch (value.getType()) {
        case INT:
            metadataFieldValue->set_value_int(value.int_value);
            break;
        case LONG:
            metadataFieldValue->set_value_long(value.long_value);
            break;
        case FLOAT:
            metadataFieldValue->set_value_float(value.float_value);
            break;
        case DOUBLE:
            metadataFieldValue->set_value_double(value.double_value);
            break;
        case STRING:
            metadataFieldValue->set_value_str(value.str_value.c_str());
            break;
        case STORAGE: // byte array
            storage_value = ((char*) value.storage_value.data());
            metadataFieldValue->set_value_storage(storage_value);
            break;
        default:
            break;
    }
}

void writeMetricDimensionKeyToMetadataDimensionKey(
        const MetricDimensionKey& metricKey,
        metadata::MetricDimensionKey* metadataMetricKey) {
    for (const FieldValue& fieldValue : metricKey.getDimensionKeyInWhat().getValues()) {
        metadata::FieldValue* metadataFieldValue = metadataMetricKey->add_dimension_key_in_what();
        metadata::Field* metadataField = metadataFieldValue->mutable_field();
        metadataField->set_tag(fieldValue.mField.getTag());
        metadataField->set_field(fieldValue.mField.getField());
        writeValueToProto(metadataFieldValue, fieldValue.mValue);
    }

    for (const FieldValue& fieldValue : metricKey.getStateValuesKey().getValues()) {
        metadata::FieldValue* metadataFieldValue = metadataMetricKey->add_state_values_key();
        metadata::Field* metadataField = metadataFieldValue->mutable_field();
        metadataField->set_tag(fieldValue.mField.getTag());
        metadataField->set_field(fieldValue.mField.getField());
        writeValueToProto(metadataFieldValue, fieldValue.mValue);
    }
}

void writeFieldValuesFromMetadata(
        const RepeatedPtrField<metadata::FieldValue>& repeatedFieldValueList,
        std::vector<FieldValue>* fieldValues) {
    for (const metadata::FieldValue& metadataFieldValue : repeatedFieldValueList) {
        Field field(metadataFieldValue.field().tag(), metadataFieldValue.field().field());
        Value value;
        switch (metadataFieldValue.value_case()) {
            case metadata::FieldValue::ValueCase::kValueInt:
                value = Value(metadataFieldValue.value_int());
                break;
            case metadata::FieldValue::ValueCase::kValueLong:
                value = Value(metadataFieldValue.value_long());
                break;
            case metadata::FieldValue::ValueCase::kValueFloat:
                value = Value(metadataFieldValue.value_float());
                break;
            case metadata::FieldValue::ValueCase::kValueDouble:
                value = Value(metadataFieldValue.value_double());
                break;
            case metadata::FieldValue::ValueCase::kValueStr:
                value = Value(metadataFieldValue.value_str());
                break;
            case metadata::FieldValue::ValueCase::kValueStorage:
                value = Value(metadataFieldValue.value_storage());
                break;
            default:
                break;
        }
        FieldValue fieldValue(field, value);
        fieldValues->emplace_back(field, value);
    }
}

MetricDimensionKey loadMetricDimensionKeyFromProto(
        const metadata::MetricDimensionKey& metricDimensionKey) {
    std::vector<FieldValue> dimKeyInWhatFieldValues;
    writeFieldValuesFromMetadata(metricDimensionKey.dimension_key_in_what(),
            &dimKeyInWhatFieldValues);
    std::vector<FieldValue> stateValuesFieldValues;
    writeFieldValuesFromMetadata(metricDimensionKey.state_values_key(), &stateValuesFieldValues);

    HashableDimensionKey dimKeyInWhat(dimKeyInWhatFieldValues);
    HashableDimensionKey stateValues(stateValuesFieldValues);
    MetricDimensionKey metricKey(dimKeyInWhat, stateValues);
    return metricKey;
}

}  // namespace statsd
}  // namespace os
}  // namespace android