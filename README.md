# spring-cloud-gateway-grpc

## Getting started

This project is a simple example of how to use gRPC with Spring Cloud Gateway, with some customizations:
* Disable SSL
* Use protobuf file to generate the gRPC service
* Use protobuf-gradle-plugin to generate the descriptor file

### Generate protobuf file

```shell
 gradle build 
```
or 

```shell
 .\gradlew.bat build
```

### Run project

```shell
 gradle bootRun --parallel
```
or 

```shell
 .\gradlew.bat bootRun --parallel
```

### Test    

```shell
curl --location 'localhost:8080/greetings/hello' \
--header 'Content-Type: application/json' \
--data '{
    "name" : "John Snow"
}'
```

## Limitation

### Protobuf

* Version 2 of protoc supported. Version 3 is not supported. [Watch this](https://github.com/FasterXML/jackson-dataformats-binary/blob/2.18/protobuf/README.md)
* Don't use required fields in response message. 

Use this
```protobuf
message HelloResponse {
  optional string greeting = 1;
}
```
and don't do that
```protobuf
message HelloResponse {
  required string greeting = 1;
}
```