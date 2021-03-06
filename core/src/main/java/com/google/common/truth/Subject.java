/*
 * Copyright (c) 2011 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.common.truth;

import static com.google.common.base.CaseFormat.LOWER_CAMEL;
import static com.google.common.base.CaseFormat.UPPER_CAMEL;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Fact.fact;
import static com.google.common.truth.Fact.factWithoutValue;
import static com.google.common.truth.Platform.doubleToString;
import static com.google.common.truth.Platform.floatToString;
import static com.google.common.truth.StringUtil.format;
import static com.google.common.truth.SubjectUtils.accumulate;
import static com.google.common.truth.SubjectUtils.concat;
import static com.google.common.truth.SubjectUtils.sandwich;
import static java.util.Arrays.asList;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.primitives.Booleans;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Chars;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.google.common.primitives.Shorts;
import com.google.common.truth.FailureMetadata.OldAndNewValuesAreSimilar;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CompatibleWith;
import com.google.errorprone.annotations.ForOverride;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;

/**
 * An object that lets you perform checks on the value under test. For example, {@code Subject}
 * contains {@link #isEqualTo(Object)} and {@link #isInstanceOf(Class)}, and {@link StringSubject}
 * contains {@link StringSubject#startsWith startsWith(String)}.
 *
 * <p>To create a {@code Subject} instance, most users will call an {@link Truth#assertThat
 * assertThat} method. For information about other ways to create an instance, see <a
 * href="https://google.github.io/truth/faq#full-chain">this FAQ entry</a>.
 *
 * <h3>For people extending Truth</h3>
 *
 * <p>For information about writing a custom {@link Subject}, see <a
 * href="https://google.github.io/truth/extension">our doc on extensions</a>.
 *
 * @param <S> the self-type, allowing {@code this}-returning methods to avoid needing subclassing
 * @param <T> the type of the object being tested by this {@code Subject}
 * @author David Saff
 * @author Christian Gruber
 */
public class Subject<S extends Subject<S, T>, T> {
  /**
   * In a fluent assertion chain, the argument to the common overload of {@link
   * StandardSubjectBuilder#about(Subject.Factory) about}, the method that specifies what kind of
   * {@link Subject} to create.
   *
   * <p>For more information about the fluent chain, see <a
   * href="https://google.github.io/truth/faq#full-chain">this FAQ entry</a>.
   *
   * <h3>For people extending Truth</h3>
   *
   * <p>When you write a custom subject, see <a href="https://google.github.io/truth/extension">our doc on
   * extensions</a>. It explains where {@code Subject.Factory} fits into the process.
   */
  public interface Factory<SubjectT extends Subject<SubjectT, ActualT>, ActualT> {
    /** Creates a new {@link Subject}. */
    SubjectT createSubject(FailureMetadata metadata, ActualT actual);
  }

  private static final FailureStrategy IGNORE_STRATEGY =
      new FailureStrategy() {
        @Override
        public void fail(AssertionError failure) {}
      };

  private final FailureMetadata metadata;
  private final T actual;
  private String customName = null;
  @Nullable private final String typeDescriptionOverride;

  /**
   * Constructor for use by subclasses. If you want to create an instance of this class itself, call
   * {@link Subject#check}{@code .that(actual)}.
   */
  protected Subject(FailureMetadata metadata, @Nullable T actual) {
    this(metadata, actual, /*typeDescriptionOverride=*/ null);
  }

  /**
   * Special constructor that lets subclasses provide a description of the type they're testing. For
   * example, {@link ThrowableSubject} passes the description "throwable." Normally, Truth is able
   * to infer this name from the class name. However, if we lack runtime type information (notably,
   * under j2cl with class metadata off), we might not have access to the original class name.
   *
   * <p>We don't expect to make this a public API: Class names are nearly always available. It's
   * just that we want to be able to run Truth's own tests run with class metadata off, and it's
   * easier to tweak the subjects to know their own names rather than generalize the tests to accept
   * obfuscated names.
   */
  Subject(FailureMetadata metadata, @Nullable T actual, @Nullable String typeDescriptionOverride) {
    this.metadata = metadata.updateForSubject(this);
    this.actual = actual;
    this.typeDescriptionOverride = typeDescriptionOverride;
  }

  /** An internal method used to obtain the value set by {@link #named(String, Object...)}. */
  protected String internalCustomName() {
    return customName;
  }

