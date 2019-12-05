import sdk_pb2
import sys

if __name__ == '__main__':
  argv = sys.argv[1:]
  if not len(argv) == 4 or sorted([argv[0], argv[2]]) != ['-o', '-v']:
    print('usage: gen_sdkinfo -v <version> -o <output-file>')
    sys.exit(1)

  for i in range(len(argv)):
    if sys.argv[i] == '-o':
      filename = sys.argv[i+1]
    if sys.argv[i] == '-v':
      version = int(sys.argv[i+1])

  proto = sdk_pb2.SdkVersion()
  proto.version = version
  with open(filename, 'wb') as f:
    f.write(proto.SerializeToString())
