/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_DIAGNOSTICS_H
#define AAPT_DIAGNOSTICS_H

#include "Source.h"

#include "util/StringPiece.h"
#include "util/Util.h"

#include <iostream>
#include <sstream>
#include <string>

namespace aapt {

struct DiagMessageActual {
    Source source;
    std::string message;
};

struct DiagMessage {
private:
    Source mSource;
    std::stringstream mMessage;

public:
    DiagMessage() = default;

    DiagMessage(const StringPiece& src) : mSource(src) {
    }

    DiagMessage(const Source& src) : mSource(src) {
    }

    template <typename T> DiagMessage& operator<<(const T& value) {
        mMessage << value;
        return *this;
    }
/*
    template <typename T> DiagMessage& operator<<(
            const ::std::function<::std::ostream&(::std::ostream&)>& f) {
        f(mMessage);
        return *this;
    }*/

    DiagMessageActual build() const {
        return DiagMessageActual{ mSource, mMessage.str() };
    }
};

struct IDiagnostics {
    virtual ~IDiagnostics() = default;

    virtual void error(const DiagMessage& message) = 0;
    virtual void warn(const DiagMessage& message) = 0;
    virtual void note(const DiagMessage& message) = 0;
};

struct StdErrDiagnostics : public IDiagnostics {
    void emit(const DiagMessage& msg, const char* tag) {
        DiagMessageActual actual = msg.build();
        if (!actual.source.path.empty()) {
            std::cerr << actual.source << ": ";
        }
        std::cerr << tag << actual.message << "." << std::endl;
    }

    void error(const DiagMessage& msg) override {
        emit(msg, "error: ");
    }

    void warn(const DiagMessage& msg) override {
        emit(msg, "warn: ");
    }

    void note(const DiagMessage& msg) override {
        emit(msg, "note: ");
    }
};

} // namespace aapt

#endif /* AAPT_DIAGNOSTICS_H */
