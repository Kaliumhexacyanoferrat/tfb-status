package tfb.status.hk2.extensions;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility methods for working with {@link Type}.
 */
final class TypeUtils {
  private TypeUtils() {
    throw new AssertionError("This class cannot be instantiated");
  }

  /**
   * Returns {@code true} if the specific type contains any type variables.
   */
  static boolean containsTypeVariable(Type type) {
    Objects.requireNonNull(type);
    return new TypeVariableDetector().matches(type);
  }

  /**
   * Resolves type variables in {@code dependentType} using the type parameters
   * from {@code contextType}.
   *
   * <p>Example usage:
   *
   * <pre>
   *   class Config {
   *     public Optional&lt;String&gt; value() { ... }
   *   }
   *
   *   Type contextType = Config.class.getMethod("value").getGenericReturnType();
   *   //   ^ Optional&lt;String&gt;
   *
   *   Type dependentType = Optional.class.getMethod("get").getGenericReturnType();
   *   //   ^ T
   *
   *   Type resolvedType = resolveType(contextType, dependentType);
   *   //   ^ String.class
   * </pre>
   */
  static Type resolveType(Type contextType, Type dependentType) {
    Objects.requireNonNull(contextType);
    Objects.requireNonNull(dependentType);
    TypeVariableMappings mappings = TypeVariableMappings.of(contextType);
    TypeVariableResolver resolver = new TypeVariableResolver(mappings);
    return resolver.resolve(dependentType);
  }

  /**
   * Returns a type representing an array with the specified component type.
   */
  private static Type newArrayType(Type componentType) {
    Objects.requireNonNull(componentType);
    return (componentType instanceof Class<?>)
        ? Array.newInstance(((Class<?>) componentType), 0).getClass()
        : new GenericArrayTypeImpl(componentType);
  }

  /**
   * Inspects types to determine if they contain any type variables.
   */
  private static final class TypeVariableDetector {
    private final Set<Type> seen = new HashSet<>();

    /**
     * Returns {@code true} if the specified type contains any type variables.
     */
    boolean matches(Type type) {
      Objects.requireNonNull(type);

      if (!seen.add(type))
        return false;

      else if (type instanceof TypeVariable)
        return true;

      else if (type instanceof Class)
        return false;

      else if (type instanceof WildcardType) {
        WildcardType wildcardType = (WildcardType) type;

        for (Type lowerBound : wildcardType.getLowerBounds())
          if (matches(lowerBound))
            return true;

        for (Type upperBound : wildcardType.getUpperBounds())
          if (matches(upperBound))
            return true;

        return false;
      }

      else if (type instanceof ParameterizedType) {
        ParameterizedType parameterizedType = (ParameterizedType) type;

        Type ownerType = parameterizedType.getOwnerType();
        if (ownerType != null && matches(parameterizedType.getOwnerType()))
          return true;

        for (Type argument : parameterizedType.getActualTypeArguments())
          if (matches(argument))
            return true;

        return false;
      }

      else if (type instanceof GenericArrayType) {
        GenericArrayType genericArrayType = (GenericArrayType) type;
        return matches(genericArrayType.getGenericComponentType());
      }

      return false;
    }
  }

