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
package org.apache.ratis.server.impl;

import static org.apache.ratis.server.impl.RaftServerConstants.DEFAULT_CALLID;
import static org.apache.ratis.server.impl.RaftServerConstants.DEFAULT_SEQNUM;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.ratis.client.impl.ClientProtoUtils;
import org.apache.ratis.protocol.*;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.proto.RaftProtos.*;
import org.apache.ratis.proto.RaftProtos.AppendEntriesReplyProto.*;
import org.apache.ratis.util.Preconditions;
import org.apache.ratis.util.ProtoUtils;

/** Server proto utilities for internal use. */
public class ServerProtoUtils {
  public static TermIndex toTermIndex(TermIndexProto p) {
    return p == null? null: TermIndex.newTermIndex(p.getTerm(), p.getIndex());
  }

  public static TermIndexProto toTermIndexProto(TermIndex ti) {
    return ti == null? null: TermIndexProto.newBuilder()
        .setTerm(ti.getTerm())
        .setIndex(ti.getIndex())
        .build();
  }

  public static TermIndex toTermIndex(LogEntryProto entry) {
    return entry == null ? null :
        TermIndex.newTermIndex(entry.getTerm(), entry.getIndex());
  }

  public static String toTermIndexString(LogEntryProto entry) {
    return TermIndex.toString(entry.getTerm(), entry.getIndex());
  }

  public static String toLogEntryString(LogEntryProto entry) {
    if (entry == null) {
      return null;
    }
    final ByteString clientId = entry.getClientId();
    return toTermIndexString(entry) + entry.getLogEntryBodyCase()
        + ", " + (clientId.isEmpty()? "<empty clientId>": ClientId.valueOf(clientId))
        + ", cid=" + entry.getCallId();
  }

  public static String toString(LogEntryProto... entries) {
    return entries == null? "null"
        : entries.length == 0 ? "[]"
        : entries.length == 1? toLogEntryString(entries[0])
        : "" + Arrays.stream(entries).map(ServerProtoUtils::toLogEntryString)
            .collect(Collectors.toList());
  }

  public static String toString(AppendEntriesReplyProto reply) {
    return toString(reply.getServerReply()) + "," + reply.getResult()
        + ",nextIndex:" + reply.getNextIndex() + ",term:" + reply.getTerm()
        + ",followerCommit:" + reply.getFollowerCommit();
  }

  private static String toString(RaftRpcReplyProto reply) {
    return reply.getRequestorId().toStringUtf8() + "->"
        + reply.getReplyId().toStringUtf8() + "," + reply.getSuccess();
  }

  public static RaftConfigurationProto toRaftConfigurationProto(
      RaftConfiguration conf) {
    return RaftConfigurationProto.newBuilder()
        .addAllPeers(ProtoUtils.toRaftPeerProtos(conf.getPeersInConf()))
        .addAllOldPeers(ProtoUtils.toRaftPeerProtos(conf.getPeersInOldConf()))
        .build();
  }

  public static RaftConfiguration toRaftConfiguration(LogEntryProto entry) {
    Preconditions.assertTrue(ProtoUtils.isConfigurationLogEntry(entry));
    final RaftConfigurationProto proto = entry.getConfigurationEntry();
    final RaftConfiguration.Builder b = RaftConfiguration.newBuilder()
        .setConf(ProtoUtils.toRaftPeerArray(proto.getPeersList()))
        .setLogEntryIndex(entry.getIndex());
    if (proto.getOldPeersCount() > 0) {
      b.setOldConf(ProtoUtils.toRaftPeerArray(proto.getOldPeersList()));
    }
    return b.build();
  }

  public static LogEntryProto toLogEntryProto(
      RaftConfiguration conf, long term, long index) {
    return LogEntryProto.newBuilder()
        .setTerm(term)
        .setIndex(index)
        .setConfigurationEntry(toRaftConfigurationProto(conf))
        .build();
  }

  static RaftRpcReplyProto.Builder toRaftRpcReplyProtoBuilder(
      RaftPeerId requestorId, RaftPeerId replyId, RaftGroupId groupId, boolean success) {
    return ClientProtoUtils.toRaftRpcReplyProtoBuilder(
        requestorId.toByteString(), replyId.toByteString(), groupId, DEFAULT_CALLID, success);
  }

  public static RequestVoteReplyProto toRequestVoteReplyProto(
      RaftPeerId requestorId, RaftPeerId replyId, RaftGroupId groupId,
      boolean success, long term, boolean shouldShutdown) {
    return RequestVoteReplyProto.newBuilder()
        .setServerReply(toRaftRpcReplyProtoBuilder(requestorId, replyId, groupId, success))
        .setTerm(term)
        .setShouldShutdown(shouldShutdown)
        .build();
  }

