import java.net.*; import java.net.http.*; import java.time.*;
public class Probe { public static void main(String[] a) throws Exception {
  HttpClient c = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
  long end = System.currentTimeMillis() + Long.parseLong(a[1]) * 1000; int n = 0, slow = 0; long worst = 0; String worstAt = "";
  while (System.currentTimeMillis() < end) {
    long t0 = System.nanoTime();
    c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + a[0] + "/api/occupancy")).header("X-Api-Key","sekret").header("Content-Type","application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString("area=A01-01&delta=0")).build(), HttpResponse.BodyHandlers.ofString());
    long ms = (System.nanoTime() - t0) / 1_000_000; n++;
    if (ms > 100) { slow++; System.out.println("  " + LocalTime.now().withNano(0) + "  sensor update took " + ms + " ms"); }
    if (ms > worst) { worst = ms; worstAt = LocalTime.now().withNano(0).toString(); }
    Thread.sleep(50);
  }
  System.out.println(n + " sensor updates, " + slow + " over 100 ms, worst " + worst + " ms at " + worstAt);
}}