  /**
   * Transforms "?" wildcard types into unique "capture#N of ?" type variables.
   *
   * <p>The implementation of {@link #resolveType(Type, Type)} uses this class
   * to prevent incorrect subtype relationships between generic types when
   * wildcards are involved.  The following code illustrates how the correctness
   * of that method depends on this class.
   *
   * <pre>
   *   class Box&lt;Q&gt; {
   *     Q value;
   *     public void set(Q value) { this.value = value; }
   *   }
   *
   *   Type boxType = new TypeLiteral&lt;Box&lt;Optional&lt;?&gt;&gt;&gt;() {}.getType();
   *   //   ^ Box&lt;Optional&lt;?&gt;&gt;
   *
   *   Type parameterType = Box.class.getMethod("set", Object.class)
   *                                 .getGenericParameterTypes()[0];
   *   //   ^ Q
   *
   *   Type resolvedType = resolveType(boxType, parameterType);
   *   //   ^ Optional&lt;capture#1 of ?&gt;
   *
   *   Type argumentType = new TypeLiteral&lt;Optional&lt;String&gt;&gt;() {}.getType();
   *   //   ^ Optional&lt;String&gt;
   *
   *   boolean isArgumentSafe = TypeChecker.isRawTypeSafe(resolvedType,
   *                                                      argumentType);
   *   //      ^ false
   *   //      This is the correct answer.
   *   //      A caller cannot set the value of the box to an
   *   //      Optional&lt;String&gt; because that does not conform to the box's
   *   //      type.
   *
   *   Type wrongType = new TypeLiteral&lt;Optional&lt;?&gt;&gt;() {}.getType();
   *   //   ^ Optional&lt;?&gt;
   *   //   This is what resolveType(...) would produce if not for this class.
   *
   *   boolean isArgumentSafe2 = TypeChecker.isRawTypeSafe(wrongType,
   *                                                       argumentType);
   *   //      ^ true
   *   //      This is the wrong answer.
   * </pre>
   */
  private static class WildcardTransformer {
    static final WildcardTransformer INSTANCE = new WildcardTransformer();
    private static final AtomicInteger CAPTURE_NUMBER = new AtomicInteger();

    /**
     * Transforms "?" wildcard types contained in the specified type into unique
     * "capture#N of ?" type variables.
     */
    Type transform(Type type) {
      Objects.requireNonNull(type);

      if (type instanceof TypeVariable<?>)
        return type;

      else if (type instanceof Class<?>)
        return type;

      else if (type instanceof WildcardType) {
        WildcardType wildcardType = (WildcardType) type;
        return (wildcardType.getLowerBounds().length > 0)
            ? type
            : newWildcardCapture(wildcardType.getUpperBounds());
      }

      else if (type instanceof ParameterizedType) {
        ParameterizedType parameterizedType = (ParameterizedType) type;
        Class<?> rawType = (Class<?>) parameterizedType.getRawType();
        Type ownerType = parameterizedType.getOwnerType();
        TypeVariable<?>[] typeParameters = rawType.getTypeParameters();
        Type[] typeArguments = parameterizedType.getActualTypeArguments();

        Type[] transformedTypeArguments = new Type[typeArguments.length];
        Arrays.setAll(
            transformedTypeArguments,
            i -> withBounds(typeParameters[i]).transform(typeArguments[i]));

        return new ParameterizedTypeImpl(
            (ownerType == null) ? null : INSTANCE.transform(ownerType),
            rawType,
            transformedTypeArguments);
      }

      else if (type instanceof GenericArrayType) {
        GenericArrayType arrayType = (GenericArrayType) type;
        Type componentType = arrayType.getGenericComponentType();
        return newArrayType(INSTANCE.transform(componentType));
      }

      else
        return type;
    }

    /**
     * Constructs a new "capture#N of ?" type variable with the specified
     * bounds.
     */
    protected TypeVariable<?> newWildcardCapture(Type[] bounds) {
      Objects.requireNonNull(bounds);

      StringJoiner joiner =
          new StringJoiner(
              " & ",
              "capture#" + CAPTURE_NUMBER.incrementAndGet() + " of ? extends ",
              "");

      for (Type bound : bounds)
        joiner.add(
            (bound instanceof Class<?>)
                ? ((Class<?>) bound).getName()
                : bound.toString());

      return new TypeVariableImpl<>(
          bounds,
          WildcardTransformer.class,
          /* name= */ joiner.toString());
    }

