/*
 * Copyright (C) 2017 The Android Open Source Project
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
#define LOG_TAG "incident_helper"

#include <android/util/ProtoOutputStream.h>

#include "frameworks/base/core/proto/android/os/system_properties.proto.h"
#include "ih_util.h"
#include "SystemPropertiesParser.h"

using namespace android::os;

const string LINE_DELIMITER = "]: [";

// system properties' names sometimes are not valid proto field names, make the names valid.
static string convertToFieldName(const string& name) {
    int len = (int)name.length();
    char cstr[len + 1];
    strcpy(cstr, name.c_str());
    for (int i = 0; i < len; i++) {
        if (!isValidChar(cstr[i])) {
            cstr[i] = '_';
        }
    }
    return string(cstr);
}

status_t
SystemPropertiesParser::Parse(const int in, const int out) const
{
    Reader reader(in);
    string line;
    string name;  // the name of the property
    string value; // the string value of the property
    ProtoOutputStream proto;
    vector<pair<string, string>> extras;

    Table sysPropTable(SystemPropertiesProto::_FIELD_NAMES,
                SystemPropertiesProto::_FIELD_IDS,
                SystemPropertiesProto::_FIELD_COUNT);
    Message sysProp(&sysPropTable);

    Table aacDrcTable(SystemPropertiesProto::AacDrc::_FIELD_NAMES,
            SystemPropertiesProto::AacDrc::_FIELD_IDS,
            SystemPropertiesProto::AacDrc::_FIELD_COUNT);
    Message aacDrc(&aacDrcTable);
    sysProp.addSubMessage(SystemPropertiesProto::AAC_DRC, &aacDrc);

    Table aaudioTable(SystemPropertiesProto::Aaudio::_FIELD_NAMES,
            SystemPropertiesProto::Aaudio::_FIELD_IDS,
            SystemPropertiesProto::Aaudio::_FIELD_COUNT);
    Message aaudio(&aaudioTable);
    sysProp.addSubMessage(SystemPropertiesProto::AAUDIO, &aaudio);

    Table cameraTable(SystemPropertiesProto::Camera::_FIELD_NAMES,
            SystemPropertiesProto::Camera::_FIELD_IDS,
            SystemPropertiesProto::Camera::_FIELD_COUNT);
    Message camera(&cameraTable);
    sysProp.addSubMessage(SystemPropertiesProto::CAMERA, &camera);

    Table dalvikVmTable(SystemPropertiesProto::DalvikVm::_FIELD_NAMES,
            SystemPropertiesProto::DalvikVm::_FIELD_IDS,
            SystemPropertiesProto::DalvikVm::_FIELD_COUNT);
    Message dalvikVm(&dalvikVmTable);
    sysProp.addSubMessage(SystemPropertiesProto::DALVIK_VM, &dalvikVm);

    Table initSvcTable(SystemPropertiesProto::InitSvc::_FIELD_NAMES,
            SystemPropertiesProto::InitSvc::_FIELD_IDS,
            SystemPropertiesProto::InitSvc::_FIELD_COUNT);
    initSvcTable.addEnumNameToValue("running", SystemPropertiesProto::InitSvc::STATUS_RUNNING);
    initSvcTable.addEnumNameToValue("stopped", SystemPropertiesProto::InitSvc::STATUS_STOPPED);
    Message initSvc(&initSvcTable);
    sysProp.addSubMessage(SystemPropertiesProto::INIT_SVC, &initSvc);

    Table logTable(SystemPropertiesProto::Log::_FIELD_NAMES,
            SystemPropertiesProto::Log::_FIELD_IDS,
            SystemPropertiesProto::Log::_FIELD_COUNT);
    Message logMsg(&logTable);
    sysProp.addSubMessage(SystemPropertiesProto::LOG, &logMsg);

    Table persistTable(SystemPropertiesProto::Persist::_FIELD_NAMES,
            SystemPropertiesProto::Persist::_FIELD_IDS,
            SystemPropertiesProto::Persist::_FIELD_COUNT);
    Message persist(&persistTable);
    sysProp.addSubMessage(SystemPropertiesProto::PERSIST, &persist);

    Table pmDexoptTable(SystemPropertiesProto::PmDexopt::_FIELD_NAMES,
            SystemPropertiesProto::PmDexopt::_FIELD_IDS,
            SystemPropertiesProto::PmDexopt::_FIELD_COUNT);
    Message pmDexopt(&pmDexoptTable);
    sysProp.addSubMessage(SystemPropertiesProto::PM_DEXOPT, &pmDexopt);

    Table roTable(SystemPropertiesProto::Ro::_FIELD_NAMES,
            SystemPropertiesProto::Ro::_FIELD_IDS,
            SystemPropertiesProto::Ro::_FIELD_COUNT);
    Message ro(&roTable);

    Table bootTable(SystemPropertiesProto::Ro::Boot::_FIELD_NAMES,
            SystemPropertiesProto::Ro::Boot::_FIELD_IDS,
            SystemPropertiesProto::Ro::Boot::_FIELD_COUNT);
    Message boot(&bootTable);
    ro.addSubMessage(SystemPropertiesProto::Ro::BOOT, &boot);

    Table bootimageTable(SystemPropertiesProto::Ro::BootImage::_FIELD_NAMES,
            SystemPropertiesProto::Ro::BootImage::_FIELD_IDS,
            SystemPropertiesProto::Ro::BootImage::_FIELD_COUNT);
    Message bootimage(&bootimageTable);
    ro.addSubMessage(SystemPropertiesProto::Ro::BOOTIMAGE, &bootimage);

    Table buildTable(SystemPropertiesProto::Ro::Build::_FIELD_NAMES,
            SystemPropertiesProto::Ro::Build::_FIELD_IDS,
            SystemPropertiesProto::Ro::Build::_FIELD_COUNT);
    Message build(&buildTable);

    Table versionTable(SystemPropertiesProto::Ro::Build::Version::_FIELD_NAMES,
            SystemPropertiesProto::Ro::Build::Version::_FIELD_IDS,
            SystemPropertiesProto::Ro::Build::Version::_FIELD_COUNT);
    Message version(&versionTable);
    build.addSubMessage(SystemPropertiesProto::Ro::Build::VERSION, &version);
    ro.addSubMessage(SystemPropertiesProto::Ro::BUILD, &build);

    Table configTable(SystemPropertiesProto::Ro::Config::_FIELD_NAMES,
            SystemPropertiesProto::Ro::Config::_FIELD_IDS,
            SystemPropertiesProto::Ro::Config::_FIELD_COUNT);
    Message config(&configTable);
    ro.addSubMessage(SystemPropertiesProto::Ro::CONFIG, &config);

    Table hardwareTable(SystemPropertiesProto::Ro::Hardware::_FIELD_NAMES,
                   SystemPropertiesProto::Ro::Hardware::_FIELD_IDS,
                   SystemPropertiesProto::Ro::Hardware::_FIELD_COUNT);
    Message hardware(&hardwareTable);
    ro.addSubMessage(SystemPropertiesProto::Ro::HARDWARE, &hardware);

    Table productTable(SystemPropertiesProto::Ro::Product::_FIELD_NAMES,
                   SystemPropertiesProto::Ro::Product::_FIELD_IDS,
                   SystemPropertiesProto::Ro::Product::_FIELD_COUNT);
    Message product(&productTable);

    Table pVendorTable(SystemPropertiesProto::Ro::Product::Vendor::_FIELD_NAMES,
            SystemPropertiesProto::Ro::Product::Vendor::_FIELD_IDS,
            SystemPropertiesProto::Ro::Product::Vendor::_FIELD_COUNT);
    Message pVendor(&pVendorTable);
    product.addSubMessage(SystemPropertiesProto::Ro::Product::VENDOR, &pVendor);
    ro.addSubMessage(SystemPropertiesProto::Ro::PRODUCT, &product);

    Table telephonyTable(SystemPropertiesProto::Ro::Telephony::_FIELD_NAMES,
                   SystemPropertiesProto::Ro::Telephony::_FIELD_IDS,
                   SystemPropertiesProto::Ro::Telephony::_FIELD_COUNT);
    Message telephony(&telephonyTable);
    ro.addSubMessage(SystemPropertiesProto::Ro::TELEPHONY, &telephony);

    Table vendorTable(SystemPropertiesProto::Ro::Vendor::_FIELD_NAMES,
                   SystemPropertiesProto::Ro::Vendor::_FIELD_IDS,
                   SystemPropertiesProto::Ro::Vendor::_FIELD_COUNT);
    Message vendor(&vendorTable);
    ro.addSubMessage(SystemPropertiesProto::Ro::VENDOR, &vendor);

    sysProp.addSubMessage(SystemPropertiesProto::RO, &ro);

    Table sysTable(SystemPropertiesProto::Sys::_FIELD_NAMES,
                   SystemPropertiesProto::Sys::_FIELD_IDS,
                   SystemPropertiesProto::Sys::_FIELD_COUNT);
    Message sys(&sysTable);

    Table usbTable(SystemPropertiesProto::Sys::Usb::_FIELD_NAMES,
                   SystemPropertiesProto::Sys::Usb::_FIELD_IDS,
                   SystemPropertiesProto::Sys::Usb::_FIELD_COUNT);
    Message usb(&usbTable);
    sys.addSubMessage(SystemPropertiesProto::Sys::USB, &usb);

    sysProp.addSubMessage(SystemPropertiesProto::SYS, &sys);

    // parse line by line
    while (reader.readLine(&line)) {
        if (line.empty()) continue;

        line = line.substr(1, line.size() - 2); // trim []
        size_t index = line.find(LINE_DELIMITER); // split by "]: ["
        if (index == string::npos) {
            fprintf(stderr, "Bad Line %s\n", line.c_str());
            continue;
        }
        name = line.substr(0, index);
        value = trim(line.substr(index + 4), DEFAULT_WHITESPACE);
        if (value.empty()) continue;

        // if the property name couldn't be found in proto definition or the value has mistype,
        // add to extra properties with its name and value
        if (!sysProp.insertField(&proto, convertToFieldName(name), value)) {
            extras.push_back(make_pair(name, value));
        }
    }
    // end session for the last write.
    sysProp.endSession(&proto);

    for (auto it = extras.begin(); it != extras.end(); it++) {
        uint64_t token = proto.start(SystemPropertiesProto::EXTRA_PROPERTIES);
        proto.write(SystemPropertiesProto::Property::NAME, it->first);
        proto.write(SystemPropertiesProto::Property::VALUE, it->second);
        proto.end(token);
    }

    if (!reader.ok(&line)) {
        fprintf(stderr, "Bad read from fd %d: %s\n", in, line.c_str());
        return -1;
    }

    if (!proto.flush(out)) {
        fprintf(stderr, "[%s]Error writing proto back\n", this->name.c_str());
        return -1;
    }
    fprintf(stderr, "[%s]Proto size: %zu bytes\n", this->name.c_str(), proto.size());
    return NO_ERROR;
}
