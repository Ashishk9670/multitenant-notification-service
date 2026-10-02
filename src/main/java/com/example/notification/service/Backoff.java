package com.example.notification.service;

import com.example.notification.config.NotificationProperties;
import java.time.Duration;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Exponential backoff: delay(n) = min(max, initial * multiplier^(n-1)) with +/- jitter. */
@Component
public class Backoff {

    private final Duration initial;
    private final double multiplier;
    private final Duration max;
    private final double jitter;
    private final DoubleSupplier random;

    @Autowired
    public Backoff(NotificationProperties props) {
        this(props.retry().initialDelay(), props.retry().multiplier(), props.retry().maxDelay(),
                props.retry().jitter(), Math::random);
    }

    public Backoff(Duration initial, double multiplier, Duration max, double jitter, DoubleSupplier random) {
        if (initial.isNegative() || initial.isZero()) throw new IllegalArgumentException("initial must be > 0");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier must be >= 1");
        if (jitter < 0 || jitter >= 1) throw new IllegalArgumentException("jitter must be in [0,1)");
        this.initial = initial;
        this.multiplier = multiplier;
        this.max = max;
        this.jitter = jitter;
        this.random = random;
    }

    /** @param attempt 1-based retry number */
    public Duration delayFor(int attempt) {
        if (attempt < 1) throw new IllegalArgumentException("attempt must be >= 1");
        double base = initial.toMillis() * Math.pow(multiplier, attempt - 1);
        double capped = Math.min(base, max.toMillis());
        double factor = 1.0 + jitter * (2 * random.getAsDouble() - 1); // [1-j, 1+j]
        long millis = Math.round(Math.min(capped * factor, max.toMillis()));
        return Duration.ofMillis(Math.max(0, millis));
    }
}
