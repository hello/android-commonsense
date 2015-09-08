# android-common-sense

Client classes for Sense.

Building
========

You will need an access / private key for Hello's S3 Maven repository. After you've acquired the pair, you will need to add the following to your `$HOME/.gradle/gradle.properties`:

```properties
helloAwsAccessKeyID=<Your access key>
helloAwsSecretKey=<Your secret key>
```

# Testing

All tests are run within Robolectric on both your local computer, and on circleCI. If you have a branch that should not be run on continuous integration before merging, prefix your branch with `no-test-`.

# Protobuf Updates

To update the Sense protobuf definitions used by the project, grab the latest [morpheus_ble.proto](https://github.com/hello/proto/blob/master/morpheus_ble.proto) from the internal proto repository. You will need to alter the output package of the protobuf like so:

```protobuf
option java_package = "is.hello.commonsense.bluetooth.model.protobuf";
```

and specify that you want to target the lite runtime by adding this option below any existing ones:

```protobuf
option optimize_for = LITE_RUNTIME;
```

You can then use a typical `protoc` invocation like:

```bash
protoc --java_out=./ --proto_path=./ morpheus_ble.proto
```

to produce the appropriate Java classes. The project currently targets protobuf version 2.6.1.
