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

#include <android-base/macros.h>
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

    DiagMessage(size_t line) : mSource(Source().withLine(line)) {
    }

    template <typename T>
    DiagMessage& operator<<(const T& value) {
        mMessage << value;
        return *this;
    }

    DiagMessageActual build() const {
        return DiagMessageActual{ mSource, mMessage.str() };
    }
};

struct IDiagnostics {
    virtual ~IDiagnostics() = default;

    enum class Level {
        Note,
        Warn,
        Error
    };

    virtual void log(Level level, DiagMessageActual& actualMsg) = 0;

    virtual void error(const DiagMessage& message) {
        DiagMessageActual actual = message.build();
        log(Level::Error, actual);
    }

    virtual void warn(const DiagMessage& message) {
        DiagMessageActual actual = message.build();
        log(Level::Warn, actual);
    }

    virtual void note(const DiagMessage& message) {
        DiagMessageActual actual = message.build();
        log(Level::Note, actual);
    }
};

class StdErrDiagnostics : public IDiagnostics {
public:
    StdErrDiagnostics() = default;

    void log(Level level, DiagMessageActual& actualMsg) override {
        const char* tag;

        switch (level) {
        case Level::Error:
            mNumErrors++;
            if (mNumErrors > 20) {
                return;
            }
            tag = "error";
            break;

        case Level::Warn:
            tag = "warn";
            break;

        case Level::Note:
            tag = "note";
            break;
        }

        if (!actualMsg.source.path.empty()) {
            std::cerr << actualMsg.source << ": ";
        }
        std::cerr << tag << ": " << actualMsg.message << "." << std::endl;
    }

private:
    size_t mNumErrors = 0;

    DISALLOW_COPY_AND_ASSIGN(StdErrDiagnostics);
};

class SourcePathDiagnostics : public IDiagnostics {
public:
    SourcePathDiagnostics(const Source& src, IDiagnostics* diag) : mSource(src), mDiag(diag) {
    }

    void log(Level level, DiagMessageActual& actualMsg) override {
        actualMsg.source.path = mSource.path;
        mDiag->log(level, actualMsg);
    }

private:
    Source mSource;
    IDiagnostics* mDiag;

    DISALLOW_COPY_AND_ASSIGN(SourcePathDiagnostics);
};

} // namespace aapt

#endif /* AAPT_DIAGNOSTICS_H */
