package tfb.status.testlib;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import tfb.status.bootstrap.Services;
import tfb.status.bootstrap.ServicesBinder;

/**
 * Allows instances of service classes to be injected into test methods as
 * parameters.
 *
 * <p>Example usage:
 *
 * <pre>
 *   &#64;ExtendWith(TestServicesInjector.class)
 *   public final class SomeServiceTest {
 *     &#64;Test
 *     public void testSomeBehavior(SomeService service) {
 *       // SomeService and its dependencies are initialized.
 *       // Make assertions about its behavior.
 *       ...
 *     }
 *   }
 * </pre>
 */
public final class TestServicesInjector implements ParameterResolver {
  @Override
  public boolean supportsParameter(ParameterContext parameterContext,
                                   ExtensionContext extensionContext) {

    Class<?> type = parameterContext.getParameter().getType();
    Services services = getServices(extensionContext);
    return services.hasService(type);
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext,
                                 ExtensionContext extensionContext) {

    Class<?> type = parameterContext.getParameter().getType();
    Services services = getServices(extensionContext);
    return services.getService(type);
  }

  private Services getServices(ExtensionContext extensionContext) {
    // Since we use the root context's store, a single set of services is shared
    // between all tests.
    ExtensionContext.Store store =
        extensionContext.getRoot().getStore(NAMESPACE);

    StoredServices stored =
        store.getOrComputeIfAbsent(
            /* key= */ StoredServices.class,
            /* defaultCreator= */ key -> new StoredServices(),
            /* requiredType= */ StoredServices.class);

    return stored.services;
  }

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(TestServicesInjector.class);

  /**
   * Wraps {@link Services} in {@link ExtensionContext.Store.CloseableResource},
   * ensuring that the services are shut down when the store is closed.
   */
  private static final class StoredServices
      implements ExtensionContext.Store.CloseableResource {

    final Services services =
        new Services(new ServicesBinder("test_config.yml"),
                     new TestServicesBinder());

    @Override
    public void close() {
      services.shutdown();
    }
  }
}