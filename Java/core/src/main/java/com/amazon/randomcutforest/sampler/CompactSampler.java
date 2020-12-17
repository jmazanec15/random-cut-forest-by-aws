/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.randomcutforest.sampler;

import static com.amazon.randomcutforest.CommonUtils.checkNotNull;
import static com.amazon.randomcutforest.CommonUtils.checkState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import com.amazon.randomcutforest.executor.Sequential;

/**
 * CompactSampler is an implementation of time-based reservoir sampling. When a
 * point is submitted to the sampler, the decision to accept the point gives
 * more weight to newer points compared to older points. The newness of a point
 * is determined by its sequence index, and larger sequence indexes are
 * considered newer.
 * <p>
 * The sampler algorithm is an example of the general weighted reservoir
 * sampling algorithm, which works like this:
 * <ol>
 * <li>For each item i choose a random number u(i) uniformly from the interval
 * (0, 1) and compute the weight function <code>-(1 / c(i)) * log u(i)</code>,
 * for a given coefficient function c(i).</li>
 * <li>For a sample size of N, maintain a list of the N items with the smallest
 * weights.</li>
 * <li>When a new item is submitted to sampler, compute its weight. If it is
 * smaller than the largest weight currently contained in the sampler, then the
 * item with the largest weight is evicted from the sample and replaced by the
 * new item.</li>
 * </ol>
 * <p>
 * The coefficient function used by CompactSampler is: *
 * <code>c(i) = exp(lambda * sequenceIndex(i))</code>.
 */
public class CompactSampler implements IStreamSampler<Integer> {

    /**
     * A max-heap containing the weighted points currently in sample. The head
     * element is the lowest priority point in the sample (or, equivalently, is the
     * point with the greatest weight).
     */
    protected final float[] weight;

    protected final int[] pointIndex;

    protected final long[] sequenceIndex;
    /**
     * The number of points in the sample when full.
     */
    protected final int capacity;

    protected int size;
    /**
     * The decay factor used for generating the weight of the point. For greater
     * values of lambda we become more biased in favor of recent points.
     */
    private final double lambda;
    /**
     * The random number generator used in sampling.
     */
    private final Random random;
    /**
     * The point evicted by the last call to {@link #sample}, or null if the new
     * point was not accepted by the sampler.
     */
    private transient Sequential<Integer> evictedPoint;

    private final boolean storeSequenceIndexesEnabled;

    /**
     * Construct a new CompactSampler.
     *
     * @param sampleSize                  The number of points in the sampler when
     *                                    full.
     * @param lambda                      The decay factor used for generating the
     *                                    weight of the point. For greater values of
     *                                    lambda we become more biased in favor of
     *                                    recent points.
     * @param seed                        The seed value used to create a random
     *                                    number generator.
     * @param storeSequenceIndexesEnabled If true, then the sequence indexes of
     *                                    sampled points will be stored in the
     *                                    sampler.
     */
    public CompactSampler(int sampleSize, double lambda, long seed, boolean storeSequenceIndexesEnabled) {
        this(sampleSize, lambda, new Random(seed), storeSequenceIndexesEnabled);
    }

    /**
     * Construct a new CompactSampler.
     *
     * @param sampleSize                  The number of points in the sampler when
     *                                    full.
     * @param lambda                      The decay factor used for generating the
     *                                    weight of the point. For greater values of
     *                                    lambda we become more biased in favor of
     *                                    recent points.
     * @param random                      A random number generator that will be
     *                                    used in sampling.
     * @param storeSequenceIndexesEnabled If true, then the sequence indexes of
     *                                    sampled points will be stored in the
     *                                    sampler.
     */
    public CompactSampler(int sampleSize, double lambda, Random random, boolean storeSequenceIndexesEnabled) {
        this.capacity = sampleSize;
        size = 0;
        weight = new float[sampleSize];
        pointIndex = new int[sampleSize];
        this.storeSequenceIndexesEnabled = storeSequenceIndexesEnabled;
        if (storeSequenceIndexesEnabled) {
            this.sequenceIndex = new long[sampleSize];
        } else {
            this.sequenceIndex = null;
        }
        this.random = random;
        this.lambda = lambda;
    }

