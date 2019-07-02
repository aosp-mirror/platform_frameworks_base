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

#include "generic_message.h"
#include "printer.h"

#include <frameworks/base/core/proto/android/os/incident.pb.h>
#include <google/protobuf/wire_format.h>
#include <google/protobuf/io/coded_stream.h>
#include <google/protobuf/io/zero_copy_stream_impl.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

using namespace android::os;
using namespace google::protobuf;
using namespace google::protobuf::io;
using namespace google::protobuf::internal;

static bool read_message(CodedInputStream* in, Descriptor const* descriptor,
        GenericMessage* message);
static void print_message(Out* out, Descriptor const* descriptor, GenericMessage const* message);

// ================================================================================
static bool
read_length_delimited(CodedInputStream* in, uint32 fieldId, Descriptor const* descriptor,
        GenericMessage* message)
{
    uint32_t size;
    if (!in->ReadVarint32(&size)) {
        fprintf(stderr, "Fail to read size of %s\n", descriptor->name().c_str());
        return false;
    }

    FieldDescriptor const* field = descriptor->FindFieldByNumber(fieldId);
    if (field != NULL) {
        int type = field->type();
        if (type == FieldDescriptor::TYPE_MESSAGE) {
            GenericMessage* child = message->addMessage(fieldId);

            CodedInputStream::Limit limit = in->PushLimit(size);
            bool rv = read_message(in, field->message_type(), child);
            in->PopLimit(limit);
            return rv;
        } else if (type == FieldDescriptor::TYPE_STRING) {
            // TODO: do a version of readstring that just pumps the data
            // rather than allocating a string which we don't care about.
            string str;
            if (in->ReadString(&str, size)) {
                message->addString(fieldId, str);
                return true;
            } else {
                fprintf(stderr, "Fail to read string of field %s, expect size %d, read %lu\n",
                        field->full_name().c_str(), size, str.size());
                fprintf(stderr, "String read \"%s\"\n", str.c_str());
                return false;
            }
        } else if (type == FieldDescriptor::TYPE_BYTES) {
            // TODO: Save bytes field.
            return in->Skip(size);
        }
    }
    return in->Skip(size);
}

// ================================================================================
static bool
read_message(CodedInputStream* in, Descriptor const* descriptor, GenericMessage* message)
{
    uint32 value32;
    uint64 value64;

    while (true) {
        uint32 tag = in->ReadTag();
        if (tag == 0) {
            return true;
        }
        int fieldId = WireFormatLite::GetTagFieldNumber(tag);
        switch (WireFormatLite::GetTagWireType(tag)) {
            case WireFormatLite::WIRETYPE_VARINT:
                if (in->ReadVarint64(&value64)) {
                    message->addInt64(fieldId, value64);
                    break;
                } else {
                    fprintf(stderr, "bad VARINT: 0x%x (%d) at index %d of field %s\n",
                            tag, tag, in->CurrentPosition(), descriptor->name().c_str());
                    return false;
                }
            case WireFormatLite::WIRETYPE_FIXED64:
                if (in->ReadLittleEndian64(&value64)) {
                    message->addInt64(fieldId, value64);
                    break;
                } else {
                    fprintf(stderr, "bad VARINT: 0x%x (%d) at index %d of field %s\n",
                            tag, tag, in->CurrentPosition(), descriptor->name().c_str());
                    return false;
                }
            case WireFormatLite::WIRETYPE_LENGTH_DELIMITED:
                if (!read_length_delimited(in, fieldId, descriptor, message)) {
                    fprintf(stderr, "bad LENGTH_DELIMITED: 0x%x (%d) at index %d of field %s\n",
                            tag, tag, in->CurrentPosition(), descriptor->name().c_str());
                    return false;
                }
                break;
            case WireFormatLite::WIRETYPE_FIXED32:
                if (in->ReadLittleEndian32(&value32)) {
                    message->addInt32(fieldId, value32);
                    break;
                } else {
                    fprintf(stderr, "bad FIXED32: 0x%x (%d) at index %d of field %s\n",
                            tag, tag, in->CurrentPosition(), descriptor->name().c_str());
                    return false;
                }
            default:
                fprintf(stderr, "bad tag: 0x%x (%d) at index %d of field %s\n", tag, tag,
                        in->CurrentPosition(), descriptor->name().c_str());
                return false;
        }
    }
}