    /**
     * Returns a {@link WildcardTransformer} that includes the additional bounds
     * defined by the specified type variable in its {@linkplain
     * #newWildcardCapture(Type[]) wildcard captures}.
     */
    private WildcardTransformer withBounds(TypeVariable<?> typeVariable) {
      Objects.requireNonNull(typeVariable);

      return new WildcardTransformer() {
        @Override
        protected TypeVariable<?> newWildcardCapture(Type[] bounds) {
          Set<Type> combinedBounds = new LinkedHashSet<>();
          Collections.addAll(combinedBounds, bounds);
          Collections.addAll(combinedBounds, typeVariable.getBounds());

          if (combinedBounds.size() > 1)
            combinedBounds.remove(Object.class);

          return super.newWildcardCapture(
              combinedBounds.toArray(new Type[0]));
        }
      };
    }
  }

  /**
   * Maps type variables to actual type arguments.
   */
  private static final class TypeVariableMappings {
    private final Set<Type> seen = new HashSet<>();
    private final Map<TypeVariable<?>, Type> map = new HashMap<>();

    /**
     * Returns the type variable mappings defined by the specified type.
     */
    static TypeVariableMappings of(Type contextType) {
      Objects.requireNonNull(contextType);

      Type invariantContextType =
          WildcardTransformer.INSTANCE.transform(contextType);

      TypeVariableMappings mappings = new TypeVariableMappings();
      mappings.add(invariantContextType);
      return mappings;
    }

    /**
     * If the specified type is a type variable, returns the type to which that
     * type variable is mapped.  If the argument is not a type variable or it is
     * not mapped to any other type, then the argument is returned.
     */
    Type get(Type type) {
      Objects.requireNonNull(type);
      while (type instanceof TypeVariable<?> && map.containsKey(type))
        type = map.get(type);
      return type;
    }

    /**
     * Modifies this instance to include the type variable mappings from the
     * specified type.
     */
    private void add(Type type) {
      Objects.requireNonNull(type);

      if (!seen.add(type))
        return;

      if (type instanceof TypeVariable) {
        TypeVariable<?> typeVariable = ((TypeVariable<?>) type);
        add(typeVariable.getBounds());
      }

      else if (type instanceof Class) {
        Class<?> clazz = (Class<?>) type;
        Type superclass = clazz.getGenericSuperclass();
        if (superclass != null)
          add(superclass);

        add(clazz.getGenericInterfaces());
      }

      else if (type instanceof WildcardType) {
        WildcardType wildcardType = (WildcardType) type;
        add(wildcardType.getUpperBounds());
      }

      else if (type instanceof ParameterizedType) {
        ParameterizedType parameterizedType = (ParameterizedType) type;
        Class<?> rawType = (Class<?>) parameterizedType.getRawType();
        TypeVariable<?>[] typeParameters = rawType.getTypeParameters();
        Type[] typeArguments = parameterizedType.getActualTypeArguments();
        Type ownerType = parameterizedType.getOwnerType();

        for (int i = 0; i < typeParameters.length; i++)
          map.putIfAbsent(typeParameters[i], typeArguments[i]);

        add(rawType);

        if (ownerType != null)
          add(ownerType);
      }

      else if (type instanceof GenericArrayType) {
        GenericArrayType arrayType = (GenericArrayType) type;
        Type componentType = arrayType.getGenericComponentType();
        add(componentType);
      }
    }

    /**
     * Modifies this instance to include the type variable mappings from all of
     * the specified types.
     */
    private void add(Type[] types) {
      Objects.requireNonNull(types);
      for (Type type : types)
        add(type);
    }
  }

  /**
   * Resolves type variables to actual type arguments.
   */
  private static final class TypeVariableResolver {
    private final TypeVariableMappings mappings;

    TypeVariableResolver(TypeVariableMappings mappings) {
      this.mappings = Objects.requireNonNull(mappings);
    }

