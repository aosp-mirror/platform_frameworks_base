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

#ifndef AAPT_JAVA_MANIFESTCLASSGENERATOR_H
#define AAPT_JAVA_MANIFESTCLASSGENERATOR_H

#include "Diagnostics.h"
#include "java/ClassDefinition.h"
#include "util/StringPiece.h"
#include "xml/XmlDom.h"

#include <iostream>

namespace aapt {

std::unique_ptr<ClassDefinition> generateManifestClass(IDiagnostics* diag, xml::XmlResource* res);

} // namespace aapt

#endif /* AAPT_JAVA_MANIFESTCLASSGENERATOR_H */
