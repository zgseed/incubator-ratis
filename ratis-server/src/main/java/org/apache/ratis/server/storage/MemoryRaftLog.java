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
package org.apache.ratis.server.storage;

import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.impl.RaftConfiguration;
import org.apache.ratis.server.impl.RaftServerConstants;
import org.apache.ratis.server.impl.ServerProtoUtils;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.util.AutoCloseableLock;
import org.apache.ratis.util.Preconditions;
import org.apache.ratis.util.ProtoUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A simple RaftLog implementation in memory. Used only for testing.
 */
public class MemoryRaftLog extends RaftLog {
  private final List<LogEntryProto> entries = new ArrayList<>();

  public MemoryRaftLog(RaftPeerId selfId, int maxBufferSize) {
    super(selfId, maxBufferSize);
  }

  @Override
  public LogEntryProto get(long index) {
    checkLogState();
    try(AutoCloseableLock readLock = readLock()) {
      final int i = (int) index;
      return i >= 0 && i < entries.size() ? entries.get(i) : null;
    }
  }

  @Override
  public EntryWithData getEntryWithData(long index) {
    return new EntryWithData(get(index), null);
  }

  @Override
  public TermIndex getTermIndex(long index) {
    checkLogState();
    try(AutoCloseableLock readLock = readLock()) {
      final int i = (int) index;
      return i >= 0 && i < entries.size() ?
          ServerProtoUtils.toTermIndex(entries.get(i)) : null;
    }
  }

  @Override
  public TermIndex[] getEntries(long startIndex, long endIndex) {
    checkLogState();
    try(AutoCloseableLock readLock = readLock()) {
      final int from = (int) startIndex;
      if (startIndex >= entries.size()) {
        return null;
      }
      final int to = (int) Math.min(entries.size(), endIndex);
      TermIndex[] ti = new TermIndex[to - from];
      for (int i = 0; i < ti.length; i++) {
        ti[i] = TermIndex.newTermIndex(entries.get(i).getTerm(),
            entries.get(i).getIndex());
      }
      return ti;
    }
  }

  @Override
  CompletableFuture<Long> truncate(long index) {
    checkLogState();
    try(AutoCloseableLock writeLock = writeLock()) {
      Preconditions.assertTrue(index >= 0);
      final int truncateIndex = (int) index;
      for (int i = entries.size() - 1; i >= truncateIndex; i--) {
        entries.remove(i);
      }
    }
    return CompletableFuture.completedFuture(index);
  }

  @Override
  public TermIndex getLastEntryTermIndex() {
    checkLogState();
    try(AutoCloseableLock readLock = readLock()) {
      final int size = entries.size();
      return size == 0 ? null : ServerProtoUtils.toTermIndex(entries.get(size - 1));
    }
  }

  @Override
  CompletableFuture<Long> appendEntry(LogEntryProto entry) {
    checkLogState();
    try(AutoCloseableLock writeLock = writeLock()) {
      validateLogEntry(entry);
      entries.add(entry);
    }
    return CompletableFuture.completedFuture(entry.getIndex());
  }

  @Override
  public long append(long term, RaftConfiguration newConf) {
    checkLogState();
    try(AutoCloseableLock writeLock = writeLock()) {
      final long nextIndex = getNextIndex();
      final LogEntryProto e = ServerProtoUtils.toLogEntryProto(newConf, term,
          nextIndex);
      entries.add(e);
      return nextIndex;
    }
  }

  @Override
  public long getStartIndex() {
    return entries.isEmpty() ? RaftServerConstants.INVALID_LOG_INDEX :
        entries.get(0).getIndex();
  }

  @Override
  public List<CompletableFuture<Long>> append(LogEntryProto... entries) {
    checkLogState();
    try(AutoCloseableLock writeLock = writeLock()) {
      if (entries == null || entries.length == 0) {
        return Collections.emptyList();
      }
      // Before truncating the entries, we first need to check if some
      // entries are duplicated. If the leader sends entry 6, entry 7, then
      // entry 6 again, without this check the follower may truncate entry 7
      // when receiving entry 6 again. Then before the leader detects this
      // truncation in the next appendEntries RPC, leader may think entry 7 has
      // been committed but in the system the entry has not been committed to
      // the quorum of peers' disks.
      // TODO add a unit test for this
      boolean toTruncate = false;
      int truncateIndex = (int) entries[0].getIndex();
      int index = 0;
      for (; truncateIndex < getNextIndex() && index < entries.length;
           index++, truncateIndex++) {
        if (this.entries.get(truncateIndex).getTerm() !=
            entries[index].getTerm()) {
          toTruncate = true;
          break;
        }
      }
      final List<CompletableFuture<Long>> futures;
      if (toTruncate) {
        futures = new ArrayList<>(entries.length - index + 1);
        futures.add(truncate(truncateIndex));
      } else {
        futures = new ArrayList<>(entries.length - index);
      }
      for (int i = index; i < entries.length; i++) {
        this.entries.add(entries[i]);
        futures.add(CompletableFuture.completedFuture(entries[i].getIndex()));
      }
      return futures;
    }
  }

  @Override
  public String toString() {
    return "last=" + getLastEntryTermIndex() + ", committed="
        + ServerProtoUtils.toString(get(getLastCommittedIndex()));
  }

  public String getEntryString() {
    return "entries=" + entries;
  }

  @Override
  public long getLatestFlushedIndex() {
    return getNextIndex() - 1;
  }

  @Override
  public void writeMetadata(long term, RaftPeerId votedFor) {
    // do nothing
  }

  @Override
  public Metadata loadMetadata() {
    return new Metadata(null, 0);
  }

  @Override
  public void syncWithSnapshot(long lastSnapshotIndex) {
    // do nothing
  }

  @Override
  public boolean isConfigEntry(TermIndex ti) {
    return ProtoUtils.isConfigurationLogEntry(get(ti.getIndex()));
  }
}
