package org.wikidata.query.rdf.blazegraph.throttling;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Instant.now;
import static java.time.format.DateTimeFormatter.ISO_INSTANT;
import static java.util.Locale.ENGLISH;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.isomorphism.util.TokenBuckets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.query.rdf.blazegraph.filters.FilterConfiguration;
import org.wikidata.query.rdf.blazegraph.filters.MonitoredFilter;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * A Servlet Filter that applies throttling.
 *
 * The throttling is based on the request time consumed and the number of
 * errors. The rational is:
 *
 * <dl>
 *     <dt>request time</dt>
 *     <dd>This is a good proxy for how much resource (CPU, IO) are consumed.</dd>
 *     <dt>errors</dt>
 *     <dd>A client always in error indicates a problem client side, which
 *     should be fixed client side.</dd>
 * </dl>
 *
 * Resource consumption is based on <a
 * href="https://en.wikipedia.org/wiki/Token_bucket">token buckets</a> as
 * implemented by <a href="https://github.com/bbeck/token-bucket">bbeck</a>. A
 * token bucket is defined by:
 *
 * <dl>
 *     <dt>capacity</dt>
 *     <dd>the maximum number of tokes in the bucket</dd>
 *     <dt>refill amount</dt>
 *     <dd>the number of tokens to add to the bucket when refilling</dd>
 *     <dt>refill period</dt>
 *     <dd>how often to refill the bucket</dd>
 * </dl>
 *
 * This filter has two buckets, one to keep track of time, and one to keep
 * track of errors. Each time an error occurs, a token is taken out of the
 * error bucket. Each refill period, tokens are added again. The time bucket
 * has a similar behaviour. As an optimization, we start keeping track of
 * resource consumption only if:
 *
 * <ol>
 *     <li>a request is taking a significant time</li>
 *     <li>a request is in error</li>
 * </ol>
 *
 * The client is throttled if either the time bucket or the error bucket is
 * empty. Since we don't know in advance the cost of a request or if it is
 * going to be in error, the throttling will only occur for the next requests.
 *
 * In case of throttling, the client is notified by an HTTP 429 status code and
 * is presented with a <code>Retry-After</code> HTTP header giving a backoff
 * time in seconds.
 *
 * Further more, if a client does not back off when being sent HTTP 429, and it
 * looks abusive, it is completely banned for a period of time, and will
 * receive HTTP 403 (Forbidden) during the duration of the ban.
 *
 * The clients are segmented in different buckets and resource consumption is
 * tracked individually for each of those buckets. The segmentation is done by
 * [IP address, User Agent], but could be extended to support more complex
 * strategies. A bucket is only kept while its client is active. After a period
 * of inactivity, the bucket is deleted.
 *
 * All state is limited to a single JVM, this filter is not cluster aware.
 */
@SuppressWarnings("checkstyle:classfanoutcomplexity")
public class ThrottlingFilter extends MonitoredFilter implements Filter, ThrottlingMXBean {

    private static final Logger LOG = LoggerFactory.getLogger(ThrottlingFilter.class);

    /** Is throttling enabled. */
    private boolean enabled;

    /** Mapping of requests to bucket. */
    private Bucketing userAgentIpBucketing;
    /** Requests by regexp.
     * This bucketing system groups all requests that match certain query pattern.
     */
    private Bucketing regexBucketing;
    /** Requests by generic agent.
     * This bucketing scheme maps all agents that use a generic user agent into this agent's bucket.
     */
    private Bucketing agentBucketing;

    /** To delegate throttling logic. */
    private TimeAndErrorsThrottler<ThrottlingState> timeAndErrorsThrottler;

    /** To delegate banning logic. */
    private BanThrottler<ThrottlingState> banThrottler;

    /** Keeps track of the number of requests that have been throttled. */
    private final LongAdder nbThrottledRequests = new LongAdder();

    /** Keeps track of the number of requests that have been banned. */
    private final LongAdder nbBannedRequests = new LongAdder();

    private Cache<Object, ThrottlingState> stateStore;