  /**
   * Adds a prefix to the subject, when it is displayed in error messages. This is especially useful
   * in the context of types that have no helpful {@code toString()} representation, e.g. boolean.
   * Writing {@code assertThat(foo).named("foo").isTrue();} then results in a more reasonable error
   * message.
   *
   * <p>{@code named()} takes a format template and argument objects which will be substituted into
   * the template, similar to {@link String#format(String, Object...)}, the chief difference being
   * that extra parameters (for which there are no template variables) will be appended to the
   * resulting string in brackets. Additionally, this only supports the {@code %s} template variable
   * type.
   */
  @SuppressWarnings("unchecked")
  @CanIgnoreReturnValue
  public S named(String format, Object... args) {
    checkNotNull(format, "Name passed to named() cannot be null.");
    this.customName = StringUtil.format(format, args);
    return (S) this;
  }

  /** Fails if the subject is not null. */
  public void isNull() {
    if (actual() != null) {
      fail("is null");
    }
  }

  /** Fails if the subject is null. */
  public void isNotNull() {
    if (actual() == null) {
      failWithoutActual("is a non-null reference");
    }
  }

  /**
   * Fails if the subject is not equal to the given object. For the purposes of this comparison, two
   * objects are equal if any of the following is true:
   *
   * <ul>
   *   <li>they are equal according to {@link Objects#equal}
   *   <li>they are arrays and are considered equal by the appropriate {@link Arrays#equals}
   *       overload
   *   <li>they are boxed integer types ({@code Byte}, {@code Short}, {@code Character}, {@code
   *       Integer}, or {@code Long}) and they are numerically equal when converted to {@code Long}.
   * </ul>
   */
  public void isEqualTo(@Nullable Object expected) {
    ComparisonResult difference = describeDifference(expected);
    if (!difference.valuesAreEqual()) {
      failEqualityCheck("is equal to", expected, difference);
    }
  }

  /**
   * Fails if the subject is equal to the given object. The meaning of equality is the same as for
   * the {@link #isEqualTo} method.
   */
  public void isNotEqualTo(@Nullable Object unexpected) {
    ComparisonResult difference = describeDifference(unexpected);
    if (difference.valuesAreEqual()) {
      fail("is not equal to", formatActualOrExpected(unexpected));
    }
  }

  /**
   * Returns whether {@code actual} equals {@code expected} differ and, in some cases, a description
   * of how they differ.
   *
   * <p>The equality check follows the rules described on {@link #isEqualTo}.
   */
  private ComparisonResult describeDifference(@Nullable Object expected) {
    if (actual() == null && expected == null) {
      return ComparisonResult.equal();
    } else if (actual() == null || expected == null) {
      return ComparisonResult.differentNoDescription();
    } else if (actual() instanceof byte[] && expected instanceof byte[]) {
      /*
       * For a special error message and to use faster Arrays.equals to avoid at least one timeout.
       *
       * TODO(cpovirk): For performance, use Arrays.equals for other array types (here and/or in
       * checkArrayEqualsRecursive)? Exception: double[] and float[], whose GWT implementations I
       * think may have both false positives and false negatives (so we can't even use Arrays.equals
       * as a fast path for them, nor deepEquals for an Object[] that might contain them). We would
       * still fall back to the slower checkArrayEqualsRecursive to produce a nicer failure message
       * -- but naturally only for tests that are about to fail, when performance matters less.
       */
      return checkByteArrayEquals((byte[]) expected, (byte[]) actual());
    } else if (actual().getClass().isArray() && expected.getClass().isArray()) {
      return checkArrayEqualsRecursive(expected, actual, "");
    } else if (isIntegralBoxedPrimitive(actual()) && isIntegralBoxedPrimitive(expected)) {
      return ComparisonResult.fromEqualsResult(integralValue(actual()) == integralValue(expected));
    } else if (actual() instanceof Double && expected instanceof Double) {
      return ComparisonResult.fromEqualsResult(
          Double.compare((Double) actual(), (Double) expected) == 0);
    } else if (actual() instanceof Float && expected instanceof Float) {
      return ComparisonResult.fromEqualsResult(
          Float.compare((Float) actual(), (Float) expected) == 0);
    } else {
      return ComparisonResult.fromEqualsResult(actual() == expected || actual().equals(expected));
    }
  }

  private static boolean isIntegralBoxedPrimitive(@Nullable Object o) {
    return o instanceof Byte
        || o instanceof Short
        || o instanceof Character
        || o instanceof Integer
        || o instanceof Long;
  }

  private static long integralValue(Object o) {
    if (o instanceof Character) {
      return (long) ((Character) o).charValue();
    } else if (o instanceof Number) {
      return ((Number) o).longValue();
    } else {
      throw new AssertionError(o + " must be either a Character or a Number.");
    }
  }

