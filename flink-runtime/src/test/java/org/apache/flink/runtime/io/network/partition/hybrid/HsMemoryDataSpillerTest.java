/*
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

package org.apache.flink.runtime.io.network.partition.hybrid;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.core.memory.MemorySegmentFactory;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.FreeingBufferRecycler;
import org.apache.flink.runtime.io.network.buffer.NetworkBuffer;
import org.apache.flink.runtime.io.network.partition.BufferReaderWriterUtil;
import org.apache.flink.runtime.io.network.partition.hybrid.HsFileDataIndex.SpilledBuffer;
import org.apache.flink.util.TestLoggerExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests for {@link HsMemoryDataSpiller}. */
@ExtendWith(TestLoggerExtension.class)
class HsMemoryDataSpillerTest {

    private static final int BUFFER_SIZE = Integer.BYTES;

    private static final long BUFFER_WITH_HEADER_SIZE =
            BUFFER_SIZE + BufferReaderWriterUtil.HEADER_LENGTH;

    private FileChannel dataFileChannel;

    private HsMemoryDataSpiller memoryDataSpiller;

    @BeforeEach
    void before(@TempDir Path tempDir) throws Exception {
        dataFileChannel =
                FileChannel.open(
                        Files.createFile(tempDir.resolve(".data")),
                        StandardOpenOption.WRITE,
                        StandardOpenOption.READ);
        this.memoryDataSpiller = new HsMemoryDataSpiller(dataFileChannel);
    }

    @Test
    void testSpillSuccessfully() throws Exception {
        List<BufferWithIdentity> bufferWithIdentityList = new ArrayList<>();
        bufferWithIdentityList.addAll(
                createBufferWithIdentityList(
                        0, Arrays.asList(Tuple2.of(0, 0), Tuple2.of(1, 1), Tuple2.of(2, 2))));
        bufferWithIdentityList.addAll(
                createBufferWithIdentityList(
                        0, Arrays.asList(Tuple2.of(4, 0), Tuple2.of(5, 1), Tuple2.of(6, 2))));
        CompletableFuture<List<SpilledBuffer>> future =
                memoryDataSpiller.spillAsync(bufferWithIdentityList);
        List<SpilledBuffer> expectedSpilledBuffers =
                getExpectedSpilledBuffers(bufferWithIdentityList);
        assertThat(future)
                .succeedsWithin(60, TimeUnit.SECONDS)
                .satisfies(
                        spilledBuffers ->
                                assertThat(spilledBuffers)
                                        .zipSatisfy(
                                                expectedSpilledBuffers,
                                                (spilledBuffer, expectedSpilledBuffer) -> {
                                                    assertThat(spilledBuffer.bufferIndex)
                                                            .isEqualTo(
                                                                    expectedSpilledBuffer
                                                                            .bufferIndex);
                                                    assertThat(spilledBuffer.subpartitionId)
                                                            .isEqualTo(
                                                                    expectedSpilledBuffer
                                                                            .subpartitionId);
                                                    assertThat(spilledBuffer.fileOffset)
                                                            .isEqualTo(
                                                                    expectedSpilledBuffer
                                                                            .fileOffset);
                                                }));
        checkData(
                Arrays.asList(
                        Tuple2.of(0, 0),
                        Tuple2.of(1, 1),
                        Tuple2.of(2, 2),
                        Tuple2.of(4, 0),
                        Tuple2.of(5, 1),
                        Tuple2.of(6, 2)));
    }

    /**
     * create buffer with identity list.
     *
     * @param subpartitionId the buffers belong to.
     * @param dataAndIndexes is the list contains pair of (bufferData, bufferIndex).
     */
    private static List<BufferWithIdentity> createBufferWithIdentityList(
            int subpartitionId, List<Tuple2<Integer, Integer>> dataAndIndexes) {
        List<BufferWithIdentity> bufferWithIdentityList = new ArrayList<>();
        for (Tuple2<Integer, Integer> dataAndIndex : dataAndIndexes) {
            Buffer.DataType dataType =
                    dataAndIndex.f1 % 2 == 0
                            ? Buffer.DataType.EVENT_BUFFER
                            : Buffer.DataType.DATA_BUFFER;

            MemorySegment segment = MemorySegmentFactory.allocateUnpooledSegment(BUFFER_SIZE);
            segment.putInt(0, dataAndIndex.f0);
            Buffer buffer =
                    new NetworkBuffer(
                            segment, FreeingBufferRecycler.INSTANCE, dataType, BUFFER_SIZE);
            bufferWithIdentityList.add(
                    new BufferWithIdentity(buffer, dataAndIndex.f1, subpartitionId));
        }
        return Collections.unmodifiableList(bufferWithIdentityList);
    }

    /** get SpilledBuffers from BufferWithIdentities. */
    private static List<SpilledBuffer> getExpectedSpilledBuffers(
            List<BufferWithIdentity> bufferWithIdentityList) {
        long totalBytes = 0;
        List<SpilledBuffer> spilledBuffers = new ArrayList<>();
        for (BufferWithIdentity bufferWithIdentity : bufferWithIdentityList) {
            spilledBuffers.add(
                    new SpilledBuffer(
                            bufferWithIdentity.getChannelIndex(),
                            bufferWithIdentity.getBufferIndex(),
                            totalBytes));
            totalBytes += BUFFER_WITH_HEADER_SIZE;
        }
        return Collections.unmodifiableList(spilledBuffers);
    }

    private void checkData(List<Tuple2<Integer, Integer>> dataAndIndexes) throws Exception {
        // reset channel position for read.
        dataFileChannel.position(0);
        ByteBuffer headerBuf = BufferReaderWriterUtil.allocatedHeaderBuffer();
        MemorySegment segment = MemorySegmentFactory.allocateUnpooledSegment(BUFFER_SIZE);
        for (Tuple2<Integer, Integer> dataAndIndex : dataAndIndexes) {
            Buffer buffer =
                    BufferReaderWriterUtil.readFromByteChannel(
                            dataFileChannel, headerBuf, segment, (ignore) -> {});

            assertThat(buffer.readableBytes()).isEqualTo(BUFFER_SIZE);
            assertThat(buffer.getNioBufferReadable().order(ByteOrder.nativeOrder()).getInt())
                    .isEqualTo(dataAndIndex.f0);
            assertThat(buffer.getDataType().isEvent()).isEqualTo(dataAndIndex.f1 % 2 == 0);
        }
    }
}
