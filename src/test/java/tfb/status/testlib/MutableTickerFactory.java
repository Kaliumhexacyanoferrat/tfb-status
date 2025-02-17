package tfb.status.testlib;

import com.google.common.base.Ticker;
import jakarta.inject.Singleton;
import org.glassfish.hk2.api.Rank;
import org.glassfish.hk2.extras.provides.Provides;

/**
 * Provides the {@link Ticker} used by this application during tests, which is a
 * {@link MutableTicker}.
 */
final class MutableTickerFactory {
  private MutableTickerFactory() {
    throw new AssertionError("This class cannot be instantiated");
  }

  @Provides(contracts = { Ticker.class, MutableTicker.class })
  @Singleton
  @Rank(1) // Override the default ticker.
  public static MutableTicker mutableTicker() {
    return new MutableTicker();
  }
}