  /** Fails if the subject is not the same instance as the given object. */
  public void isSameAs(@Nullable @CompatibleWith("T") Object other) {
    if (actual() != other) {
      failEqualityCheck(
          "is the same instance as",
          other,
          ComparisonResult
              .differentNoDescription() // or maybe equal; what we mean is "nothing to describe"
          );
    }
  }

  /** Fails if the subject is the same instance as the given object. */
  public void isNotSameAs(@Nullable @CompatibleWith("T") Object other) {
    if (actual() == other) {
      fail("is not the same instance as", formatActualOrExpected(other));
    }
  }

  /** Fails if the subject is not an instance of the given class. */
  public void isInstanceOf(Class<?> clazz) {
    if (clazz == null) {
      throw new NullPointerException("clazz");
    }
    if (actual() == null) {
      fail("is an instance of", clazz.getName());
      return;
    }
    if (!Platform.isInstanceOfType(actual(), clazz)) {
      if (classMetadataUnsupported()) {
        throw new UnsupportedOperationException(
            actualAsString()
                + ", an instance of "
                + actual().getClass().getName()
                + ", may or may not be an instance of "
                + clazz.getName()
                + ". Under -XdisableClassMetadata, we do not have enough information to tell.");
      }
      failWithBadResults(
          "is an instance of", clazz.getName(), "is an instance of", actual().getClass().getName());
    }
  }

  /** Fails if the subject is an instance of the given class. */
  public void isNotInstanceOf(Class<?> clazz) {
    if (clazz == null) {
      throw new NullPointerException("clazz");
    }
    if (classMetadataUnsupported()) {
      throw new UnsupportedOperationException(
          "isNotInstanceOf is not supported under -XdisableClassMetadata");
    }
    if (actual() == null) {
      return; // null is not an instance of clazz.
    }
    if (Platform.isInstanceOfType(actual(), clazz)) {
      failWithRawMessage(
          "%s expected not to be an instance of %s, but was.", actualAsString(), clazz.getName());
    }
  }

  /** Fails unless the subject is equal to any element in the given iterable. */
  public void isIn(Iterable<?> iterable) {
    if (!Iterables.contains(iterable, actual())) {
      fail("is equal to any element in", iterable);
    }
  }

  /** Fails unless the subject is equal to any of the given elements. */
  public void isAnyOf(
      @Nullable @CompatibleWith("T") Object first,
      @Nullable @CompatibleWith("T") Object second,
      @Nullable Object... rest) {
    isIn(accumulate(first, second, rest));
  }

  /** Fails if the subject is equal to any element in the given iterable. */
  public void isNotIn(Iterable<?> iterable) {
    if (Iterables.contains(iterable, actual())) {
      failWithRawMessage("Not true that %s is not in %s.", actualAsString(), iterable);
    }
  }

  /** Fails if the subject is equal to any of the given elements. */
  public void isNoneOf(
      @Nullable @CompatibleWith("T") Object first,
      @Nullable @CompatibleWith("T") Object second,
      @Nullable Object... rest) {
    isNotIn(accumulate(first, second, rest));
  }

  /** @deprecated Prefer {@code #actual()} for direct access to the subject. */
  @Deprecated
  protected T getSubject() {
    // TODO(cgruber): move functionality to actual() and delete when no callers.
    return actual;
  }

  /** Returns the unedited, unformatted raw actual value. */
  protected final T actual() {
    return getSubject();
  }

  /**
   * Returns a string representation of the actual value. This will either be the toString() of the
   * value or a prefixed "name" along with the string representation.
   */
  /*
   * TODO(cpovirk): If we delete named(), this method will be a thin wrapper around
   * actualCustomStringRepresentation(), one that merely adds angle brackets (which we might not
   * want it to do). But if we delete actualCustomStringRepresentation() in favor of a "format
   * actual or expected" method, as described in a comment on that method, then it becomes useful
   * again (though there's still a question of what to do with the angle brackets).
   */
  protected final String actualAsString() {
    String formatted = actualCustomStringRepresentation();
    if (customName != null) {
      // Covers some rare cases where a type might return "" from their custom formatter.
      // This is actually pretty terrible, as it comes from subjects overriding (formerly)
      // getDisplaySubject() in cases of .named() to make it not prefixing but replacing.
      // That goes against the stated contract of .named().  Once displayedAs() is in place,
      // we can rip this out and callers can use that instead.
      // TODO(cgruber)
      return customName + (formatted.isEmpty() ? "" : " (<" + formatted + ">)");
    } else {
      return "<" + formatted + ">";
    }
  }