  static RaftRpcRequestProto.Builder toRaftRpcRequestProtoBuilder(
      RaftPeerId requestorId, RaftPeerId replyId, RaftGroupId groupId) {
    return ClientProtoUtils.toRaftRpcRequestProtoBuilder(
        requestorId.toByteString(), replyId.toByteString(), groupId, DEFAULT_CALLID, DEFAULT_SEQNUM);
  }

  public static RequestVoteRequestProto toRequestVoteRequestProto(
      RaftPeerId requestorId, RaftPeerId replyId, RaftGroupId groupId, long term, TermIndex lastEntry) {
    final RequestVoteRequestProto.Builder b = RequestVoteRequestProto.newBuilder()
        .setServerRequest(toRaftRpcRequestProtoBuilder(requestorId, replyId, groupId))
        .setCandidateTerm(term);
    if (lastEntry != null) {
      b.setCandidateLastEntry(toTermIndexProto(lastEntry));
    }
    return b.build();
  }

  public static InstallSnapshotReplyProto toInstallSnapshotReplyProto(
      RaftPeerId requestorId, RaftPeerId replyId, RaftGroupId groupId,
      long term, int requestIndex, InstallSnapshotResult result) {
    final RaftRpcReplyProto.Builder rb = toRaftRpcReplyProtoBuilder(requestorId,
        replyId, groupId, result == InstallSnapshotResult.SUCCESS);
    final InstallSnapshotReplyProto.Builder builder = InstallSnapshotReplyProto
        .newBuilder().setServerReply(rb).setTerm(term).setResult(result)
        .setRequestIndex(requestIndex);
    return builder.build();
  }

  public static InstallSnapshotRequestProto toInstallSnapshotRequestProto(
      RaftPeerId requestorId, RaftPeerId replyId, RaftGroupId groupId, String requestId, int requestIndex,
      long term, TermIndex lastTermIndex, List<FileChunkProto> chunks,
      long totalSize, boolean done) {
    return InstallSnapshotRequestProto.newBuilder()
        .setServerRequest(toRaftRpcRequestProtoBuilder(requestorId, replyId, groupId))
        .setRequestId(requestId)
        .setRequestIndex(requestIndex)
        // .setRaftConfiguration()  TODO: save and pass RaftConfiguration
        .setLeaderTerm(term)
        .setTermIndex(toTermIndexProto(lastTermIndex))
        .addAllFileChunks(chunks)
        .setTotalSize(totalSize)
        .setDone(done).build();
  }

  public static AppendEntriesReplyProto toAppendEntriesReplyProto(
      RaftPeerId requestorId, RaftPeerId replyId, RaftGroupId groupId, long term,
      long followerCommit, long nextIndex, AppendResult result, long callId) {
    RaftRpcReplyProto.Builder rpcReply = toRaftRpcReplyProtoBuilder(
        requestorId, replyId, groupId, result == AppendResult.SUCCESS)
        .setCallId(callId);
    return AppendEntriesReplyProto.newBuilder()
        .setServerReply(rpcReply)
        .setTerm(term)
        .setNextIndex(nextIndex)
        .setFollowerCommit(followerCommit)
        .setResult(result).build();
  }

  public static AppendEntriesRequestProto toAppendEntriesRequestProto(
      RaftPeerId requestorId, RaftPeerId replyId, RaftGroupId groupId, long leaderTerm,
      List<LogEntryProto> entries, long leaderCommit, boolean initializing,
      TermIndex previous, Collection<CommitInfoProto> commitInfos, long callId) {
    RaftRpcRequestProto.Builder rpcRequest = toRaftRpcRequestProtoBuilder(requestorId, replyId, groupId)
        .setCallId(callId);
    final AppendEntriesRequestProto.Builder b = AppendEntriesRequestProto
        .newBuilder()
        .setServerRequest(rpcRequest)
        .setLeaderTerm(leaderTerm)
        .setLeaderCommit(leaderCommit)
        .setInitializing(initializing);
    if (entries != null && !entries.isEmpty()) {
      b.addAllEntries(entries);
    }

    if (previous != null) {
      b.setPreviousLog(toTermIndexProto(previous));
    }
    ProtoUtils.addCommitInfos(commitInfos, i -> b.addCommitInfos(i));
    return b.build();
  }

  static ServerRpcProto toServerRpcProto(RaftPeer peer, long delay) {
    if (peer == null) {
      // if no peer information return empty
      return ServerRpcProto.getDefaultInstance();
    }
    return ServerRpcProto.newBuilder()
        .setId(ProtoUtils.toRaftPeerProto(peer))
        .setLastRpcElapsedTimeMs(delay)
        .build();
  }
}
