/*
 The MIT License

 Copyright (c) 2010-2021 Paul R. Holser, Jr.

 Permission is hereby granted, free of charge, to any person obtaining
 a copy of this software and associated documentation files (the
 "Software"), to deal in the Software without restriction, including
 without limitation the rights to use, copy, modify, merge, publish,
 distribute, sublicense, and/or sell copies of the Software, and to
 permit persons to whom the Software is furnished to do so, subject to
 the following conditions:

 The above copyright notice and this permission notice shall be
 included in all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package com.pholser.junit.quickcheck.generator.java.util;

import static com.pholser.junit.quickcheck.internal.Lists.removeFrom;
import static com.pholser.junit.quickcheck.internal.Lists.shrinksOfOneItem;
import static com.pholser.junit.quickcheck.internal.Ranges.Type.INTEGRAL;
import static com.pholser.junit.quickcheck.internal.Ranges.checkRange;
import static com.pholser.junit.quickcheck.internal.Reflection.findConstructor;
import static com.pholser.junit.quickcheck.internal.Reflection.instantiate;
import static com.pholser.junit.quickcheck.internal.Sequences.halving;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

import com.pholser.junit.quickcheck.generator.ComponentizedGenerator;
import com.pholser.junit.quickcheck.generator.Distinct;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.generator.Shrink;
import com.pholser.junit.quickcheck.generator.Size;
import com.pholser.junit.quickcheck.internal.Lists;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import java.math.BigDecimal;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

/**
 * <p>Base class for generators of {@link Map}s.</p>
 *
 * <p>The generated map has a number of entries limited by
 * {@link GenerationStatus#size()}, or else by the attributes of a {@link Size}
 * marking. The individual keys and values will have types corresponding to the
 * property parameter's type arguments.</p>
 *
 * @param <T> the type of map generated
 */
public abstract class MapGenerator<T extends Map>
    extends ComponentizedGenerator<T> {

    private Size sizeRange;
    private boolean distinct;

    protected MapGenerator(Class<T> type) {
        super(type);
    }

    /**
     * <p>Tells this generator to add key-value pairs to the generated map a
     * number of times within a specified minimum and/or maximum, inclusive,
     * chosen with uniform distribution.</p>
     *
     * <p>Note that maps disallow duplicate keys, so the number of pairs added
     * may not be equal to the map's {@link Map#size()}.</p>
     *
     * @param size annotation that gives the size constraints
     */
    public void configure(Size size) {
        this.sizeRange = size;
        checkRange(INTEGRAL, size.min(), size.max());
    }

    /**
     * Tells this generator to add entries whose keys are distinct from
     * each other.
     *
     * @param distinct Keys of generated entries will be distinct if this
     * param is not null
     */
    public void configure(Distinct distinct) {
        this.distinct = distinct != null;
    }

    @SuppressWarnings("unchecked")
    @Override public T generate(
        SourceOfRandomness random,
        GenerationStatus status) {

        int size = size(random, status);

        Generator<?> keyGenerator = componentGenerators().get(0);
        Stream<?> keyStream =
            Stream.generate(() -> keyGenerator.generate(random, status))
                .sequential();
        if (distinct)
            keyStream = keyStream.distinct();

        T items = empty();
        Generator<?> valueGenerator = componentGenerators().get(1);
        keyStream
            .map(key ->
                new SimpleEntry<>(
                    key,
                    valueGenerator.generate(random, status)))
            .filter(entry -> okToAdd(entry.getKey(), entry.getValue()))
            .limit(size)
            .forEach(entry -> items.put(entry.getKey(), entry.getValue()));

        return items;
    }

    @Override public List<T> doShrink(SourceOfRandomness random, T larger) {
        @SuppressWarnings("unchecked")
        List<Entry<?, ?>> entries = new ArrayList<>(larger.entrySet());

        List<T> shrinks = new ArrayList<>(removals(entries));

        @SuppressWarnings("unchecked")
        Shrink<Entry<?, ?>> entryShrink = entryShrinker(
            (Shrink<Object>) componentGenerators().get(0),
            (Shrink<Object>) componentGenerators().get(1));

        Stream<List<Entry<?, ?>>> oneEntryShrinks =
            shrinksOfOneItem(random, entries, entryShrink)
                .stream();
        if (distinct)
            oneEntryShrinks = oneEntryShrinks.filter(MapGenerator::isKeyDistinct);

        shrinks.addAll(
            oneEntryShrinks
                .map(this::convert)
                .filter(this::inSizeRange)
                .collect(toList()));

        return shrinks;
    }

    @Override public int numberOfNeededComponents() {
        return 2;
    }

    @Override public BigDecimal magnitude(Object value) {
        Map<?, ?> narrowed = narrow(value);

        if (narrowed.isEmpty())
            return BigDecimal.ZERO;

        BigDecimal keysMagnitude =
            narrowed.keySet().stream()
                .map(e -> componentGenerators().get(0).magnitude(e))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal valuesMagnitude =
            narrowed.values().stream()
                .map(e -> componentGenerators().get(1).magnitude(e))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return BigDecimal.valueOf(narrowed.size())
            .multiply(keysMagnitude)
            .add(valuesMagnitude);
    }

    protected final T empty() {
        return instantiate(findConstructor(types().get(0)));
    }

    protected boolean okToAdd(Object key, Object value) {
        return true;
    }

    private boolean inSizeRange(T target) {
        return sizeRange == null
            || (target.size() >= sizeRange.min() && target.size() <= sizeRange.max());
    }

    private int size(SourceOfRandomness random, GenerationStatus status) {
        return sizeRange != null
            ? random.nextInt(sizeRange.min(), sizeRange.max())
            : status.size();
    }

    private List<T> removals(List<Entry<?, ?>> items) {
        return stream(halving(items.size()).spliterator(), false)
            .map(i -> removeFrom(items, i))
            .flatMap(Collection::stream)
            .map(this::convert)
            .filter(this::inSizeRange)
            .collect(toList());
    }

    @SuppressWarnings("unchecked")
    private T convert(List<?> entries) {
        T converted = empty();

        for (Object each : entries) {
            Entry<?, ?> entry = (Entry<?, ?>) each;
            converted.put(entry.getKey(), entry.getValue());
        }

        return converted;
    }

    private Shrink<Entry<?, ?>> entryShrinker(
        Shrink<Object> keyShrinker,
        Shrink<Object> valueShrinker) {

        return (r, e) -> {
            @SuppressWarnings("unchecked")
            Entry<Object, Object> entry = (Entry<Object, Object>) e;

            List<Object> keyShrinks = keyShrinker.shrink(r, entry.getKey());
            List<Object> valueShrinks = valueShrinker.shrink(r, entry.getValue());
            List<Entry<?, ?>> shrinks = new ArrayList<>();
            shrinks.addAll(
                keyShrinks.stream()
                    .map(k -> new SimpleEntry<>(k, entry.getValue()))
                    .collect(toList()));
            shrinks.addAll(
                valueShrinks.stream()
                    .map(v -> new SimpleEntry<>(entry.getKey(), v))
                    .collect(toList()));

            return shrinks;
        };
    }

    private static boolean isKeyDistinct(List<Entry<?, ?>> entries) {
        return Lists.isDistinct(
            entries.stream()
                .map(Entry::getKey)
                .collect(toList()));
    }
}