  /** Like {@link #actualAsString()} but without angle brackets around the value. */
  final String actualAsStringNoBrackets() {
    String formatted = actualCustomStringRepresentation();
    if (customName != null) {
      return customName + (formatted.isEmpty() ? "" : " (" + formatted + ")");
    } else {
      return formatted;
    }
  }

  /**
   * Supplies the direct string representation of the actual value to other methods which may prefix
   * or otherwise position it in an error message. This should only be overridden to provide an
   * improved string representation of the value under test, as it would appear in any given error
   * message, and should not be used for additional prefixing.
   *
   * <p>Subjects should override this with care.
   *
   * <p>By default, this returns {@code String.ValueOf(getActualValue())}.
   */
  /*
   * TODO(cpovirk): Consider whether this API pulls its weight. If users want to format the actual
   * value, maybe they should do so themselves? Of course, they won't have a chance to use a custom
   * format for inherited implementations like isEqualTo(). But if they want to format the actual
   * value specially, then it seems likely that they'll want to format the expected value specially,
   * too. And that applies just as well to APIs like isIn(). Maybe we'll want an API that supports
   * formatting those values, too (like formatActualOrExpected below)? See also the related
   * b/70930431. But note that we are likely to use this from FailureMetadata, at least in the short
   * term, for better or for worse.
   */
  @ForOverride
  protected String actualCustomStringRepresentation() {
    return formatActualOrExpected(actual());
  }

  final String actualCustomStringRepresentationForPackageMembersToCall() {
    return actualCustomStringRepresentation();
  }

  private final String formatActualOrExpected(@Nullable Object o) {
    if (o instanceof byte[]) {
      return base16((byte[]) o);
    } else if (o != null && o.getClass().isArray()) {
      String wrapped = Iterables.toString(stringableIterable(new Object[] {o}));
      return wrapped.substring(1, wrapped.length() - 1);
    } else if (o instanceof Double) {
      return doubleToString((Double) o);
    } else if (o instanceof Float) {
      return floatToString((Float) o);
    } else {
      return String.valueOf(o);
    }
  }

  // We could add a dep on com.google.common.io, but that seems overkill for base16 encoding
  private static String base16(byte[] bytes) {
    StringBuilder sb = new StringBuilder(2 * bytes.length);
    for (byte b : bytes) {
      sb.append(hexDigits[(b >> 4) & 0xf]).append(hexDigits[b & 0xf]);
    }
    return sb.toString();
  }

  private static final char[] hexDigits = "0123456789ABCDEF".toCharArray();

  private static Iterable<?> stringableIterable(Object[] array) {
    return Iterables.transform(asList(array), STRINGIFY);
  }

  private static final Function<Object, Object> STRINGIFY =
      new Function<Object, Object>() {
        @Override
        public Object apply(@Nullable Object input) {
          if (input != null && input.getClass().isArray()) {
            Iterable<?> iterable;
            if (input.getClass() == boolean[].class) {
              iterable = Booleans.asList((boolean[]) input);
            } else if (input.getClass() == int[].class) {
              iterable = Ints.asList((int[]) input);
            } else if (input.getClass() == long[].class) {
              iterable = Longs.asList((long[]) input);
            } else if (input.getClass() == short[].class) {
              iterable = Shorts.asList((short[]) input);
            } else if (input.getClass() == byte[].class) {
              iterable = Bytes.asList((byte[]) input);
            } else if (input.getClass() == double[].class) {
              iterable = doubleArrayAsString((double[]) input);
            } else if (input.getClass() == float[].class) {
              iterable = floatArrayAsString((float[]) input);
            } else if (input.getClass() == char[].class) {
              iterable = Chars.asList((char[]) input);
            } else {
              iterable = Arrays.asList((Object[]) input);
            }
            return Iterables.transform(iterable, STRINGIFY);
          }
          return input;
        }
      };

  /**
   * The result of comparing two objects for equality. This includes both the "equal"/"not-equal"
   * bit and, in the case of "not equal," optional facts describing the difference.
   */
  private static final class ComparisonResult {
    /**
     * If {@code equal} is true, returns an equal result; if false, a non-equal result with no
     * description.
     */
    static ComparisonResult fromEqualsResult(boolean equal) {
      return equal ? EQUAL : DIFFERENT_NO_DESCRIPTION;
    }

    /** Returns a non-equal result with the given description. */
    static ComparisonResult differentWithDescription(Fact... facts) {
      return new ComparisonResult(ImmutableList.copyOf(facts));
    }

    /** Returns an equal result. */
    static ComparisonResult equal() {
      return EQUAL;
    }

    /** Returns a non-equal result with no description. */
    static ComparisonResult differentNoDescription() {
      return DIFFERENT_NO_DESCRIPTION;
    }