// ================================================================================
static void
print_value(Out* out, FieldDescriptor const* field, GenericMessage::Node const& node)
{
    FieldDescriptor::Type type = field->type();

    switch (node.type) {
        case GenericMessage::TYPE_VALUE32:
            switch (type) {
                case FieldDescriptor::TYPE_FIXED32:
                    out->printf("%u", node.value32);
                    break;
                case FieldDescriptor::TYPE_SFIXED32:
                    out->printf("%d", node.value32);
                    break;
                case FieldDescriptor::TYPE_FLOAT:
                    out->printf("%f", *(float*)&node.value32);
                    break;
                default:
                    out->printf("(unexpected type %d: value32 %d (0x%x)",
                                type, node.value32, node.value32);
                    break;
            }
            break;
        case GenericMessage::TYPE_VALUE64:
            switch (type) {
                case FieldDescriptor::TYPE_DOUBLE:
                    out->printf("%f", *(double*)&node.value64);
                    break;
                // Int32s here were added with addInt64 from a WIRETYPE_VARINT,
                // even if the definition is for a 32 bit int.
                case FieldDescriptor::TYPE_SINT32:
                case FieldDescriptor::TYPE_INT32:
                    out->printf("%d", node.value64);
                    break;
                case FieldDescriptor::TYPE_INT64:
                case FieldDescriptor::TYPE_SINT64:
                case FieldDescriptor::TYPE_SFIXED64:
                    out->printf("%lld", node.value64);
                    break;
                case FieldDescriptor::TYPE_UINT32:
                case FieldDescriptor::TYPE_UINT64:
                case FieldDescriptor::TYPE_FIXED64:
                    out->printf("%u", node.value64);
                    break;
                case FieldDescriptor::TYPE_BOOL:
                    if (node.value64) {
                        out->printf("true");
                    } else {
                        out->printf("false");
                    }
                    break;
                case FieldDescriptor::TYPE_ENUM:
                    if (field->enum_type()->FindValueByNumber((int)node.value64) == NULL) {
                        out->printf("%lld", (int) node.value64);
                    } else {
                        out->printf("%s", field->enum_type()->FindValueByNumber((int)node.value64)
                            ->name().c_str());
                    }
                    break;
                default:
                    out->printf("(unexpected type %d: value64 %lld (0x%x))",
                                type, node.value64, node.value64);
                    break;
            }
            break;
        case GenericMessage::TYPE_MESSAGE:
            print_message(out, field->message_type(), node.message);
            break;
        case GenericMessage::TYPE_STRING:
            // TODO: custom format for multi-line strings.
            out->printf("%s", node.str->c_str());
            break;
        case GenericMessage::TYPE_DATA:
            out->printf("<bytes>");
            break;
    }
}

static void
print_message(Out* out, Descriptor const* descriptor, GenericMessage const* message)
{
    out->printf("%s {\n", descriptor->name().c_str());
    out->indent();

    int const N = descriptor->field_count();
    for (int i=0; i<N; i++) {
        FieldDescriptor const* field = descriptor->field(i);

        int fieldId = field->number();
        bool repeated = field->label() == FieldDescriptor::LABEL_REPEATED;
        FieldDescriptor::Type type = field->type();
        GenericMessage::const_iterator_pair it = message->find(fieldId);

        out->printf("%s=", field->name().c_str());
        if (repeated) {
            if (it.first != it.second) {
                out->printf("[\n");
                out->indent();

                for (GenericMessage::const_iterator_pair it = message->find(fieldId);
                        it.first != it.second; it.first++) {
                    print_value(out, field, it.first->second);
                    out->printf("\n");
                }

                out->dedent();
                out->printf("]");
            } else {
                out->printf("[]");
            }
        } else {
            if (it.first != it.second) {
                print_value(out, field, it.first->second);
            } else {
                switch (type) {
                    case FieldDescriptor::TYPE_BOOL:
                        out->printf("false");
                        break;
                    case FieldDescriptor::TYPE_STRING:
                    case FieldDescriptor::TYPE_MESSAGE:
                        out->printf("");
                        break;
                    case FieldDescriptor::TYPE_ENUM:
                        out->printf("%s", field->default_value_enum()->name().c_str());
                        break;
                    default:
                        out->printf("0");
                        break;
                }
            }
        }
        out->printf("\n");
    }
    out->dedent();
    out->printf("}");
}

// ================================================================================
static void
usage(FILE* out)
{
    fprintf(out, "usage: incident_report -i INPUT [-o OUTPUT]\n");
    fprintf(out, "\n");
    fprintf(out, "Pretty-prints an incident report protobuf file.\n");
    fprintf(out, "  -i INPUT    the input file. INPUT may be '-' to use stdin\n");
    fprintf(out, "  -o OUTPUT   the output file. OUTPUT may be '-' or omitted to use stdout\n");
    fprintf(out, "\n");
    fprintf(out, "\n");
    fprintf(out, "usage: incident_report [-o OUTPUT] [-t|b] [-s SERIAL] [SECTION...]\n");
    fprintf(out, "\n");
    fprintf(out, "Take an incident report over adb (which must be in the PATH).\n");
    fprintf(out, "  -b          output the incident report raw protobuf format\n");
    fprintf(out, "  -o OUTPUT   the output file. OUTPUT may be '-' or omitted to use stdout\n");
    fprintf(out, "  -r REASON   human readable description of why the report is taken.\n");
    fprintf(out, "  -s SERIAL   sent to adb to choose which device, instead of $ANDROID_SERIAL\n");
    fprintf(out, "  -t          output the incident report in pretty-printed text format\n");
    fprintf(out, "\n");
    fprintf(out, "  SECTION     which bugreport sections to print, either the int code of the\n");
    fprintf(out, "              section in the Incident proto or the field name.  If ommited,\n");
    fprintf(out, "              the report will contain all fields\n");
    fprintf(out, "\n");
}

