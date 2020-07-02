/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.repartition;

import com.facebook.presto.common.block.ArrayAllocator;
import com.facebook.presto.common.block.Block;
import com.google.common.annotations.VisibleForTesting;
import io.airlift.slice.SliceOutput;
import org.openjdk.jol.info.ClassLayout;

import static com.facebook.presto.array.Arrays.ExpansionFactor.LARGE;
import static com.facebook.presto.array.Arrays.ExpansionOption.PRESERVE;
import static com.facebook.presto.array.Arrays.ensureCapacity;
import static com.facebook.presto.operator.UncheckedByteArrays.setLongUnchecked;
import static com.google.common.base.MoreObjects.toStringHelper;
import static io.airlift.slice.SizeOf.SIZE_OF_INT;
import static java.util.Objects.requireNonNull;
import static sun.misc.Unsafe.ARRAY_LONG_INDEX_SCALE;

public class Int128ArrayBlockEncodingBuffer
        extends AbstractBlockEncodingBuffer
{
    @VisibleForTesting
    static final int POSITION_SIZE = Long.BYTES * 2 + Byte.BYTES;

    private static final String NAME = "INT128_ARRAY";
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(Int128ArrayBlockEncodingBuffer.class).instanceSize();

    private byte[] valuesBuffer;
    private int valuesBufferIndex;
    private int estimatedValueBufferMaxCapacity;

    public Int128ArrayBlockEncodingBuffer(ArrayAllocator bufferAllocator, boolean isNested)
    {
        super(bufferAllocator, isNested);
    }

    @Override
    public void accumulateSerializedRowSizes(int[] serializedRowSizes)
    {
        throw new UnsupportedOperationException("accumulateSerializedRowSizes is not supported for fixed width types");
    }

    @Override
    public void appendDataInBatch()
    {
        if (batchSize == 0) {
            return;
        }

        appendValuesToBuffer();
        appendNulls();

        bufferedPositionCount += batchSize;
    }

    @Override
    public void serializeTo(SliceOutput output)
    {
        writeLengthPrefixedString(output, NAME);

        output.writeInt(bufferedPositionCount);

        serializeNullsTo(output);

        if (valuesBufferIndex > 0) {
            output.appendBytes(valuesBuffer, 0, valuesBufferIndex);
        }
    }

    @Override
    public void resetBuffers()
    {
        bufferedPositionCount = 0;
        valuesBufferIndex = 0;
        flushed = true;
        resetNullsBuffer();
    }

    @Override
    public void noMoreBatches()
    {
        super.noMoreBatches();

        if (flushed && valuesBuffer != null) {
            bufferAllocator.returnArray(valuesBuffer);
            valuesBuffer = null;
        }
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE;
    }

    @Override
    public long getSerializedSizeInBytes()
    {
        return NAME.length() + SIZE_OF_INT +    // NAME
                SIZE_OF_INT +                   // positionCount
                valuesBufferIndex +             // values buffer
                getNullsBufferSerializedSizeInBytes();    // nulls buffer
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("super", super.toString())
                .add("estimatedValueBufferMaxCapacity", estimatedValueBufferMaxCapacity)
                .add("valuesBufferCapacity", valuesBuffer == null ? 0 : valuesBuffer.length)
                .add("valuesBufferIndex", valuesBufferIndex)
                .toString();
    }

    @VisibleForTesting
    int getEstimatedValueBufferMaxCapacity()
    {
        return estimatedValueBufferMaxCapacity;
    }

    @Override
    protected void setupDecodedBlockAndMapPositions(DecodedBlockNode decodedBlockNode, int partitionBufferCapacity, double decodedBlockPageSizeFraction)
    {
        requireNonNull(decodedBlockNode, "decodedBlockNode is null");
        decodedBlock = (Block) mapPositionsToNestedBlock(decodedBlockNode).getDecodedBlock();

        double targetBufferSize = partitionBufferCapacity * decodedBlockPageSizeFraction;
        if (decodedBlock.mayHaveNull()) {
            setEstimatedNullsBufferMaxCapacity((int) (targetBufferSize * Byte.BYTES / POSITION_SIZE));
            estimatedValueBufferMaxCapacity = (int) (targetBufferSize * Long.BYTES * 2 / POSITION_SIZE);
        }
        else {
            estimatedValueBufferMaxCapacity = (int) targetBufferSize;
        }
    }

    @Override
    protected void accumulateSerializedRowSizes(int[] positionOffsets, int positionCount, int[] serializedRowSizes)
    {
        for (int i = 0; i < positionCount; i++) {
            serializedRowSizes[i] += (positionOffsets[i + 1] - positionOffsets[i]) * POSITION_SIZE;
        }
    }

    private void appendValuesToBuffer()
    {
        valuesBuffer = ensureCapacity(valuesBuffer, valuesBufferIndex + batchSize * ARRAY_LONG_INDEX_SCALE * 2, estimatedValueBufferMaxCapacity, LARGE, PRESERVE, bufferAllocator);

        int[] positions = getPositions();

        if (decodedBlock.mayHaveNull()) {
            for (int i = positionsOffset; i < positionsOffset + batchSize; i++) {
                int position = positions[i];

                long value = decodedBlock.getLong(position, 0);
                valuesBufferIndex = setLongUnchecked(valuesBuffer, valuesBufferIndex, value);

                value = decodedBlock.getLong(position, 8);
                valuesBufferIndex = setLongUnchecked(valuesBuffer, valuesBufferIndex, value);

                if (decodedBlock.isNull(position)) {
                    valuesBufferIndex -= ARRAY_LONG_INDEX_SCALE * 2;
                }
            }
        }
        else {
            for (int i = positionsOffset; i < positionsOffset + batchSize; i++) {
                int position = positions[i];

                long value = decodedBlock.getLong(position, 0);
                valuesBufferIndex = setLongUnchecked(valuesBuffer, valuesBufferIndex, value);

                value = decodedBlock.getLong(position, 8);
                valuesBufferIndex = setLongUnchecked(valuesBuffer, valuesBufferIndex, value);
            }
        }
    }
}
