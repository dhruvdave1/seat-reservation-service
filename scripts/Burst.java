// Hot-seat burst against the seat reservation service, then a full reconciliation.
// Single file, JDK 25 only, no dependencies:  java scripts/Burst.java [options]
//
// Environment: BASE_URL (default http://localhost:8080), ADMIN_KEY (needed to create a show;
// not needed with --show-id).
// Options (defaults in brackets):
//   --requests N      reserve requests to fire [20000]
//   --concurrency C   requests in flight at once [500]
//   --users U         distinct users (tokens) [2000]
//   --seats S         seats in the created show [200]
//   --hot H           hot seats most requests fight over [5]
//   --hot-share F     share of requests aimed at hot seats [0.8]
//   --dup-share F     share of requests re-sent with the same idempotency key [0.1]
//   --show-id ID      use an existing show instead of creating one (no admin key needed)
//   --cancel-after    cancel every reservation the burst made, at the end (frees the seats)
//
// Exit code 0 only if there were no 5xx, no transport errors and every check passed.

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Burst {

	record Result(int index, int status, String reason, String reservationId, List<String> seats, String user,
			String key, boolean duplicate, long micros, String error) {
	}

	// HTTP/1.1: one connection per in-flight request. Over HTTP/2 every request shares one
	// connection, and Render's edge caps concurrent streams per connection (~100); the JDK
	// client then fails requests with "too many concurrent streams" instead of opening more.
	static final HttpClient http = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(15))
		.executor(Executors.newVirtualThreadPerTaskExecutor())
		.build();

	static String base;

	public static void main(String[] args) throws Exception {
		Map<String, String> opt = parse(args);
		base = System.getenv().getOrDefault("BASE_URL", "http://localhost:8080").replaceAll("/+$", "");
		int requests = Integer.parseInt(opt.getOrDefault("requests", "20000"));
		int concurrency = Integer.parseInt(opt.getOrDefault("concurrency", "500"));
		int users = Integer.parseInt(opt.getOrDefault("users", "2000"));
		int seatCount = Integer.parseInt(opt.getOrDefault("seats", "200"));
		int hot = Integer.parseInt(opt.getOrDefault("hot", "5"));
		double hotShare = Double.parseDouble(opt.getOrDefault("hot-share", "0.8"));
		double dupShare = Double.parseDouble(opt.getOrDefault("dup-share", "0.1"));
		Random random = new Random(7);

		System.out.printf("target %s%n", base);

		// 1. The show.
		String showId = opt.get("show-id");
		if (showId == null) {
			String adminKey = System.getenv("ADMIN_KEY");
			if (adminKey == null || adminKey.isBlank()) {
				fail("ADMIN_KEY is required to create a show (or pass --show-id)");
			}
			String admin = token("burst-admin", adminKey);
			StringBuilder seats = new StringBuilder();
			for (int i = 1; i <= seatCount; i++) {
				seats.append(i > 1 ? "," : "").append("\"S").append(i).append('"');
			}
			HttpResponse<String> created = send(post("/shows",
					"{\"name\":\"burst-" + System.currentTimeMillis() + "\",\"price_paise\":50000,\"seats\":[" + seats
							+ "]}", admin, null));
			if (created.statusCode() != 201) {
				fail("create show: " + created.statusCode() + " " + created.body());
			}
			showId = field(created.body(), "id");
		}
		final String show = showId;
		String showBefore = send(get("/shows/" + showId)).body();
		List<String> labels = all(showBefore, "\"label\":\"([^\"]+)\"");
		int perUserLimit = Integer.parseInt(field(showBefore, "per_user_limit"));
		List<String> hotSeats = labels.subList(0, Math.min(hot, labels.size()));
		System.out.printf("show %s: %d seats, %d hot %s, per-user limit %d%n", showId, labels.size(), hotSeats.size(),
				hotSeats, perUserLimit);

		// 2. Tokens, minted concurrently.
		String runTag = Long.toString(System.currentTimeMillis(), 36);
		List<String> userIds = new ArrayList<>();
		for (int i = 0; i < users; i++) {
			userIds.add("burst-" + runTag + "-u" + i);
		}
		Map<String, String> tokens = new ConcurrentHashMap<>();
		runAll(userIds.size(), 100, i -> tokens.put(userIds.get(i), token(userIds.get(i), null)));
		System.out.printf("minted %d tokens%n", tokens.size());

		// 2b. Per-user limit under concurrency: one fresh user, 10 parallel 1-seat reserves on
		// 10 distinct free seats. Exactly min(limit, 10) may succeed.
		int greedySeats = 0;
		List<String> limitChecks = new ArrayList<>();
		List<String> freeSeats = all(showBefore, "\\{\"label\":\"([^\"]+)\",\"status\":\"available\"\\}").stream()
			.filter(s -> !hotSeats.contains(s)).toList();
		if (freeSeats.size() >= 10) {
			String greedy = "burst-" + runTag + "-greedy";
			String greedyToken = token(greedy, null);
			List<String> targets = freeSeats.subList(freeSeats.size() - 10, freeSeats.size());
			AtomicInteger wins = new AtomicInteger();
			AtomicInteger limited = new AtomicInteger();
			runAll(10, 10, i -> {
				int code = send(post("/shows/" + show + "/reserve", "{\"seats\":[\"" + targets.get(i) + "\"]}", greedyToken,
						null)).statusCode();
				if (code == 201) {
					wins.incrementAndGet();
				}
				else if (code == 409) {
					limited.incrementAndGet();
				}
			});
			int expected = Math.min(perUserLimit, 10);
			String line = String.format("per-user storm: 10 parallel reserves by one user -> %d confirmed, %d declined (limit %d)",
					wins.get(), limited.get(), perUserLimit);
			System.out.println(line);
			if (wins.get() != expected || wins.get() + limited.get() != 10) {
				limitChecks.add(line);
			}
			greedySeats = wins.get();
		}

		// Metric baseline after the per-user storm (gauges cache their DB snapshot for 1 s).
		Thread.sleep(1200);
		Map<String, Double> metricsBefore = scrape();

		// 3. The request plan: mostly hot seats, some 2-seat requests, some duplicates.
		record Planned(String user, List<String> seats, String key, boolean duplicate) {
		}
		List<Planned> plan = new ArrayList<>();
		for (int i = 0; i < requests; i++) {
			String user = userIds.get(random.nextInt(userIds.size()));
			// two-seat requests only when the show has two seats; a pool smaller than n falls back to all seats
			int n = Math.min(random.nextDouble() < 0.1 ? 2 : 1, labels.size());
			Set<String> seats = new TreeSet<>();
			while (seats.size() < n) {
				List<String> pool = random.nextDouble() < hotShare && hotSeats.size() >= n ? hotSeats : labels;
				seats.add(pool.get(random.nextInt(pool.size())));
			}
			Planned p = new Planned(user, List.copyOf(seats), runTag + "-k" + i, false);
			plan.add(p);
			if (random.nextDouble() < dupShare && plan.size() < requests) {
				plan.add(new Planned(p.user(), p.seats(), p.key(), true));
				i++;
			}
		}
		Collections.shuffle(plan, random);

		// 4. Fire.
		System.out.printf("firing %d reserve requests, concurrency %d ...%n", plan.size(), concurrency);
		List<Result> results = new CopyOnWriteArrayList<>();
		long t0 = System.nanoTime();
		runAll(plan.size(), concurrency, i -> {
			Planned p = plan.get(i);
			String body = "{\"seats\":[\"" + String.join("\",\"", p.seats()) + "\"],\"idempotency_key\":\"" + p.key() + "\"}";
			long s = System.nanoTime();
			try {
				HttpResponse<String> r = send(post("/shows/" + show + "/reserve", body, tokens.get(p.user()), null));
				long micros = (System.nanoTime() - s) / 1000;
				String reason = r.statusCode() == 201 ? (p.duplicate() ? "created(dup-send)" : "created")
						: Objects.requireNonNullElse(fieldOrNull(r.body(), "reason"), "status_" + r.statusCode());
				results.add(new Result(i, r.statusCode(), reason,
						r.statusCode() == 201 ? field(r.body(), "reservation_id") : null,
						r.statusCode() == 201 ? all(r.body(), "\"seats\":\\[([^\\]]*)\\]").stream()
							.flatMap(x -> all("[" + x + "]", "\"([^\"]+)\"").stream()).toList() : List.of(),
						p.user(), p.key(), p.duplicate(), micros, null));
			}
			catch (Exception ex) {
				results.add(new Result(i, -1, "transport_error", null, List.of(), p.user(), p.key(), p.duplicate(),
						(System.nanoTime() - s) / 1000, ex.getClass().getSimpleName() + ": " + ex.getMessage()));
			}
		});
		double seconds = (System.nanoTime() - t0) / 1e9;

		// 5. Outcome distribution and latency.
		System.out.printf("%n== outcomes (%d requests in %.1f s, %.0f req/s)%n", results.size(), seconds,
				results.size() / seconds);
		Map<String, Long> byStatus = new TreeMap<>();
		Map<String, Long> byReason = new TreeMap<>();
		for (Result r : results) {
			byStatus.merge(r.status() == -1 ? "transport_error" : Integer.toString(r.status()), 1L, Long::sum);
			byReason.merge(r.reason(), 1L, Long::sum);
		}
		byStatus.forEach((k, v) -> System.out.printf("  status %-16s %7d%n", k, v));
		byReason.forEach((k, v) -> System.out.printf("  outcome %-20s %7d%n", k, v));
		long[] lat = results.stream().mapToLong(Result::micros).sorted().toArray();
		System.out.printf("  latency ms: p50 %.0f  p95 %.0f  p99 %.0f  max %.0f%n", pct(lat, 50), pct(lat, 95),
				pct(lat, 99), lat.length == 0 ? 0 : lat[lat.length - 1] / 1000.0);
		results.stream().filter(r -> r.error() != null).map(Result::error).distinct().limit(5)
			.forEach(e -> System.out.println("  error sample: " + e));

		// 6. Reconciliation.
		System.out.println("\n== reconciliation");
		List<String> failures = new ArrayList<>(limitChecks);
		if (freeSeats.size() >= 10) {
			check(failures, limitChecks.isEmpty(), "per-user limit held under 10 parallel reserves");
		}
		else {
			System.out.println("  [skip] per-user limit storm (needs 10 free non-hot seats)");
		}
		String showAfter = send(get("/shows/" + showId)).body();
		int total = Integer.parseInt(field(showAfter, "total_seats"));
		int available = Integer.parseInt(field(showAfter, "available"));
		int held = Integer.parseInt(field(showAfter, "held"));
		int confirmed = Integer.parseInt(field(showAfter, "confirmed"));
		// baseline after the per-user storm, so the burst's own seats are compared below
		int confirmedBefore = Integer.parseInt(field(showBefore, "confirmed")) + greedySeats;
		check(failures, available + held + confirmed == total, "invariant available %d + held %d + confirmed %d == total %d",
				available, held, confirmed, total);

		long fiveXx = results.stream().filter(r -> r.status() >= 500).count();
		long transport = results.stream().filter(r -> r.status() == -1).count();
		check(failures, fiveXx == 0, "zero 5xx (saw %d)", fiveXx);
		check(failures, transport == 0, "zero transport errors (saw %d)", transport);

		Map<String, List<String>> reservationSeats = new HashMap<>();
		Map<String, String> reservationUser = new HashMap<>();
		results.stream().filter(r -> r.status() == 201).forEach(r -> {
			reservationSeats.put(r.reservationId(), r.seats());
			reservationUser.put(r.reservationId(), r.user());
		});
		Map<String, Set<String>> seatOwners = new HashMap<>();
		reservationSeats.forEach((id, seats) -> seats.forEach(s -> seatOwners.computeIfAbsent(s, x -> new HashSet<>()).add(id)));
		long doubleBooked = seatOwners.values().stream().filter(owners -> owners.size() > 1).count();
		check(failures, doubleBooked == 0, "no seat in two reservations (%d double-booked)", doubleBooked);
		int seatsWon = reservationSeats.values().stream().mapToInt(List::size).sum();
		check(failures, confirmed - confirmedBefore == seatsWon, "confirmed seats in API (+%d) == seats in distinct 201s (%d)",
				confirmed - confirmedBefore, seatsWon);
		for (String seat : hotSeats) {
			check(failures, seatOwners.getOrDefault(seat, Set.of()).size() <= 1, "hot seat %s has at most one owner (%d)",
				seat, seatOwners.getOrDefault(seat, Set.of()).size());
		}

		Map<String, Set<String>> idsByKey = new HashMap<>();
		results.stream().filter(r -> r.status() == 201)
			.forEach(r -> idsByKey.computeIfAbsent(r.key(), k -> new HashSet<>()).add(r.reservationId()));
		long keysWithTwoIds = idsByKey.values().stream().filter(s -> s.size() > 1).count();
		check(failures, keysWithTwoIds == 0, "same idempotency key never produced two reservations (%d keys did)", keysWithTwoIds);

		Map<String, Integer> perUser = new HashMap<>();
		reservationSeats.forEach((id, seats) -> perUser.merge(reservationUser.get(id), seats.size(), Integer::sum));
		int maxPerUser = perUser.values().stream().mapToInt(Integer::intValue).max().orElse(0);
		check(failures, maxPerUser <= perUserLimit, "no user above the per-user limit (max %d, limit %d)", maxPerUser,
				perUserLimit);

		Map<String, Double> metricsAfter = scrape();
		if (metricsBefore.isEmpty() || metricsAfter.isEmpty()) {
			System.out.println("  (metrics not reachable; skipping metric reconciliation)");
		}
		else {
			long replays201 = results.stream().filter(r -> r.status() == 201).count() - reservationSeats.size();
			reconcileMetric(failures, metricsBefore, metricsAfter, "reservations_confirmed_total", reservationSeats.size());
			reconcileMetric(failures, metricsBefore, metricsAfter, "reservations_declined_total{reason=\"seat_taken\"}",
					byReason.getOrDefault("seat_taken", 0L));
			reconcileMetric(failures, metricsBefore, metricsAfter, "reservations_declined_total{reason=\"per_user_limit\"}",
					byReason.getOrDefault("per_user_limit", 0L));
			reconcileMetric(failures, metricsBefore, metricsAfter,
					"reservations_declined_total{reason=\"idempotent_replay\"}", replays201);
			Thread.sleep(1200); // gauges cache their DB snapshot for 1 s
			Map<String, Double> gauges = scrape();
			double gaugeDelta = gauges.get("seats_confirmed") - metricsBefore.get("seats_confirmed");
			check(failures, gaugeDelta == seatsWon, "seats_confirmed gauge moved by %.0f == seats won %d", gaugeDelta, seatsWon);
		}

		if (opt.containsKey("cancel-after")) {
			AtomicInteger cancelled = new AtomicInteger();
			List<Map.Entry<String, String>> toCancel = new ArrayList<>(reservationUser.entrySet());
			runAll(toCancel.size(), 50, i -> {
				var e = toCancel.get(i);
				if (send(post("/reservations/" + e.getKey() + "/cancel", "", tokens.get(e.getValue()), null)).statusCode() == 200) {
					cancelled.incrementAndGet();
				}
			});
			System.out.printf("  cancelled %d/%d burst reservations; show now %s%n", cancelled.get(), toCancel.size(),
					field(send(get("/shows/" + showId)).body(), "counts").isEmpty() ? "?" : counts(send(get("/shows/" + showId)).body()));
		}

		System.out.println(failures.isEmpty() ? "\nRESULT: PASS" : "\nRESULT: FAIL " + failures);
		System.exit(failures.isEmpty() ? 0 : 1);
	}

	// --- checks and metrics

	static void check(List<String> failures, boolean ok, String format, Object... args) {
		String line = String.format(format, args);
		System.out.printf("  [%s] %s%n", ok ? "ok" : "FAIL", line);
		if (!ok) {
			failures.add(line);
		}
	}

	static void reconcileMetric(List<String> failures, Map<String, Double> before, Map<String, Double> after, String name,
			long expectedDelta) {
		double delta = after.getOrDefault(name, 0.0) - before.getOrDefault(name, 0.0);
		check(failures, delta == expectedDelta, "metric %s moved by %.0f == observed %d", name, delta, expectedDelta);
	}

	/** Prometheus text format, with the application tag dropped so names are short. */
	static Map<String, Double> scrape() {
		Map<String, Double> metrics = new HashMap<>();
		try {
			HttpResponse<String> r = send(get("/actuator/prometheus"));
			if (r.statusCode() != 200) {
				return metrics;
			}
			for (String line : r.body().split("\n")) {
				if (line.startsWith("#") || line.isBlank()) {
					continue;
				}
				int space = line.lastIndexOf(' ');
				String name = line.substring(0, space).replaceAll("application=\"[^\"]*\",?", "").replace(",}", "}")
					.replace("{}", "");
				metrics.put(name, Double.parseDouble(line.substring(space + 1)));
			}
		}
		catch (Exception ignored) {
		}
		return metrics;
	}

	// --- http

	static String token(String userId, String adminKey) throws Exception {
		String body = adminKey == null ? "{\"user_id\":\"" + userId + "\"}"
				: "{\"user_id\":\"" + userId + "\",\"admin_key\":\"" + adminKey + "\"}";
		HttpResponse<String> r = send(post("/auth/token", body, null, null));
		if (r.statusCode() != 200) {
			fail("token for " + userId + ": " + r.statusCode() + " " + r.body());
		}
		return field(r.body(), "access_token");
	}

	static HttpRequest post(String path, String json, String bearer, String key) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
			.timeout(Duration.ofSeconds(120))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json));
		if (bearer != null) {
			b.header("Authorization", "Bearer " + bearer);
		}
		return b.build();
	}

	static HttpRequest get(String path) {
		return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120)).GET().build();
	}

	static HttpResponse<String> send(HttpRequest request) throws Exception {
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	interface Task {

		void run(int i) throws Exception;

	}

	/** Runs tasks 0..count-1 on virtual threads with at most `parallel` in flight. */
	static void runAll(int count, int parallel, Task task) throws Exception {
		Semaphore permits = new Semaphore(parallel);
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<?>> futures = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				permits.acquire();
				int n = i;
				futures.add(pool.submit(() -> {
					try {
						task.run(n);
					}
					finally {
						permits.release();
					}
					return null;
				}));
			}
			for (Future<?> f : futures) {
				f.get();
			}
		}
	}

	// --- tiny JSON helpers (flat fields only)

	static String field(String json, String name) {
		String v = fieldOrNull(json, name);
		if (v == null) {
			fail("field " + name + " missing in " + json);
		}
		return v;
	}

	static String fieldOrNull(String json, String name) {
		Matcher m = Pattern.compile("\"" + name + "\":\\s*(\"([^\"]*)\"|(-?[0-9.]+)|(\\{[^}]*\\}))").matcher(json);
		if (!m.find()) {
			return null;
		}
		return m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
	}

	static List<String> all(String text, String regex) {
		List<String> out = new ArrayList<>();
		Matcher m = Pattern.compile(regex).matcher(text);
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}

	static String counts(String showJson) {
		return fieldOrNull(showJson, "counts");
	}

	static double pct(long[] sortedMicros, int p) {
		if (sortedMicros.length == 0) {
			return 0;
		}
		int i = (int) Math.ceil(p / 100.0 * sortedMicros.length) - 1;
		return sortedMicros[Math.max(0, i)] / 1000.0;
	}

	static Map<String, String> parse(String[] args) {
		Map<String, String> opt = new HashMap<>();
		for (int i = 0; i < args.length; i++) {
			String a = args[i].replaceFirst("^--", "");
			if (a.equals("cancel-after")) {
				opt.put(a, "true");
			}
			else if (i + 1 < args.length) {
				opt.put(a, args[++i]);
			}
		}
		return opt;
	}

	static void fail(String message) {
		System.err.println("burst: " + message);
		System.exit(2);
	}

}