    /**
     * Returns the result of resolving all the type variables in the specified
     * type to actual type arguments.
     */
    Type resolve(Type type) {
      Objects.requireNonNull(type);

      if (type instanceof TypeVariable<?>)
        return mappings.get(type);

      else if (type instanceof Class<?>)
        return type;

      else if (type instanceof WildcardType) {
        WildcardType wildcardType = (WildcardType) type;
        Type[] lowerBounds = wildcardType.getLowerBounds();
        Type[] upperBounds = wildcardType.getUpperBounds();
        return new WildcardTypeImpl(
            resolve(lowerBounds),
            resolve(upperBounds));
      }

      else if (type instanceof ParameterizedType) {
        ParameterizedType parameterizedType = (ParameterizedType) type;
        Class<?> rawType = (Class<?>) parameterizedType.getRawType();
        Type[] typeArguments = parameterizedType.getActualTypeArguments();
        Type ownerType = parameterizedType.getOwnerType();
        return new ParameterizedTypeImpl(
            (ownerType == null) ? null : resolve(ownerType),
            rawType,
            resolve(typeArguments));
      }

      else if (type instanceof GenericArrayType) {
        GenericArrayType arrayType = (GenericArrayType) type;
        Type componentType = arrayType.getGenericComponentType();
        return newArrayType(resolve(componentType));
      }

      else
        return type;
    }

    /**
     * Returns the result of resolving all the type variables in the specified
     * types to actual type arguments.
     */
    Type[] resolve(Type[] types) {
      Type[] resolved = new Type[types.length];
      Arrays.setAll(resolved, i -> resolve(types[i]));
      return resolved;
    }
  }

