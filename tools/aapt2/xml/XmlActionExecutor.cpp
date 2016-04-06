/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "xml/XmlActionExecutor.h"

namespace aapt {
namespace xml {

static bool wrapperOne(XmlNodeAction::ActionFunc& f, Element* el, SourcePathDiagnostics*) {
    return f(el);
}

static bool wrapperTwo(XmlNodeAction::ActionFuncWithDiag& f, Element* el,
                       SourcePathDiagnostics* diag) {
    return f(el, diag);
}

void XmlNodeAction::action(XmlNodeAction::ActionFunc f) {
    mActions.emplace_back(std::bind(wrapperOne, std::move(f),
                                    std::placeholders::_1,
                                    std::placeholders::_2));
}

void XmlNodeAction::action(XmlNodeAction::ActionFuncWithDiag f) {
    mActions.emplace_back(std::bind(wrapperTwo, std::move(f),
                                    std::placeholders::_1,
                                    std::placeholders::_2));
}

static void printElementToDiagMessage(const Element* el, DiagMessage* msg) {
    *msg << "<";
    if (!el->namespaceUri.empty()) {
        *msg << el->namespaceUri << ":";
    }
    *msg << el->name << ">";
}

bool XmlNodeAction::execute(XmlActionExecutorPolicy policy, SourcePathDiagnostics* diag,
                            Element* el) const {
    bool error = false;
    for (const ActionFuncWithDiag& action : mActions) {
        error |= !action(el, diag);
    }

    for (Element* childEl : el->getChildElements()) {
        if (childEl->namespaceUri.empty()) {
            std::map<std::u16string, XmlNodeAction>::const_iterator iter =
                    mMap.find(childEl->name);
            if (iter != mMap.end()) {
                error |= !iter->second.execute(policy, diag, childEl);
                continue;
            }
        }

        if (policy == XmlActionExecutorPolicy::Whitelist) {
            DiagMessage errorMsg(childEl->lineNumber);
            errorMsg << "unknown element ";
            printElementToDiagMessage(childEl, &errorMsg);
            errorMsg << " found";
            diag->error(errorMsg);
            error = true;
        }
    }
    return !error;
}

bool XmlActionExecutor::execute(XmlActionExecutorPolicy policy, IDiagnostics* diag,
                                XmlResource* doc) const {
    SourcePathDiagnostics sourceDiag(doc->file.source, diag);

    Element* el = findRootElement(doc);
    if (!el) {
        if (policy == XmlActionExecutorPolicy::Whitelist) {
            sourceDiag.error(DiagMessage() << "no root XML tag found");
            return false;
        }
        return true;
    }

    if (el->namespaceUri.empty()) {
        std::map<std::u16string, XmlNodeAction>::const_iterator iter = mMap.find(el->name);
        if (iter != mMap.end()) {
            return iter->second.execute(policy, &sourceDiag, el);
        }
    }

    if (policy == XmlActionExecutorPolicy::Whitelist) {
        DiagMessage errorMsg(el->lineNumber);
        errorMsg << "unknown element ";
        printElementToDiagMessage(el, &errorMsg);
        errorMsg << " found";
        sourceDiag.error(errorMsg);
        return false;
    }
    return true;
}

} // namespace xml
} // namespace aapt
