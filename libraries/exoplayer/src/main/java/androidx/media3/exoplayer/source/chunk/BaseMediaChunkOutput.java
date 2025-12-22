/*
 * Copyright (C) 2017 The Android Open Source Project
 *
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
package androidx.media3.exoplayer.source.chunk;

import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.source.chunk.ChunkExtractor.TrackOutputProvider;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.TrackOutput;

/**
 * A {@link TrackOutputProvider} that provides {@link TrackOutput TrackOutputs} based on a
 * predefined mapping from track type to output.
 */
@UnstableApi
public final class BaseMediaChunkOutput implements TrackOutputProvider {

  private static final String TAG = "BaseMediaChunkOutput";

  private final @C.TrackType int[] trackTypes;
  private final int[] trackIds;
  private final SampleQueue[] sampleQueues;

  /**
   * @param trackTypes The track types of the individual track outputs.
   * @param sampleQueues The individual sample queues.
   */
  public BaseMediaChunkOutput(int[] trackTypes, SampleQueue[] sampleQueues) {
    this.trackTypes = trackTypes;
    this.trackIds = new int[trackTypes.length];
    for (int i = 0; i < trackTypes.length; i++) {
      this.trackIds[i] = Integer.MIN_VALUE;
    }
    this.sampleQueues = sampleQueues;
  }

  @Override
  public TrackOutput track(int id, @C.TrackType int type) {
    return track(id, type, C.ID_UNSET);
  }

  @Override
  public TrackOutput track(int id, @C.TrackType int type, int scalableBaseId) {
    for (int i = 0; i < trackTypes.length; i++) {
      if (type == trackTypes[i]) {
        if (trackIds[i] == Integer.MIN_VALUE) {
          trackIds[i] = id;
          return sampleQueues[i];
        } else if (scalableBaseId == C.ID_UNSET && trackIds[i] == id) {
          return sampleQueues[i];
        }
        else if (scalableBaseId != C.ID_UNSET && sampleQueues[i].isEnhancement()) {
          // ChunkSampleStream operates on sampleQueues[0] so the enhanced sampleQueue
          // must be moved to the index 0 in order for it to be the primary one
          moveToFront(trackTypes, i);
          moveToFront(trackIds, i);
          moveToFront(sampleQueues, i);
          return sampleQueues[0];
        }
      }
    }
    Log.e(TAG, "Unmatched track of type: " + type);
    return new DiscardingTrackOutput();
  }

  /** Returns the current absolute write indices of the individual sample queues. */
  public int[] getWriteIndices() {
    int[] writeIndices = new int[sampleQueues.length];
    for (int i = 0; i < sampleQueues.length; i++) {
      writeIndices[i] = sampleQueues[i].getWriteIndex();
    }
    return writeIndices;
  }

  /**
   * Sets an offset that will be added to the timestamps (and sub-sample timestamps) of samples
   * subsequently written to the sample queues.
   */
  public void setSampleOffsetUs(long sampleOffsetUs) {
    for (SampleQueue sampleQueue : sampleQueues) {
      sampleQueue.setSampleOffsetUs(sampleOffsetUs);
    }
  }

  public static <T> void moveToFront(T[] array, int index) {
    if (array.length == 0) return;
    if (index <= 0 || index >= array.length) return;

    T element = array[index];
    System.arraycopy(array, 0, array, 1, index);
    array[0] = element;
  }

  public static void moveToFront(int[] array, int index) {
    if (array.length == 0) return;
    if (index <= 0 || index >= array.length) return;

    int element = array[index];
    System.arraycopy(array, 0, array, 1, index);
    array[0] = element;
  }
}
