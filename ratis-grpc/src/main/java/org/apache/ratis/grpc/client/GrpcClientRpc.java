/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.grpc.client;

import org.apache.ratis.client.impl.ClientProtoUtils;
import org.apache.ratis.client.impl.RaftClientRpcWithProxy;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.grpc.GrpcUtil;
import org.apache.ratis.protocol.ClientId;
import org.apache.ratis.protocol.GroupManagementRequest;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftClientRequest;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.ServerInformationRequest;
import org.apache.ratis.protocol.SetConfigurationRequest;
import org.apache.ratis.thirdparty.io.grpc.stub.StreamObserver;
import org.apache.ratis.proto.RaftProtos.GroupManagementRequestProto;
import org.apache.ratis.proto.RaftProtos.RaftClientReplyProto;
import org.apache.ratis.proto.RaftProtos.RaftClientRequestProto;
import org.apache.ratis.proto.RaftProtos.ServerInformationRequestProto;
import org.apache.ratis.proto.RaftProtos.SetConfigurationRequestProto;
import org.apache.ratis.util.IOUtils;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.PeerProxyMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class GrpcClientRpc extends RaftClientRpcWithProxy<GrpcClientProtocolClient> {
  public static final Logger LOG = LoggerFactory.getLogger(GrpcClientRpc.class);

  private final ClientId clientId;
  private final int maxMessageSize;

  public GrpcClientRpc(ClientId clientId, RaftProperties properties) {
    super(new PeerProxyMap<>(clientId.toString(), p -> new GrpcClientProtocolClient(clientId, p, properties)));
    this.clientId = clientId;
    this.maxMessageSize = GrpcConfigKeys.messageSizeMax(properties, LOG::debug).getSizeInt();
  }

  @Override
  public CompletableFuture<RaftClientReply> sendRequestAsync(
      RaftClientRequest request) {
    final RaftPeerId serverId = request.getServerId();
    try {
      final GrpcClientProtocolClient proxy = getProxies().getProxy(serverId);
      // Reuse the same grpc stream for all async calls.
      return proxy.getAppendStreamObservers().onNext(request);
    } catch (IOException e) {
      return JavaUtils.completeExceptionally(e);
    }
  }

  @Override
  public RaftClientReply sendRequest(RaftClientRequest request)
      throws IOException {
    final RaftPeerId serverId = request.getServerId();
    final GrpcClientProtocolClient proxy = getProxies().getProxy(serverId);
    if (request instanceof GroupManagementRequest) {
      final GroupManagementRequestProto proto = ClientProtoUtils.toGroupManagementRequestProto((GroupManagementRequest)request);
      return ClientProtoUtils.toRaftClientReply(proxy.groupAdd(proto));
    } else if (request instanceof SetConfigurationRequest) {
      final SetConfigurationRequestProto setConf = ClientProtoUtils.toSetConfigurationRequestProto(
          (SetConfigurationRequest) request);
      return ClientProtoUtils.toRaftClientReply(proxy.setConfiguration(setConf));
    } else if (request instanceof ServerInformationRequest){
      final ServerInformationRequestProto proto = ClientProtoUtils.toServerInformationRequestProto(
          (ServerInformationRequest) request);
      return ClientProtoUtils.toServerInformationReply(proxy.serverInformation(proto));
    } else {
      final CompletableFuture<RaftClientReply> f = sendRequest(request, proxy);
      // TODO: timeout support
      try {
        return f.get();
      } catch (InterruptedException e) {
        throw new InterruptedIOException(
            "Interrupted while waiting for response of request " + request);
      } catch (ExecutionException e) {
        throw IOUtils.toIOException(e);
      }
    }
  }

  private CompletableFuture<RaftClientReply> sendRequest(
      RaftClientRequest request, GrpcClientProtocolClient proxy) throws IOException {
    final RaftClientRequestProto requestProto =
        toRaftClientRequestProto(request);
    final CompletableFuture<RaftClientReplyProto> replyFuture =
        new CompletableFuture<>();
    // create a new grpc stream for each non-async call.
    final StreamObserver<RaftClientRequestProto> requestObserver =
        proxy.appendWithTimeout(new StreamObserver<RaftClientReplyProto>() {
          @Override
          public void onNext(RaftClientReplyProto value) {
            replyFuture.complete(value);
          }

          @Override
          public void onError(Throwable t) {
            replyFuture.completeExceptionally(GrpcUtil.unwrapIOException(t));
          }

          @Override
          public void onCompleted() {
            if (!replyFuture.isDone()) {
              replyFuture.completeExceptionally(
                  new IOException(clientId + ": Stream completed but no reply for request " + request));
            }
          }
        });
    requestObserver.onNext(requestProto);
    requestObserver.onCompleted();

    return replyFuture.thenApply(ClientProtoUtils::toRaftClientReply);
  }

  private RaftClientRequestProto toRaftClientRequestProto(RaftClientRequest request) throws IOException {
    final RaftClientRequestProto proto = ClientProtoUtils.toRaftClientRequestProto(request);
    if (proto.getSerializedSize() > maxMessageSize) {
      throw new IOException(clientId + ": Message size:" + proto.getSerializedSize()
          + " exceeds maximum:" + maxMessageSize);
    }
    return proto;
  }
}
