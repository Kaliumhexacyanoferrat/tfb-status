package tfb.status.hk2.extensions;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.inject.Named;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.DescriptorType;
import org.glassfish.hk2.api.DescriptorVisibility;
import org.glassfish.hk2.api.HK2Loader;
import org.glassfish.hk2.api.Injectee;
import org.glassfish.hk2.api.ProxyForSameScope;
import org.glassfish.hk2.api.Rank;
import org.glassfish.hk2.api.ServiceHandle;
import org.glassfish.hk2.api.UseProxy;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.jvnet.hk2.internal.Collector;
import org.jvnet.hk2.internal.Utilities;

/**
 * An {@link ActiveDescriptor} for a service whose annotations come from some
 * {@link AnnotatedElement}, such as a method, field, or class.
 */
final class ProvidesDescriptor<T> implements ActiveDescriptor<T> {
  private final AnnotatedElement annotatedElement;
  private final Type implementationType;
  private final ImmutableSet<Type> contracts;
  private final Annotation scope;
  private final Function<ServiceHandle<?>, T> createFunction;
  private final Consumer<T> disposeFunction;

  ProvidesDescriptor(AnnotatedElement annotatedElement,
                     Type implementationType,
                     ImmutableSet<Type> contracts,
                     Annotation scope,
                     Function<ServiceHandle<?>, T> createFunction,
                     Consumer<T> disposeFunction) {

    this.annotatedElement = Objects.requireNonNull(annotatedElement);
    this.implementationType = Objects.requireNonNull(implementationType);
    this.contracts = Objects.requireNonNull(contracts);
    this.scope = Objects.requireNonNull(scope);
    this.createFunction = Objects.requireNonNull(createFunction);
    this.disposeFunction = Objects.requireNonNull(disposeFunction);
  }

  @Override
  public T create(ServiceHandle<?> root) {
    return createFunction.apply(root);
  }

  @Override
  public void dispose(@Nullable T instance) {
    if (instance != null)
      disposeFunction.accept(instance);
  }

  @Override
  public Class<?> getImplementationClass() {
    return TypeToken.of(implementationType).getRawType();
  }

  @Override
  public Type getImplementationType() {
    return implementationType;
  }

  @Override
  public Set<Type> getContractTypes() {
    return contracts;
  }

  @Override
  public boolean isReified() {
    return true;
  }

  @Override
  public Annotation getScopeAsAnnotation() {
    return scope;
  }

  @Override
  public Class<? extends Annotation> getScopeAnnotation() {
    return getScopeAsAnnotation().annotationType();
  }

  @Override
  public Set<Annotation> getQualifierAnnotations() {
    Collector collector = new Collector();
    Set<Annotation> qualifiers =
        Utilities.getAllQualifiers(
            annotatedElement,
            getName(),
            collector);
    collector.throwIfErrors();
    return ImmutableSet.copyOf(qualifiers);
  }

  @Override
  public List<Injectee> getInjectees() {
    return ImmutableList.of();
  }

  @Override
  public @Nullable Long getFactoryServiceId() {
    return null;
  }

  @Override
  public @Nullable Long getFactoryLocatorId() {
    return null;
  }

  @Override
  public @Nullable String getImplementation() {
    return getImplementationClass().getName();
  }

  @Override
  public Set<String> getAdvertisedContracts() {
    return getContractTypes()
        .stream()
        .map(contract -> TypeToken.of(contract))
        .map(contract -> contract.getRawType())
        .map(contract -> contract.getName())
        .collect(toImmutableSet());
  }

  @Override
  public String getScope() {
    return getScopeAnnotation().getName();
  }

  @Override
  public @Nullable String getName() {
    return Arrays.stream(annotatedElement.getAnnotations())
                 .filter(annotation -> annotation.annotationType() == Named.class)
                 .map(annotation -> ((Named) annotation))
                 .map(annotation -> annotation.value())
                 .findAny()
                 .orElse(null);
  }

  @Override
  public Set<String> getQualifiers() {
    return getQualifierAnnotations()
        .stream()
        .map(annotation -> annotation.annotationType())
        .map(annotationType -> annotationType.getName())
        .collect(toImmutableSet());
  }

  @Override
  public DescriptorType getDescriptorType() {
    return DescriptorType.CLASS;
  }

  @Override
  public DescriptorVisibility getDescriptorVisibility() {
    return DescriptorVisibility.NORMAL;
  }

  @Override
  public Map<String, List<String>> getMetadata() {
    Map<String, List<String>> metadata = new HashMap<>();

    Annotation scope = getScopeAsAnnotation();
    BuilderHelper.getMetadataValues(scope, metadata);

    for (Annotation qualifier : getQualifierAnnotations())
      BuilderHelper.getMetadataValues(qualifier, metadata);

    return ImmutableMap.copyOf(metadata);
  }

  @Override
  public @Nullable HK2Loader getLoader() {
    return null;
  }

  @GuardedBy("this")
  private int ranking = 0;

  @GuardedBy("this")
  private boolean initialRankingFound = false;

  @Override
  public synchronized int getRanking() {
    if (!initialRankingFound) {
      Rank rank = annotatedElement.getAnnotation(Rank.class);
      if (rank != null)
        ranking = rank.value();

      initialRankingFound = true;
    }

    return ranking;
  }

  @Override
  public synchronized int setRanking(int ranking) {
    int previousRanking = getRanking();
    this.ranking = ranking;
    return previousRanking;
  }

  @Override
  public @Nullable Boolean isProxiable() {
    UseProxy useProxy = annotatedElement.getAnnotation(UseProxy.class);
    return (useProxy == null) ? null : useProxy.value();
  }

  @Override
  public @Nullable Boolean isProxyForSameScope() {
    ProxyForSameScope proxyForSameScope =
        annotatedElement.getAnnotation(ProxyForSameScope.class);

    return (proxyForSameScope == null) ? null : proxyForSameScope.value();
  }

  @Override
  public @Nullable String getClassAnalysisName() {
    return null;
  }

  @Override
  public @Nullable Long getServiceId() {
    return null;
  }

  @Override
  public @Nullable Long getLocatorId() {
    return null;
  }

  @GuardedBy("this")
  private @Nullable T cache = null;

  @GuardedBy("this")
  private boolean isCacheSet = false;

  @Override
  public synchronized @Nullable T getCache() {
    if (!isCacheSet)
      throw new IllegalStateException();

    return cache;
  }

  @Override
  public synchronized boolean isCacheSet() {
    return isCacheSet;
  }

  @Override
  public synchronized void setCache(T cacheMe) {
    cache = cacheMe;
    isCacheSet = true;
  }

  @Override
  public synchronized void releaseCache() {
    cache = null;
    isCacheSet = false;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[" + annotatedElement + "]";
  }
}