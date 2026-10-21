import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns bench/results/ CSVs into the Markdown tables of BENCHMARKS.md: the median of the timed runs for
 * every number. Usage: java bench/Summarize.java bench/results
 */
public class Summarize {

    /** test name -> metric -> values across runs */
    static Map<String, Map<String, List<Double>>> load(Path dir, String label) throws IOException {
        Map<String, Map<String, List<Double>>> out = new LinkedHashMap<>();
        for (int run = 1; ; run++) {
            Path f = dir.resolve(label + "-run" + run + ".csv");
            if (!Files.exists(f)) {
                break;
            }
            List<String> lines = Files.readAllLines(f);
            String[] header = split(lines.get(0));
            for (String line : lines.subList(1, lines.size())) {
                String[] cells = split(line);
                Map<String, List<Double>> m = out.computeIfAbsent(cells[0], k -> new LinkedHashMap<>());
                for (int i = 1; i < cells.length; i++) {
                    m.computeIfAbsent(header[i], k -> new ArrayList<>()).add(Double.parseDouble(cells[i]));
                }
            }
        }
        return out;
    }

    static String[] split(String line) {
        return Arrays.stream(line.split("\",\"")).map(s -> s.replace("\"", "")).toArray(String[]::new);
    }

    static double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        s.sort(null);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    static double med(Map<String, Map<String, List<Double>>> data, String test, String metric) {
        Map<String, List<Double>> m = data.get(test);
        if (m == null || !m.containsKey(metric)) {
            return Double.NaN;
        }
        return median(m.get(metric));
    }

    static String rps(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%,.0f", v);
    }

    static String pct(double a, double b) {
        return Double.isNaN(a) || Double.isNaN(b) ? "n/a" : String.format(Locale.ROOT, "%.0f%%", 100 * a / b);
    }

    static String ms(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    static String shortName(String test) {
        int i = test.indexOf(' ');
        return i < 0 ? test : test.substring(0, i);
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "bench/results");
        Path b1 = root.resolve("b1");
        if (Files.isDirectory(b1)) {
            var java = load(b1, "java");
            var redis = load(b1, "redis");
            System.out.println("### B1. Throughput by command (50 clients, no pipelining)\n");
            System.out.println("| Test | This server (req/s) | Redis 8.10.2 (req/s) | This server as % of Redis |");
            System.out.println("|---|---:|---:|---:|");
            for (String t : java.keySet()) {
                if (t.startsWith("LPUSH (needed")) {
                    continue;
                }
                double a = med(java, t, "rps");
                double b = med(redis, t, "rps");
                System.out.println("| " + shortName(t) + " | " + rps(a) + " | " + rps(b) + " | " + pct(a, b) + " |");
            }
            System.out.println("\n### B4. Latency (ms, from the B1 runs)\n");
            System.out.println("| Test | Server | p50 | p95 | p99 | max |");
            System.out.println("|---|---|---:|---:|---:|---:|");
            for (String t : List.of("SET", "GET")) {
                for (var e : List.of(Map.entry("this server", java), Map.entry("Redis", redis))) {
                    var d = e.getValue();
                    System.out.println("| " + t + " | " + e.getKey() + " | " + ms(med(d, t, "p50_latency_ms")) + " | "
                            + ms(med(d, t, "p95_latency_ms")) + " | " + ms(med(d, t, "p99_latency_ms")) + " | "
                            + ms(med(d, t, "max_latency_ms")) + " |");
                }
            }
            System.out.println();
            Path b2 = root.resolve("b2");
            if (Files.isDirectory(b2)) {
                var j16 = load(b2, "java-P16");
                var r16 = load(b2, "redis-P16");
                System.out.println("### B2. Pipelining (50 clients)\n");
                System.out.println("| Test | Pipeline | This server (req/s) | Redis (req/s) | This server as % of Redis |");
                System.out.println("|---|---:|---:|---:|---:|");
                for (String t : List.of("SET", "GET")) {
                    System.out.println("| " + t + " | 1 | " + rps(med(java, t, "rps")) + " | " + rps(med(redis, t, "rps"))
                            + " | " + pct(med(java, t, "rps"), med(redis, t, "rps")) + " |");
                    System.out.println("| " + t + " | 16 | " + rps(med(j16, t, "rps")) + " | " + rps(med(r16, t, "rps"))
                            + " | " + pct(med(j16, t, "rps"), med(r16, t, "rps")) + " |");
                }
                System.out.println();
            }
            Path b3 = root.resolve("b3");
            if (Files.isDirectory(b3)) {
                System.out.println("### B3. Client count (SET)\n");
                System.out.println("| Clients | This server (req/s) | Redis (req/s) | This server as % of Redis |");
                System.out.println("|---:|---:|---:|---:|");
                double j1 = med(load(b3, "java-c1"), "SET", "rps");
                double r1 = med(load(b3, "redis-c1"), "SET", "rps");
                double j200 = med(load(b3, "java-c200"), "SET", "rps");
                double r200 = med(load(b3, "redis-c200"), "SET", "rps");
                System.out.println("| 1 | " + rps(j1) + " | " + rps(r1) + " | " + pct(j1, r1) + " |");
                System.out.println("| 50 | " + rps(med(java, "SET", "rps")) + " | " + rps(med(redis, "SET", "rps")) + " | "
                        + pct(med(java, "SET", "rps"), med(redis, "SET", "rps")) + " |");
                System.out.println("| 200 | " + rps(j200) + " | " + rps(r200) + " | " + pct(j200, r200) + " |");
                System.out.println();
            }
            Path b5 = root.resolve("b5");
            if (Files.isDirectory(b5)) {
                System.out.println("### B5. AOF cost (SET, 50 clients)\n");
                System.out.println("| AOF | This server (req/s) | Redis (req/s) | This server as % of Redis |");
                System.out.println("|---|---:|---:|---:|");
                for (String p : List.of("off", "no", "everysec", "always")) {
                    double a = med(load(b5, "java-" + p), "SET", "rps");
                    double b = med(load(b5, "redis-" + p), "SET", "rps");
                    String label = p.equals("off") ? "off" : "appendfsync " + p;
                    System.out.println("| " + label + " | " + rps(a) + " | " + rps(b) + " | " + pct(a, b) + " |");
                }
                System.out.println();
            }
        }
        Path b6 = root.resolve("b6/crash.csv");
        if (Files.exists(b6)) {
            System.out.println("### B6. Crash test (kill -9 mid-stream)\n");
            System.out.println("| Policy | Run | Writes acknowledged | Recovered after restart | Acknowledged but lost |");
            System.out.println("|---|---:|---:|---:|---:|");
            for (String line : Files.readAllLines(b6).subList(1, Files.readAllLines(b6).size())) {
                String[] c = line.split(",");
                System.out.println("| " + c[0] + " | " + c[1] + " | " + c[2] + " | " + c[3] + " | " + c[4] + " |");
            }
        }
    }
}
