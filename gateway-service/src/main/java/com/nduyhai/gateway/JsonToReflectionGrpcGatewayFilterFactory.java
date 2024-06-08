package com.nduyhai.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.protobuf.ProtobufFactory;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchema;
import com.fasterxml.jackson.dataformat.protobuf.schema.ProtobufSchemaLoader;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.*;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.netty.buffer.PooledByteBufAllocator;
import lombok.Getter;
import lombok.Setter;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static org.springframework.cloud.gateway.support.GatewayToStringStyler.filterToStringCreator;

@Component
public class JsonToReflectionGrpcGatewayFilterFactory extends AbstractGatewayFilterFactory<JsonToReflectionGrpcGatewayFilterFactory.Config> {

    public JsonToReflectionGrpcGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return Arrays.asList("service", "method");
    }

    @Override
    public GatewayFilter apply(Config config) {
        GatewayFilter filter = new GatewayFilter() {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
                GRPCResponseDecorator modifiedResponse = new GRPCResponseDecorator(exchange, config);
                ServerWebExchangeUtils.setAlreadyRouted(exchange);
                return modifiedResponse.writeWith(exchange.getRequest().getBody())
                        .then(chain.filter(exchange.mutate().response(modifiedResponse).build()));
            }

            @Override
            public String toString() {
                return filterToStringCreator(JsonToReflectionGrpcGatewayFilterFactory.this).toString();
            }
        };
        int order = NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1;
        return new OrderedGatewayFilter(filter, order);
    }


    @Setter
    @Getter
    public static class Config {
        private String service;
        private String method;

    }

    static class GRPCResponseDecorator extends ServerHttpResponseDecorator {
        private final ServerWebExchange exchange;
        private final Descriptors.Descriptor descriptor;

        private final ObjectWriter objectWriter;

        private final ObjectReader objectReader;

        private final ClientCall<DynamicMessage, DynamicMessage> clientCall;

        private final ObjectNode objectNode;

        public GRPCResponseDecorator(ServerWebExchange exchange, Config config) {
            super(exchange.getResponse());
            this.exchange = exchange;
            try {
                ManagedChannel channel = createChannel();

                ListenableFuture<ImmutableList<String>> futureServices = ServerReflectionClient.create(channel).listServices();
                String serviceName = futureServices.get().stream().filter(e -> e.endsWith(config.getService())).findFirst().orElse(config.service);

                DescriptorProtos.FileDescriptorSet fileDescriptor = ServerReflectionClient.create(channel).lookupService(serviceName).get();
                this.descriptor = fileDescriptor.getDescriptorForType();

                Descriptors.MethodDescriptor methodDescriptor = getMethodDescriptor(config,
                        fileDescriptor);
                Descriptors.ServiceDescriptor serviceDescriptor = methodDescriptor.getService();
                Descriptors.Descriptor outputType = methodDescriptor.getOutputType();
                this.clientCall = createClientCallForType(config, serviceDescriptor, outputType);


                DescriptorProtos.FileDescriptorProto descriptorFile = fileDescriptor.getFile(0);

                //TODO: convert descriptorFile to proto
                ProtobufSchema schema = ProtobufSchemaLoader.std.parse(descriptorFile.toString());
                ProtobufSchema responseType = schema.withRootType(outputType.getName());

                ObjectMapper objectMapper = new ObjectMapper(new ProtobufFactory());
                objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
                objectWriter = objectMapper.writer(schema);
                objectReader = objectMapper.readerFor(JsonNode.class).with(responseType);
                objectNode = objectMapper.createObjectNode();
            } catch (InterruptedException | ExecutionException | Descriptors.DescriptorValidationException |
                     IOException e) {
                throw new RuntimeException(e);
            }
        }


        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            exchange.getResponse().getHeaders().set("Content-Type", "application/json");
            return getDelegate().writeWith(deserializeJSONRequest().map(callGRPCServer()).map(serialiseGRPCResponse())
                    .map(wrapGRPCResponse()).cast(DataBuffer.class).last());
        }

        private Flux<JsonNode> deserializeJSONRequest() {
            return exchange.getRequest().getBody().mapNotNull(dataBufferBody -> {
                if (dataBufferBody.capacity() == 0) {
                    return objectNode;
                }
                ResolvableType targetType = ResolvableType.forType(JsonNode.class);
                return new Jackson2JsonDecoder().decode(dataBufferBody, targetType, null, null);
            }).cast(JsonNode.class);
        }

        private Function<JsonNode, DynamicMessage> callGRPCServer() {
            return jsonRequest -> {
                try {
                    byte[] request = objectWriter.writeValueAsBytes(jsonRequest);
                    return ClientCalls.blockingUnaryCall(clientCall, DynamicMessage.parseFrom(descriptor, request));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
        }

        private Function<Object, DataBuffer> wrapGRPCResponse() {
            return jsonResponse -> {
                try {
                    return new NettyDataBufferFactory(new PooledByteBufAllocator())
                            .wrap(Objects.requireNonNull(new ObjectMapper().writeValueAsBytes(jsonResponse)));
                } catch (JsonProcessingException e) {
                    return new NettyDataBufferFactory(new PooledByteBufAllocator()).allocateBuffer();
                }
            };
        }

        private Function<DynamicMessage, Object> serialiseGRPCResponse() {
            return gRPCResponse -> {
                try {
                    return objectReader.readValue(gRPCResponse.toByteArray());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
        }

        private ClientCall<DynamicMessage, DynamicMessage> createClientCallForType(Config config,
                                                                                   Descriptors.ServiceDescriptor serviceDescriptor, Descriptors.Descriptor outputType) {
            MethodDescriptor.Marshaller<DynamicMessage> marshaller = ProtoUtils
                    .marshaller(DynamicMessage.newBuilder(outputType).build());
            MethodDescriptor<DynamicMessage, DynamicMessage> methodDescriptor = MethodDescriptor
                    .<DynamicMessage, DynamicMessage>newBuilder().setType(MethodDescriptor.MethodType.UNKNOWN)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceDescriptor.getFullName(),
                            config.getMethod()))
                    .setRequestMarshaller(marshaller).setResponseMarshaller(marshaller).build();
            Channel channel = createChannel();
            return channel.newCall(methodDescriptor, CallOptions.DEFAULT);
        }


        private ManagedChannel createChannel() {
            URI requestURI = ((Route) exchange.getAttributes().get(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)).getUri();
            return createChannelChannel(requestURI.getHost(), requestURI.getPort());
        }

        private ManagedChannel createChannelChannel(String host, int port) {
            return ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build();
        }


        private Descriptors.MethodDescriptor getMethodDescriptor(Config config, DescriptorProtos.FileDescriptorSet fileDescriptorSet)
                throws IOException, Descriptors.DescriptorValidationException {
            DescriptorProtos.FileDescriptorProto fileProto = fileDescriptorSet.getFile(0);
            Descriptors.FileDescriptor fileDescriptor = Descriptors.FileDescriptor.buildFrom(fileProto,
                    new Descriptors.FileDescriptor[0]);

            Descriptors.ServiceDescriptor serviceDescriptor = fileDescriptor.findServiceByName(config.getService());
            if (serviceDescriptor == null) {
                throw new NoSuchElementException("No Service found");
            }

            List<Descriptors.MethodDescriptor> methods = serviceDescriptor.getMethods();

            return methods.stream().filter(method -> method.getName().equals(config.getMethod())).findFirst()
                    .orElseThrow(() -> new NoSuchElementException("No Method found"));
        }

    }
}