    /**
     * Initialise the filter.
     *
     * The following parameters are available (see
     * {@link org.isomorphism.util.TokenBucket} for the details on bucket
     * configuration, see implementation for the default values):
     * <ul>
     *     <li>{@code request-duration-threshold-in-millis}: requests longer
     *     than this threshold will start the tracking for this user</li>
     *
     *     <li>{@code time-bucket-capacity-in-seconds},
     *     {@code time-bucket-refill-amount-in-seconds},
     *     {@code time-bucket-refill-period-in-minutes}: configuration of the
     *     bucket tracking request durations</li>
     *
     *     <li>{@code error-bucket-capacity},
     *     {@code error-bucket-refill-amount},
     *     {@code error-bucket-refill-period-in-minutes}: configuration of the
     *     bucket tracking errors</li>
     *
     *     <li>{@code throttle-bucket-capacity},
     *     {@code throttle-bucket-refill-amount},
     *     {@code throttle-bucket-refill-period-in-minutes}: configuration of
     *     the bucket tracking throttling</li>
     *
     *     <li>{@code ban-duration-in-minutes}: how long should a user be
     *     banned when a ban is triggered</li>
     *
     *     <li>{@code max-state-size}: how many users to track</li>
     *     <li>{@code state-expiration-in-minutes}: tracking of a user expires
     *     after this duration</li>
     *
     *     <li>{@code enable-throttling-if-header}: enable the throttling on
     *     the requests which have this header set</li>
     *     <li>{@code enable-ban-if-header}: enable the banning on the requests
     *     which have this header set</li>
     *     <li>{@code always-throttle-param}: always throttle requests where
     *     this parameter is set (useful for testing)</li>
     *     <li>{@code always-ban-param}: always ban requests where this
     *     parameter is set (useful for testing)</li>
     *
     *     <li>{@code enabled}: entirely disable this filter if set to
     *     false</li>
     * </ul>
     *
     * See {@link FilterConfiguration#loadStringParam(String)} for
     * the details of where the configuration is loaded from.
     *
     * @param filterConfig {@inheritDoc}
     */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        super.init(filterConfig);
        ThrottlingFilterConfig config = new ThrottlingFilterConfig(new FilterConfiguration(filterConfig, FilterConfiguration.WDQS_CONFIG_PREFIX));

        this.enabled = config.isFilterEnabled();

        this.userAgentIpBucketing = new UserAgentIpAddressBucketing();

        this.regexBucketing = new RegexpBucketing(
                loadRegexPatterns(config.getRegexPatternsFile()),
                r -> r.getParameter("query")
        );
        this.agentBucketing = new RegexpBucketing(
                loadRegexPatterns(config.getAgentPatternsFile()),
                r -> r.getHeader("User-Agent")
        );

        stateStore = CacheBuilder.newBuilder()
                .maximumSize(config.getMaxStateSize())
                .expireAfterAccess(config.getStateExpiration().toMillis(), MILLISECONDS)
                .build();

        Callable<ThrottlingState> stateInitializer = createThrottlingState(
                config.getTimeBucketCapacity(),
                config.getTimeBucketRefillAmount(),
                config.getTimeBucketRefillPeriod(),
                config.getErrorBucketCapacity(),
                config.getErrorBucketRefillAmount(),
                config.getErrorBucketRefillPeriod(),
                config.getThrottleBucketCapacity(),
                config.getThrottleBucketRefillAmount(),
                config.getThrottleBucketRefillPeriod(),
                config.getBanDuration());

        timeAndErrorsThrottler = new TimeAndErrorsThrottler<>(
                config.getRequestDurationThreshold(),
                stateInitializer,
                stateStore,
                config.getEnableThrottlingIfHeader(),
                config.getAlwaysThrottleParam(),
                Clock.systemUTC());

