package com.nduyhai.gateway;

import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import org.springframework.cloud.gateway.config.GrpcSslConfigurer;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GRPCLocalConfiguration {
    @Bean
    public GrpcSslConfigurer grpcSslConfigurer(HttpClientProperties properties) {
        return new GrpcSslConfigurer(properties.getSsl()) {
            @Override
            public ManagedChannel configureSsl(NettyChannelBuilder builder) {
                return builder.usePlaintext().defaultLoadBalancingPolicy("round_robin").build();
            }
        };
    }
}