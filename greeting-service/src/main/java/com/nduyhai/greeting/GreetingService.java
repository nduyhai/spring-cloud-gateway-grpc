package com.nduyhai.greeting;

import com.nduyhai.grpc.Greeting;
import com.nduyhai.grpc.GreetingServiceGrpc;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.lognet.springboot.grpc.GRpcService;
import org.springframework.stereotype.Service;

@Service
@GRpcService
@Slf4j
public class GreetingService extends GreetingServiceGrpc.GreetingServiceImplBase {

  @Override
  public void hello(Greeting.HelloRequest request,
      StreamObserver<Greeting.HelloResponse> responseObserver) {
    try {
      Greeting.HelloResponse response = Greeting.HelloResponse.newBuilder()
          .setGreeting("Hello, " + request.getName()).build();

      responseObserver.onNext(response);
      responseObserver.onCompleted();
      log.info("On completed");
    } catch (Exception ex) {
      responseObserver.onError(ex);
    }
  }
}