        banThrottler = new BanThrottler<>(
                stateInitializer,
                stateStore,
                config.getEnableBanIfHeader(),
                config.getAlwaysBanParam(),
                Clock.systemUTC());
    }

    /**
     * Create Callable to initialize throttling state.
     */
    private static Callable<ThrottlingState> createThrottlingState(
            Duration timeBucketCapacity,
            Duration timeBucketRefillAmount,
            Duration timeBucketRefillPeriod,
            int errorBucketCapacity,
            int errorBucketRefillAmount,
            Duration errorBucketRefillPeriod,
            int throttleBucketCapacity,
            int throttleBucketRefillAmount,
            Duration throttleBucketRefillPeriod,
            Duration banDuration) {
        return () -> new ThrottlingState(
                TokenBuckets.builder()
                        .withCapacity(timeBucketCapacity.toMillis())
                        .withFixedIntervalRefillStrategy(
                                timeBucketRefillAmount.toMillis(),
                                timeBucketRefillPeriod.toMillis(), MILLISECONDS)
                        .build(),
                TokenBuckets.builder()
                        .withCapacity(errorBucketCapacity)
                        .withFixedIntervalRefillStrategy(
                                errorBucketRefillAmount,
                                errorBucketRefillPeriod.toMillis(), MILLISECONDS)
                        .build(),
                TokenBuckets.builder()
                        .withCapacity(throttleBucketCapacity)
                        .withFixedIntervalRefillStrategy(
                                throttleBucketRefillAmount,
                                throttleBucketRefillPeriod.toMillis(), MILLISECONDS)
                        .build(),
                banDuration);
    }

    /**
     * Check resource consumption and throttle requests as needed.
     *
     * @param request {@inheritDoc}
     * @param response {@inheritDoc}
     * @param chain {@inheritDoc}
     * @throws IOException {@inheritDoc}
     * @throws ServletException {@inheritDoc}
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        Object bucket = regexBucketing.bucket(httpRequest);
        if (bucket == null) {
            bucket = agentBucketing.bucket(httpRequest);
        }
        if (bucket == null) {
            bucket = userAgentIpBucketing.bucket(httpRequest);
        }

        Instant bannedUntil = banThrottler.throttledUntil(bucket, httpRequest);
        if (bannedUntil.isAfter(now())) {
            LOG.info("A request is being banned.");
            if (enabled) {
                nbBannedRequests.increment();
                notifyUserBanned(httpResponse, bannedUntil);
                return;
            }
        }

        Duration throttledDuration = timeAndErrorsThrottler.throttledDuration(bucket, httpRequest);
        if (!throttledDuration.isNegative()) {
            LOG.info("A request is being throttled.");
            if (enabled) {
                nbThrottledRequests.increment();
                notifyUserThrottled(httpResponse, throttledDuration);
                banThrottler.throttled(bucket, httpRequest);
                return;
            }
        }

        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            chain.doFilter(request, response);
            // for throttling purpose, consider all 1xx and 2xx status codes as
            // success, 4xx and 5xx as failure
            if (httpResponse.getStatus() < 400) {
                timeAndErrorsThrottler.success(bucket, httpRequest, stopwatch.elapsed());
            } else {
                timeAndErrorsThrottler.failure(bucket, httpRequest, stopwatch.elapsed());
            }
        } catch (IOException | ServletException e) {
            // an exception processing the request is treated as a failure
            timeAndErrorsThrottler.failure(bucket, httpRequest, stopwatch.elapsed());
            throw e;
        }
    }

    /**
     * Notify the user that he is being banned.
     *
     * @param response the response
     * @param bannedUntil time until which the user is banned
     * @throws IOException if the response cannot be written
     */
    @SuppressFBWarnings("XSS_SERVLET") // inputs to sendError() are all trusted
    private void notifyUserBanned(HttpServletResponse response, Instant bannedUntil) throws IOException {
        response.sendError(403, formattedBanMessage(bannedUntil));
    }

    @VisibleForTesting
    static String formattedBanMessage(Instant bannedUntil) {
        String banEndTime = ISO_INSTANT.format(bannedUntil);
        return format(
                ENGLISH,
                "You have been banned until %s, please respect throttling and retry-after headers.",
                banEndTime
        );
    }

    /**
     * Notify the user that he is being throttled.
     *
     * @param response the response
     * @param duration how long the user will be throttled
     * @throws IOException if the response cannot be written
     */
    private void notifyUserThrottled(HttpServletResponse response, Duration duration) throws IOException {
        String retryAfter = Long.toString(duration.getSeconds());
        response.setHeader("Retry-After", retryAfter);
        response.sendError(429, format(ENGLISH, "Too Many Requests - Please retry in %s seconds.", retryAfter));
    }

    @Override
    public long getStateSize() {
        return stateStore.size();
    }

    @Override
    public long getNumberOfThrottledRequests() {
        return nbThrottledRequests.longValue();
    }

    @Override
    public long getNumberOfBannedRequests() {
        return nbBannedRequests.longValue();
    }

    /**
     * Safely compile regex pattern.
     * If there's an error, it returns null and logs.
     * @return Pattern or null if compile failed.
     */
    private Pattern safeCompile(String line) {
        try {
            return Pattern.compile(line, Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            LOG.warn("Invalid pattern: {}", line);
            return null;
        }
    }

    @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "Path comes from trusted configuration file.")
    private Collection<Pattern> loadRegexPatterns(String patternFilename) {
        try {
            Path patternFile = Paths.get(patternFilename);
            if (!patternFile.toFile().exists()) {
                LOG.info("Patterns file {} not found, ignoring.", patternFilename);
                return ImmutableList.of();
            }
            try (Stream<String> lines = Files.lines(patternFile, UTF_8)) {
                ImmutableList<Pattern> patterns = lines
                        .map(this::safeCompile)
                        .filter(Objects::nonNull)
                        .collect(toImmutableList());
                LOG.info("Loaded {} patterns from {}", patterns.size(), patternFilename);
                return patterns;
            }
        } catch (IOException e) {
            LOG.warn("Failed reading from patterns file {}.", patternFilename);
        }
        return ImmutableList.of();
    }

}