int
main(int argc, char** argv)
{
    enum { OUTPUT_TEXT, OUTPUT_PROTO } outputFormat = OUTPUT_TEXT;
    const char* inFilename = NULL;
    const char* outFilename = NULL;
    const char* reason = NULL;
    const char* adbSerial = NULL;
    pid_t childPid = -1;
    vector<string> sections;
    const char* privacy = NULL;

    int opt;
    while ((opt = getopt(argc, argv, "bhi:o:r:s:twp:")) != -1) {
        switch (opt) {
            case 'b':
                outputFormat = OUTPUT_PROTO;
                break;
            case 'i':
                inFilename = optarg;
                break;
            case 'o':
                outFilename = optarg;
                break;
            case 'r':
                reason = optarg;
                break;
            case 's':
                adbSerial = optarg;
                break;
            case 't':
                outputFormat = OUTPUT_TEXT;
                break;
            case 'h':
                usage(stdout);
                return 0;
            case 'p':
                privacy = optarg;
                break;
            default:
                usage(stderr);
                return 1;
        }
    }

    while (optind < argc) {
        sections.push_back(argv[optind++]);
    }

    int inFd;
    if (inFilename != NULL) {
        // translate-only mode - oepn the file or use stdin.
        if (strcmp("-", inFilename) == 0) {
            inFd = STDIN_FILENO;
        } else {
            inFd = open(inFilename, O_RDONLY | O_CLOEXEC);
            if (inFd < 0) {
                fprintf(stderr, "unable to open file for read (%s): %s\n", strerror(errno),
                        inFilename);
                return 1;
            }
        }
    } else {
        // pipe mode - run adb shell incident ...
        int pfd[2];
        if (pipe(pfd) != 0) {
            fprintf(stderr, "pipe failed: %s\n", strerror(errno));
            return 1;
        }

        childPid = fork();
        if (childPid == -1) {
            fprintf(stderr, "fork failed: %s\n", strerror(errno));
            return 1;
        } else if (childPid == 0) {
            // child
            dup2(pfd[1], STDOUT_FILENO);
            close(pfd[0]);
            close(pfd[1]);
            char const** args = (char const**)malloc(sizeof(char*) * (10 + sections.size()));
            int argpos = 0;
            args[argpos++] = "adb";
            if (adbSerial != NULL) {
                args[argpos++] = "-s";
                args[argpos++] = adbSerial;
            }
            args[argpos++] = "shell";
            args[argpos++] = "incident";
            if (privacy != NULL) {
                args[argpos++] = "-p";
                args[argpos++] = privacy;
            }
            if (reason != NULL) {
                args[argpos++] = "-r";
                args[argpos++] = reason;
            }
            for (vector<string>::const_iterator it=sections.begin(); it!=sections.end(); it++) {
                args[argpos++] = it->c_str();
            }
            args[argpos++] = NULL;
            execvp(args[0], (char*const*)args);
            fprintf(stderr, "execvp failed: %s\n", strerror(errno));
            free(args);
            return 0;
        } else {
            // parent
            inFd = pfd[0];
            close(pfd[1]);
        }
    }

    int outFd;
    if (outFilename == NULL || strcmp("-", outFilename) == 0) {
        outFd = STDOUT_FILENO;
    } else {
        outFd = open(outFilename, O_CREAT | O_RDWR, 0666);
        if (outFd < 0) {
            fprintf(stderr, "unable to open file for write: %s\n", outFilename);
            return 1;
        }
    }

    GenericMessage message;

    Descriptor const* descriptor = IncidentProto::descriptor();
    FileInputStream infile(inFd);
    CodedInputStream in(&infile);

    if (!read_message(&in, descriptor, &message)) {
        fprintf(stderr, "unable to read incident\n");
        return 1;
    }

    Out out(outFd);

    print_message(&out, descriptor, &message);
    out.printf("\n");

    if (childPid != -1) {
        int status;
        do {
            waitpid(childPid, &status, 0);
        } while (!WIFEXITED(status));
        if (WEXITSTATUS(status) != 0) {
            return WEXITSTATUS(status);
        }
    }

    return 0;
}