    private static final ComparisonResult EQUAL = new ComparisonResult(null);
    private static final ComparisonResult DIFFERENT_NO_DESCRIPTION =
        new ComparisonResult(ImmutableList.<Fact>of());

    @Nullable private final ImmutableList<Fact> facts;

    private ComparisonResult(ImmutableList<Fact> facts) {
      this.facts = facts;
    }

    boolean valuesAreEqual() {
      return facts == null;
    }

    ImmutableList<Fact> factsOrEmpty() {
      return firstNonNull(facts, ImmutableList.<Fact>of());
    }
  }

  /**
   * Returns null if the arrays are equal. If not equal, returns a string comparing the two arrays,
   * displaying them in the style "[1, 2, 3]" to supplement the main failure message, which uses the
   * style "010203."
   */
  private static ComparisonResult checkByteArrayEquals(byte[] expected, byte[] actual) {
    if (Arrays.equals(expected, actual)) {
      return ComparisonResult.equal();
    }
    return ComparisonResult.differentWithDescription(
        fact("expected", Arrays.toString(expected)), fact("but was", Arrays.toString(actual)));
  }

  /**
   * Returns null if the arrays are equal, recursively. If not equal, returns the string of the
   * index at which they're different.
   */
  /*
   * TODO(cpovirk): Decide whether it's worthwhile to go to this trouble to display the index at
   * which the arrays differ. If we were to stop doing that, we could mostly delegate to
   * Arrays.equals() and our float/double arrayEquals methods. (We'd use deepEquals, but it doesn't
   * have our special double/float handling for GWT.)
   */
  private ComparisonResult checkArrayEqualsRecursive(
      Object expectedArray, Object actualArray, String lastIndex) {
    String expectedType = arrayType(expectedArray);
    String actualType = arrayType(actualArray);
    if (!expectedType.equals(actualType)) {
      Fact indexFact =
          lastIndex.isEmpty()
              ? factWithoutValue("wrong type")
              : fact("wrong type for index", lastIndex);
      return ComparisonResult.differentWithDescription(
          indexFact, fact("expected", expectedType), fact("but was", actualType));
    }
    int actualLength = Array.getLength(actualArray);
    int expectedLength = Array.getLength(expectedArray);
    if (expectedLength != actualLength) {
      Fact indexFact =
          lastIndex.isEmpty()
              ? factWithoutValue("wrong length")
              : fact("wrong length for index", lastIndex);
      return ComparisonResult.differentWithDescription(
          indexFact, fact("expected", expectedLength), fact("but was", actualLength));
    }
    for (int i = 0; i < actualLength || i < expectedLength; i++) {
      String index = lastIndex + "[" + i + "]";
      if (i < expectedLength && i < actualLength) {
        Object expected = Array.get(expectedArray, i);
        Object actual = Array.get(actualArray, i);
        if (actual != null
            && actual.getClass().isArray()
            && expected != null
            && expected.getClass().isArray()) {
          ComparisonResult result = checkArrayEqualsRecursive(expected, actual, index);
          if (!result.valuesAreEqual()) {
            return result;
          }
          continue;
        } else if (gwtSafeObjectEquals(actual, expected)) {
          continue;
        }
      }
      return ComparisonResult.differentWithDescription(fact("differs at index", index));
    }
    return ComparisonResult.equal();
  }

  private static String arrayType(Object array) {
    if (array.getClass() == boolean[].class) {
      return "boolean[]";
    } else if (array.getClass() == int[].class) {
      return "int[]";
    } else if (array.getClass() == long[].class) {
      return "long[]";
    } else if (array.getClass() == short[].class) {
      return "short[]";
    } else if (array.getClass() == byte[].class) {
      return "byte[]";
    } else if (array.getClass() == double[].class) {
      return "double[]";
    } else if (array.getClass() == float[].class) {
      return "float[]";
    } else if (array.getClass() == char[].class) {
      return "char[]";
    } else {
      return "Object[]";
    }
  }

  private static boolean gwtSafeObjectEquals(Object actual, Object expected) {
    if (actual instanceof Double && expected instanceof Double) {
      return Double.doubleToLongBits((Double) actual) == Double.doubleToLongBits((Double) expected);
    } else if (actual instanceof Float && expected instanceof Float) {
      return Float.floatToIntBits((Float) actual) == Float.floatToIntBits((Float) expected);
    } else {
      return Objects.equal(actual, expected);
    }
  }

  private static List<String> doubleArrayAsString(double[] items) {
    List<String> itemAsStrings = new ArrayList<>(items.length);
    for (double item : items) {
      itemAsStrings.add(doubleToString(item));
    }
    return itemAsStrings;
  }