    /**
     * Construct a new compact sampler with the provided state. The 3 arrays
     * {@code weight}, {@code pointIndex}, and {@code sequenceIndex} define the
     * points stored in the sampler. In particular, for a given index {@code i} the
     * values {@code weight[i]}, {@code pointIndex[i]}, and {@code sequenceIndex[i]}
     * together define a single weighted point.
     *
     * Internally, the points defined by {@code weight}, {@code pointIndex}, and
     * {@code sequenceIndex} are stored in a max-heap with the weight value
     * determining the heap structure.
     *
     * @param sampleSize    The number of points in the sampler when full.
     * @param lambda        The decay factor used for generating the weight of the
     *                      point. For greater values of lambda the sampler is more
     *                      biased in favor of recent points.
     * @param random        A random number generator that will be used in sampling.
     * @param weight        An array of weights used for time-decay reservoir
     *                      sampling.
     * @param pointIndex    An array of point indexes identifying the sampled
     *                      points.
     * @param sequenceIndex An array of sequence indexes corresponding to the
     *                      sampled points.
     * @param validateHeap  If true, then the constructor will fail with an
     *                      IllegalArgumentException if the weight array doesn't
     *                      satisfy the heap property.
     */
    public CompactSampler(int sampleSize, double lambda, Random random, float[] weight, int[] pointIndex,
            long[] sequenceIndex, boolean validateHeap) {
        this(sampleSize, lambda, random, sequenceIndex != null);
        checkNotNull(weight, "weight must not be null");
        checkNotNull(pointIndex, "pointIndex must not be null");

        size = weight.length;
        System.arraycopy(weight, 0, this.weight, 0, size);
        System.arraycopy(pointIndex, 0, this.pointIndex, 0, size);

        if (sequenceIndex != null) {
            System.arraycopy(sequenceIndex, 0, this.sequenceIndex, 0, size);
        }

        reheap(validateHeap);
    }

    /**
     * This convenience constructor creates a SimpleStreamSampler with lambda equal
     * to 0, which is equivalent to uniform sampling on the stream.
     *
     * @param sampleSize                  The number of points in the sampler when
     *                                    full.
     * @param seed                        The seed value used to create a random
     *                                    number generator.
     * @param storeSequenceIndexesEnabled If true, then the sequence indexes of
     *                                    sampled points will be stored in the
     *                                    sampler.
     * @return a new SimpleStreamSampler which samples uniformly from its input.
     */
    public static CompactSampler uniformSampler(int sampleSize, long seed, boolean storeSequenceIndexesEnabled) {
        return new CompactSampler(sampleSize, 0.0, seed, storeSequenceIndexesEnabled);
    }

    @Override
    public Optional<Float> acceptSample(long sequenceIndex) {
        evictedPoint = null;
        float weight = computeWeight(sequenceIndex);
        if (size < capacity) {
            return Optional.of(weight);
        } else if (weight < this.weight[0]) {
            long evictedIndex = storeSequenceIndexesEnabled ? this.sequenceIndex[0] : 0L;
            evictedPoint = new Sequential<>(this.pointIndex[0], this.weight[0], evictedIndex);
            --size;
            this.weight[0] = this.weight[size];
            this.pointIndex[0] = this.pointIndex[size];
            swapDown(0);
            return Optional.of(weight);
        } else {
            return Optional.empty();
        }
    }

    /**
     * Check to see if the weight at current index is greater than or equal to the
     * weight at each corresponding child index. If validate is true then throw an
     * IllegalStateException, otherwise swap the nodes and perform the same check at
     * the next level. Continue until you reach a level where the parent node's
     * weight is greater than or equal to both children's weights, or until there
     * are no more levels to descend.
     *
     * @param startIndex The index of node to start with.
     * @param validate   If true, a violation of the heap property will throw an
     *                   IllegalStateException. If false, then swap nodes that
     *                   violate the heap property.
     */
    private void swapDown(int startIndex, boolean validate) {
        int current = startIndex;
        while (2 * current + 1 < size) {
            int maxIndex = 2 * current + 1;
            if (2 * current + 2 < size && weight[2 * current + 2] > weight[maxIndex]) {
                maxIndex = 2 * current + 2;
            }
            if (weight[maxIndex] > weight[current]) {
                if (validate) {
                    throw new IllegalStateException("the heap property is not satisfied at index " + current);
                }
                swapWeights(current, maxIndex);
                current = maxIndex;
            } else {
                break;
            }
        }
    }

