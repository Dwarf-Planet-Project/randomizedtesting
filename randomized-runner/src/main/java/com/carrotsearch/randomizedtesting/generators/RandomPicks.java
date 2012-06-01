package com.carrotsearch.randomizedtesting.generators;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Random selections of objects.
 */
public final class RandomPicks {
  /**
   * Pick a random object from the given array.
   */
  public static <T> T randomFrom(Random r, T [] array) {
    if (array.length == 0)
      throw new IllegalArgumentException("Can't pick a random object from an empty array.");
    return array[r.nextInt(array.length)];
  }

  /**
   * Pick a random object from the given list.
   */
  public static <T> T randomFrom(Random r, List<T> list) {
    if (list.size() == 0)
      throw new IllegalArgumentException("Can't pick a random object from an empty list.");
    return list.get(r.nextInt(list.size()));
  }

  /**
   * Pick a random object from the collection. Requires linear scanning.
   */
  public static <T> T randomFrom(Random r, Collection<T> collection) {
    final int size = collection.size();
    if (size == 0)
      throw new IllegalArgumentException("Can't pick a random object from an empty collection.");
    int pick = r.nextInt(size);
    T value = null;
    for (Iterator<T> i = collection.iterator();; pick--) {
      value = i.next();
      if (pick == 0)
        break;
    }
    return value;
  }
}