  private static List<String> floatArrayAsString(float[] items) {
    List<String> itemAsStrings = new ArrayList<>(items.length);
    for (float item : items) {
      itemAsStrings.add(floatToString(item));
    }
    return itemAsStrings;
  }

  /**
   * Returns a builder for creating a derived subject but without providing information about how
   * the derived subject will relate to the current subject. In most cases, you should provide such
   * information by using {@linkplain #check(String, Object...) the other overload}.
   */
  protected final StandardSubjectBuilder check() {
    return new StandardSubjectBuilder(metadata.updateForCheckCall());
  }

  /**
   * Returns a builder for creating a derived subject.
   *
   * <p>Derived subjects retain the {@link FailureStrategy} and {@linkplain
   * StandardSubjectBuilder#withMessage messages} of the current subject, and in some cases, they
   * automatically supplement their failure message with information about the original subject.
   *
   * <p>For example, {@link ThrowableSubject#hasMessageThat}, which returns a {@link StringSubject},
   * is implemented with {@code check("getMessage()").that(actual().getMessage()}.
   *
   * <p>The arguments to {@code check} describe how the new subject was derived from the old,
   * formatted like a chained method call. This allows Truth to include that information in its
   * failure messages. For example, the {@code assertThat(caught).hasCauseThat().hasMessageThat()}
   * will produce a failure message that includes {@code throwable.getCause().getMessage()}, thanks
   * to {@code check} calls that supplied "getCause()" and "getMessage()" as arguments.
   *
   * <p>If the method you're delegating to accepts parameters, you can pass {@code check} a format
   * string. For example, {@link MultimapSubject#valuesForKey} calls {@code
   * check("valuesForKey(%s)", key)}.
   *
   * <p>If you aren't really delegating to an instance method on the actual value -- maybe you're
   * calling a static method, or you're calling a chain of several methods -- you can supply
   * whatever string will be most useful to users. For example, if you're delegating to {@code
   * getOnlyElement(actual().colors())}, you might call {@check "onlyColor()"}.
   *
   * @param format a template with {@code %s} placeholders
   * @param args the arguments to be inserted into those placeholders
   */
  protected final StandardSubjectBuilder check(String format, Object... args) {
    return doCheck(OldAndNewValuesAreSimilar.DIFFERENT, format, args);
  }

  // TODO(cpovirk): Figure out a public API for this.

  final StandardSubjectBuilder checkNoNeedToDisplayBothValues(String format, Object... args) {
    return doCheck(OldAndNewValuesAreSimilar.SIMILAR, format, args);
  }

  private StandardSubjectBuilder doCheck(
      OldAndNewValuesAreSimilar valuesAreSimilar, String format, Object[] args) {
    checkNotNull(format); // Probably LazyMessage itself should be this strict, but it isn't yet.
    final LazyMessage message = new LazyMessage(format, args);
    Function<String, String> descriptionUpdate =
        new Function<String, String>() {
          @Override
          public String apply(String input) {
            return input + "." + message;
          }
        };
    return new StandardSubjectBuilder(
        metadata.updateForCheckCall(valuesAreSimilar, descriptionUpdate));
  }

  /**
   * Begins a new call chain that ignores any failures. This is useful for subjects that normally
   * delegate with to other subjects by using {@link #check} but have already reported a failure. In
   * such cases it may still be necessary to return a {@code Subject} instance even though any
   * subsequent assertions are meaningless. For example, if a user chains together more {@link
   * ThrowableSubject#hasCauseThat} calls than the actual exception has causes, {@code hasCauseThat}
   * returns {@code ignoreCheck().that(... a dummy exception ...)}.
   */
  protected final StandardSubjectBuilder ignoreCheck() {
    return StandardSubjectBuilder.forCustomFailureStrategy(IGNORE_STRATEGY);
  }

  // TODO(cpovirk): Make this public once names are settled.
  final void fail(Fact first, Fact... rest) {
    doFail(sandwich(first, rest, butWas()));
  }

  /**
   * Reports a failure constructing a message from a simple verb.
   *
   * @param check the check being asserted
   */
  protected final void fail(String check) {
    fail(check, new Object[0]);
  }

  /**
   * Assembles a failure message and passes such to the FailureStrategy
   *
   * @param verb the check being asserted
   * @param other the value against which the subject is compared
   */
  protected final void fail(String verb, Object other) {
    fail(verb, new Object[] {other});
  }