    private void swapDown(int startIndex) {
        swapDown(startIndex, false);
    }

    public void reheap(boolean validate) {
        for (int i = (size + 1) / 2; i >= 0; i--) {
            swapDown(i, validate);
        }
    }

    @Override
    public void addSample(Integer pointIndex, float weight, long sequenceIndex) {
        checkState(size < capacity, "sampler full");
        this.weight[size] = weight;
        this.pointIndex[size] = pointIndex;
        if (storeSequenceIndexesEnabled) {
            this.sequenceIndex[size] = sequenceIndex;
        }
        int current = size++;
        while (current > 0) {
            int tmp = (current - 1) / 2;
            if (this.weight[tmp] < this.weight[current]) {
                swapWeights(current, tmp);
                current = tmp;
            } else
                break;
        }
    }

    @Override
    public List<Sequential<Integer>> getSequentialSamples() {
        checkState(storeSequenceIndexesEnabled, "to call this method storeSequenceIndexesEnabled must be true");
        List<Sequential<Integer>> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            result.add(new Sequential<>(pointIndex[i], weight[i], sequenceIndex[i]));
        }
        return result;
    }

    @Override
    public List<Weighted<Integer>> getWeightedSamples() {
        List<Weighted<Integer>> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            result.add(new Weighted<>(pointIndex[i], weight[i]));
        }
        return result;
    }

    /**
     * @return the point evicted by the most recent call to {@link #sample}, or null
     *         if no point was evicted.
     */
    public Optional<Sequential<Integer>> getEvictedPoint() {
        return Optional.ofNullable(evictedPoint);
    }

    /**
     * Score is computed as <code>-log(w(i)) + log(-log(u(i))</code>, where
     *
     * <ul>
     * <li><code>w(i) = exp(lambda * sequenceIndex)</code></li>
     * <li><code>u(i)</code> is chosen uniformly from (0, 1)</li>
     * </ul>
     * <p>
     * A higher score means lower priority. So the points with the lower score have
     * higher chance of making it to the sample.
     *
     * @param sequenceIndex The sequenceIndex of the point whose score is being
     *                      computed.
     * @return the weight value used to define point priority
     */
    protected float computeWeight(long sequenceIndex) {
        double randomNumber = 0d;
        while (randomNumber == 0d) {
            randomNumber = random.nextDouble();
        }

        return (float) (-sequenceIndex * lambda + Math.log(-Math.log(randomNumber)));
    }

    /**
     * @return the number of points contained by the sampler when full.
     */
    @Override
    public int getCapacity() {
        return capacity;
    }

    /**
     * @return the number of points currently contained by the sampler.
     */
    @Override
    public int size() {
        return size;
    }

    public float[] getWeightArray() {
        return weight;
    }

    public int[] getPointIndexArray() {
        return pointIndex;
    }

    public long[] getSequenceIndexArray() {
        return sequenceIndex;
    }

    /**
     * @return the lambda value that determines the amount of bias given toward
     *         recent points. Larger values of lambda indicate a greater bias toward
     *         recent points. A value of 0 corresponds to a uniform sample over the
     *         stream.
     */
    public double getLambda() {
        return lambda;
    }

    public boolean isStoreSequenceIndexesEnabled() {
        return storeSequenceIndexesEnabled;
    }

    private void swapWeights(int a, int b) {
        int tmp = pointIndex[a];
        pointIndex[a] = pointIndex[b];
        pointIndex[b] = tmp;

        float tmpDouble = weight[a];
        weight[a] = weight[b];
        weight[b] = tmpDouble;

        if (storeSequenceIndexesEnabled) {
            long tmpLong = sequenceIndex[a];
            sequenceIndex[a] = sequenceIndex[b];
            sequenceIndex[b] = tmpLong;
        }
    }
}