  /**
   * An implementation of {@link TypeVariable} that does not support
   * annotations.
   */
  private static final class TypeVariableImpl<D extends GenericDeclaration>
      implements TypeVariable<D> {

    private final Type[] bounds;
    private final D genericDeclaration;
    private final String name;

    private TypeVariableImpl(Type[] bounds, D genericDeclaration, String name) {
      this.bounds = Objects.requireNonNull(bounds);
      this.genericDeclaration = Objects.requireNonNull(genericDeclaration);
      this.name = Objects.requireNonNull(name);
    }

    @Override
    public Type[] getBounds() {
      return bounds.clone();
    }

    @Override
    public D getGenericDeclaration() {
      return genericDeclaration;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public <T extends Annotation> /*@Nullable*/ T getAnnotation(Class<T> annotationClass) {
      Objects.requireNonNull(annotationClass);
      return null;
    }

    @Override
    public Annotation[] getAnnotations() {
      return new Annotation[0];
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
      return new Annotation[0];
    }

    @Override
    public AnnotatedType[] getAnnotatedBounds() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(/*@Nullable*/ Object object) {
      // Require the same class type for equality, and do not equate to other
      // implementations of the interface.  The JDK's implementation does this,
      // meaning that we must do the same in order to preserve symmetry.
      if (object == this) {
        return true;
      } else if (!(object instanceof TypeVariableImpl)) {
        return false;
      } else {
        TypeVariableImpl<?> that = (TypeVariableImpl<?>) object;
        return this.genericDeclaration.equals(that.genericDeclaration)
            && this.name.equals(that.name);
      }
    }

    @Override
    public int hashCode() {
      return genericDeclaration.hashCode() ^ name.hashCode();
    }

    @Override
    public String toString() {
      return getName();
    }
  }

  /**
   * An implementation of {@link WildcardType}.
   */
  private static final class WildcardTypeImpl implements WildcardType {
    private final Type[] lowerBounds;
    private final Type[] upperBounds;

    WildcardTypeImpl(Type[] lowerBounds, Type[] upperBounds) {
      this.lowerBounds = Objects.requireNonNull(lowerBounds);
      this.upperBounds = Objects.requireNonNull(upperBounds);
    }

    @Override
    public Type[] getLowerBounds() {
      return lowerBounds.clone();
    }

    @Override
    public Type[] getUpperBounds() {
      return upperBounds.clone();
    }

    @Override
    public boolean equals(/*@Nullable*/ Object object) {
      if (object == this) {
        return true;
      } else if (!(object instanceof WildcardType)) {
        return false;
      } else {
        WildcardType that = (WildcardType) object;
        return Arrays.equals(this.lowerBounds, that.getLowerBounds())
            && Arrays.equals(this.upperBounds, that.getUpperBounds());
      }
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(lowerBounds) ^ Arrays.hashCode(upperBounds);
    }

    @Override
    public String toString() {
      if (lowerBounds.length > 0) {
        StringJoiner joiner = new StringJoiner(" & ", "? super ", "");
        for (Type bound : lowerBounds)
          joiner.add(
              (bound instanceof Class<?>)
                  ? ((Class<?>) bound).getName()
                  : bound.toString());
        return joiner.toString();
      }

      if (upperBounds.length == 0 || upperBounds[0] == Object.class)
        return "?";

      StringJoiner joiner = new StringJoiner(" & ", "? extends ", "");
      for (Type bound : upperBounds)
        joiner.add(
            (bound instanceof Class<?>)
                ? ((Class<?>) bound).getName()
                : bound.toString());
      return joiner.toString();
    }
  }

  /**
   * An implementation of {@link ParameterizedType}.
   */
  private static final class ParameterizedTypeImpl implements ParameterizedType {
    private final /*@Nullable*/ Type ownerType;
    private final Class<?> rawType;
    private final Type[] actualTypeArguments;

    ParameterizedTypeImpl(/*@Nullable*/ Type ownerType,
                          Class<?> rawType,
                          Type[] actualTypeArguments) {

      this.ownerType = ownerType;
      this.rawType = Objects.requireNonNull(rawType);
      this.actualTypeArguments = Objects.requireNonNull(actualTypeArguments);
    }

    @Override
    public /*@Nullable*/ Type getOwnerType() {
      return ownerType;
    }

    @Override
    public Type getRawType() {
      return rawType;
    }

    @Override
    public Type[] getActualTypeArguments() {
      return actualTypeArguments.clone();
    }

    @Override
    public boolean equals(/*@Nullable*/ Object object) {
      if (object == this) {
        return true;
      } else if (!(object instanceof ParameterizedType)) {
        return false;
      } else {
        ParameterizedType that = (ParameterizedType) object;
        return this.rawType.equals(that.getRawType())
            && Objects.equals(this.ownerType, that.getOwnerType())
            && Arrays.equals(this.actualTypeArguments, that.getActualTypeArguments());
      }
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(actualTypeArguments)
          ^ Objects.hashCode(ownerType)
          ^ rawType.hashCode();
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      if (ownerType != null) {
        sb.append(ownerType.getTypeName());
        sb.append(".");
      }
      sb.append(rawType.getName());
      if (actualTypeArguments.length == 0)
        return sb.toString();
      StringJoiner joiner = new StringJoiner(", ", "<", ">");
      for (Type typeArgument : actualTypeArguments)
        joiner.add(typeArgument.getTypeName());
      sb.append(joiner.toString());
      return sb.toString();
    }
  }

  /**
   * An implementation of {@link GenericArrayType}.
   */
  private static final class GenericArrayTypeImpl implements GenericArrayType {
    private final Type genericComponentType;

    GenericArrayTypeImpl(Type genericComponentType) {
      this.genericComponentType = Objects.requireNonNull(genericComponentType);
    }

    @Override
    public Type getGenericComponentType() {
      return genericComponentType;
    }

    @Override
    public boolean equals(/*@Nullable*/ Object object) {
      if (object == this) {
        return true;
      } else if (!(object instanceof GenericArrayType)) {
        return false;
      } else {
        GenericArrayType that = (GenericArrayType) object;
        return this.genericComponentType.equals(that.getGenericComponentType());
      }
    }

    @Override
    public int hashCode() {
      return genericComponentType.hashCode();
    }

    @Override
    public String toString() {
      return genericComponentType.getTypeName() + "[]";
    }
  }
}