  /**
   * Assembles a failure message and passes such to the FailureStrategy
   *
   * @param verb the check being asserted
   * @param messageParts the expectations against which the subject is compared
   */
  protected final void fail(String verb, Object... messageParts) {
    StringBuilder message = new StringBuilder("Not true that ");
    message.append(actualAsString()).append(" ").append(verb);
    for (Object part : messageParts) {
      message.append(" <").append(part).append(">");
    }
    metadata.fail(message.toString());
  }

  /*
   * TODO(cpovirk): Once the old `fail(String, ...)` methods are gone, rename this to `fail` and
   * make it public, too.
   */
  final void failWithFact(String key, Object value) {
    fail(fact(key, value));
  }

  private void failEqualityCheck(String verb, Object expected, ComparisonResult difference) {
    if (actual() instanceof byte[] && expected instanceof byte[]) {
      // TODO(cpovirk): Expand this to cover more types.
      failComparing(
          Joiner.on("; ").join(difference.factsOrEmpty()),
          formatActualOrExpected(expected),
          formatActualOrExpected(actual()));
      return;
    }
    StringBuilder message =
        new StringBuilder("Not true that ").append(actualAsString()).append(" ");
    // If the actual and parts aren't null, and they have equal toString()'s but different
    // classes, we need to disambiguate them.
    boolean neitherNull = (expected != null) && (actual() != null);
    boolean sameToStrings =
        actualCustomStringRepresentation().equals(formatActualOrExpected(expected));
    boolean needsClassDisambiguation =
        neitherNull && sameToStrings && !actual().getClass().equals(expected.getClass());
    if (needsClassDisambiguation) {
      message.append("(").append(actual().getClass().getName()).append(") ");
    }
    message.append(verb).append(" <").append(formatActualOrExpected(expected)).append(">");
    if (needsClassDisambiguation) {
      message.append(" (").append(expected.getClass().getName()).append(")");
    }
    if (!needsClassDisambiguation && sameToStrings) {
      message.append(" (although their toString() representations are the same)");
    }
    if (!difference.factsOrEmpty().isEmpty()) {
      message.append(". ");
      Joiner.on("; ").appendTo(message, difference.factsOrEmpty());
    }
    metadata.fail(message.toString());
  }

  /**
   * Assembles a failure message and passes it to the FailureStrategy
   *
   * @param verb the check being asserted
   * @param expected the expectations against which the subject is compared
   * @param failVerb the failure of the check being asserted
   * @param actual the actual value the subject was compared against
   */
  protected final void failWithBadResults(
      String verb, Object expected, String failVerb, Object actual) {
    String message =
        format(
            "Not true that %s %s <%s>. It %s <%s>",
            actualAsString(),
            verb,
            expected,
            failVerb,
            (actual == null) ? "null reference" : actual);
    metadata.fail(message);
  }

  /**
   * Assembles a failure message with an alternative representation of the wrapped subject and
   * passes it to the FailureStrategy
   *
   * @param verb the check being asserted
   * @param expected the expected value of the check
   * @param actual the custom representation of the subject to be reported in the failure.
   */
  protected final void failWithCustomSubject(String verb, Object expected, Object actual) {
    String message =
        format(
            "Not true that <%s> %s <%s>",
            (actual == null) ? "null reference" : actual, verb, expected);
    metadata.fail(message);
  }

  /** @deprecated Use {@link #failWithoutActual(String)} */
  @Deprecated
  protected final void failWithoutSubject(String check) {
    String strSubject = this.customName == null ? "the subject" : "\"" + customName + "\"";
    metadata.fail(format("Not true that %s %s", strSubject, check));
  }

  // TODO(cpovirk): Make this public once names are settled.
  final void failWithoutActual(Fact first, Fact... rest) {
    doFail(ImmutableList.copyOf(Lists.asList(first, rest)));
  }

  /**
   * Assembles a failure message without a given subject and passes it to the FailureStrategy
   *
   * @param check the check being asserted
   */
  protected final void failWithoutActual(String check) {
    failWithoutSubject(check);
  }

  /**
   * Passes through a failure message verbatim. Used for {@link Subject} subclasses which need to
   * provide alternate language for more fit-to-purpose error messages.
   *
   * @param message the message template to be passed to the failure. Note, this method only
   *     guarantees to process {@code %s} tokens. It is not guaranteed to be compatible with {@code
   *     String.format()}. Any other formatting desired (such as floats or scientific notation)
   *     should be performed before the method call and the formatted value passed in as a string.
   * @param parameters the object parameters which will be applied to the message template.
   */
  // TODO(cgruber) final
  protected void failWithRawMessage(String message, Object... parameters) {
    metadata.fail(format(message, parameters));
  }

  /** Passes through a failure message verbatim, along with a cause. */
  protected final void failWithRawMessageAndCause(String message, Throwable cause) {
    metadata.fail(message, cause);
  }

  /**
   * Passes through a failure message verbatim, along with the expected and actual values that the
   * {@link FailureStrategy} may use to construct a {@code ComparisonFailure}.
   */
  protected final void failComparing(String message, CharSequence expected, CharSequence actual) {
    metadata.failComparing(message, expected, actual);
  }

  /**
   * Passes through a failure message verbatim, along with a cause and the expected and actual
   * values that the {@link FailureStrategy} may use to construct a {@code ComparisonFailure}.
   */
  protected final void failComparing(
      String message, CharSequence expected, CharSequence actual, Throwable cause) {
    metadata.failComparing(message, expected, actual, cause);
  }

  /**
   * @throws UnsupportedOperationException always
   * @deprecated {@link Object#equals(Object)} is not supported on Truth subjects. If you meant to
   *     test object equality between an expected and the actual value, use {@link
   *     #isEqualTo(Object)} instead.
   */
  @Deprecated
  @Override
  public final boolean equals(@Nullable Object o) {
    throw new UnsupportedOperationException(
        "If you meant to test object equality, use .isEqualTo(other) instead.");
  }

  /**
   * @throws UnsupportedOperationException always
   * @deprecated {@link Object#hashCode()} is not supported on Truth subjects.
   */
  @Deprecated
  @Override
  public final int hashCode() {
    throw new UnsupportedOperationException("Subject.hashCode() is not supported.");
  }

  @Override
  public String toString() {
    return getClass().getName() + "(" + actualCustomStringRepresentation() + ")";
  }

  /**
   * Returns a "but was: <actual value>" string. This method should be rarely needed, since Truth
   * inserts a "but was" fact by default for assertions. However, it's occasionally useful for calls
   * to {@code failWithoutActual} that want a "but was" fact but don't want it to come last, where
   * Truth inserts it by default.
   */
  /*
   * TODO(cpovirk): Consider giving this protected access.
   *
   * It is likely better than what users would otherwise do -- `fact("but was", actual())`, which
   * ignores actualCustomStringRepresentation() (which is inaccessible outside the package).
   *
   * But I want to think more about this. In particular, if people use this to reimplement
   * isEqualTo(), I would be sad that they're missing out on its normal special handling. That's
   * probably not enough reason to avoid adding this, but we can hold it back for now.
   */
  final Fact butWas() {
    return fact("but was", actualCustomStringRepresentation());
  }

  /*
   * Computed lazily so that we're not doing expensive string operations during every assertion,
   * only during every failure.
   */
  final String typeDescription() {
    return typeDescriptionOrGuess(getClass(), typeDescriptionOverride);
  }

  @SuppressWarnings("unchecked") // unavoidable for Class<? extends Foo> AFAIK
  private static String typeDescriptionOrGuess(
      Class<? extends Subject> clazz, @Nullable String typeDescriptionOverride) {
    if (typeDescriptionOverride != null) {
      return typeDescriptionOverride;
    }
    /*
     * j2cl doesn't store enough metadata to know whether "Foo$BarSubject" is a nested class, so it
     * can't tell whether the simple name is "Foo$BarSubject" or just "BarSubject": b/71808768. It
     * returns "Foo$BarSubject" to err on the side of preserving information. We want just
     * "BarSubject," so we strip any likely enclosing type ourselves.
     */
    String subjectClass = clazz.getSimpleName().replaceFirst(".*[$]", "");
    String actualClass =
        (subjectClass.endsWith("Subject") && !subjectClass.equals("Subject"))
            ? subjectClass.substring(0, subjectClass.length() - "Subject".length())
            : "Object";
    return UPPER_CAMEL.to(LOWER_CAMEL, actualClass);
  }

  private static boolean classMetadataUnsupported() {
    // https://github.com/google/truth/issues/198
    // TODO(cpovirk): Consider whether to remove instanceof tests under GWT entirely.
    // TODO(cpovirk): Run more Truth tests under GWT, and add tests for this.
    return String.class.getSuperclass() == null;
  }

  private void doFail(ImmutableList<Fact> facts) {
    metadata.fail(prependNameIfAny(facts));
  }

  private ImmutableList<Fact> prependNameIfAny(ImmutableList<Fact> facts) {
    return concat(nameAsFacts(), facts);
  }

  private ImmutableList<Fact> nameAsFacts() {
    return customName == null
        ? ImmutableList.<Fact>of()
        : ImmutableList.of(fact("name", customName));
  }
}
