package crowdwatch.core;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import crowdwatch.ds.AVLTree;
import crowdwatch.ds.LinkedList;
import crowdwatch.ds.MergeSort;
import crowdwatch.model.Alert;
import crowdwatch.model.Area;
import crowdwatch.model.Incident;
import crowdwatch.model.Enums.AlertKind;

/**
 * Reports over a period: the busiest times of each area (from the minute frames), incidents per
 * day and category, how quickly units acknowledged and reached incidents, and how often the
 * officer's check-in was overdue or a unit did not answer. Written as JSON (for the console),
 * CSV (for Excel) or a printable HTML page (Print > Save as PDF in the browser).
 */
public final class Reports {
    private Reports() { }

    /** One incident's times: id, category, area, reported, acknowledged, on scene, resolved, automatic. */
    public static Object[] times(Incident i) {
        return new Object[] { i.id, i.category.code, i.area.id, i.reported, i.acknowledged, i.onScene, i.resolved, i.automatic };
    }

    /** The figures of a report. */
    public static final class Report {
        public LocalDate from, to;
        public String venue;
        // per area: id, name, peak people, peak %, time of the peak, busiest hour of the day, average % in that hour
        public final LinkedList<Object[]> areas = new LinkedList<>();
        // per hour of the day: average people on site (whole venue)
        public final double[] hourly = new double[24];
        // per day: date, incidents
        public final LinkedList<Object[]> days = new LinkedList<>();
        // per category: code, incidents, median minutes to on scene (-1 if none)
        public final LinkedList<Object[]> categories = new LinkedList<>();
        public int incidents, automatic, acknowledged, onScene;
        public double ackMedian = -1, ackP90 = -1, sceneMedian = -1, sceneP90 = -1;
        public int overdue, noAnswer, sos, frames;
    }

    /**
     * Builds a report.
     *
     * @param frames    minute frames in the period
     * @param incidents incident times (see {@link #times}) in the period
     * @param alerts    alerts (any period; only those in the period are counted)
     */
    public static Report build(CrowdMonitor m, LocalDate from, LocalDate to, LinkedList<Records.Frame> frames, LinkedList<Object[]> incidents, LinkedList<Alert> alerts) {
        Report r = new Report();
        r.from = from; r.to = to; r.venue = m.venueName();
        LocalDateTime start = from.atStartOfDay(), end = to.plusDays(1).atStartOfDay();
        // areas: peaks and busiest hour
        AVLTree<String, double[]> peak = new AVLTree<>();          // id -> {people, pct, minute as epoch}
        AVLTree<String, double[]> hourSum = new AVLTree<>();        // id -> 24 sums of %, then 24 counts
        double[] venueHour = new double[24];
        int[] venueHourN = new int[24];
        for (Records.Frame f : frames) {
            if (f.minute.isBefore(start) || !f.minute.isBefore(end)) continue;
            r.frames++;
            int h = f.minute.getHour(), total = 0;
            for (int k = 0; k < f.ids.length; k++) {
                Area a = m.findArea(f.ids[k]);
                if (a == null || a.restricted) continue;
                int pct = a.capacity <= 0 ? 0 : (int) Math.round(f.occupancy[k] * 100.0 / a.capacity);
                total += f.occupancy[k];
                double[] p = peak.search(a.id);
                if (p == null || f.occupancy[k] > p[0]) peak.insert(a.id, new double[] { f.occupancy[k], pct, f.minute.toEpochSecond(java.time.ZoneOffset.UTC) });
                double[] hs = hourSum.search(a.id);
                if (hs == null) { hs = new double[48]; hourSum.insert(a.id, hs); }
                hs[h] += pct;
                hs[24 + h]++;
            }
            venueHour[h] += total;
            venueHourN[h]++;
        }
        for (int h = 0; h < 24; h++) r.hourly[h] = venueHourN[h] == 0 ? 0 : Math.round(venueHour[h] / venueHourN[h]);
        for (String id : peak.keysInOrder()) {
            double[] p = peak.search(id), hs = hourSum.search(id);
            int best = 0;
            for (int h = 1; h < 24; h++) if (avg(hs, h) > avg(hs, best)) best = h;
            Area a = m.findArea(id);
            r.areas.addLast(new Object[] { id, a.name, (int) p[0], (int) p[1], LocalDateTime.ofEpochSecond((long) p[2], 0, java.time.ZoneOffset.UTC),
                String.format("%02d:00-%02d:00", best, (best + 1) % 24), (int) Math.round(avg(hs, best)) });
        }
        // busiest first
        Object[][] arr = r.areas.toArray(new Object[0][]);
        MergeSort.sort(arr, (x, y) -> Integer.compare((Integer) y[3], (Integer) x[3]));
        r.areas.clear();
        for (Object[] x : arr) r.areas.addLast(x);
        // incidents
        AVLTree<String, int[]> perDay = new AVLTree<>();
        AVLTree<String, LinkedList<Double>> perCat = new AVLTree<>();
        LinkedList<Double> ack = new LinkedList<>(), scene = new LinkedList<>();
        AVLTree<Integer, Boolean> seen = new AVLTree<>();
        for (Object[] i : incidents) {
            LocalDateTime rep = (LocalDateTime) i[3];
            if (rep == null || rep.isBefore(start) || !rep.isBefore(end) || seen.contains((Integer) i[0])) continue;
            seen.insert((Integer) i[0], true);
            r.incidents++;
            if (Boolean.TRUE.equals(i[7])) r.automatic++;
            int[] d = perDay.search(rep.toLocalDate().toString());
            if (d == null) perDay.insert(rep.toLocalDate().toString(), new int[] { 1 }); else d[0]++;
            LinkedList<Double> c = perCat.search((String) i[1]);
            if (c == null) { c = new LinkedList<>(); perCat.insert((String) i[1], c); }
            if (i[4] != null) { r.acknowledged++; ack.addLast(minutes(rep, (LocalDateTime) i[4])); }
            if (i[5] != null) { r.onScene++; double mm = minutes(rep, (LocalDateTime) i[5]); scene.addLast(mm); c.addLast(mm); }
            else c.addLast(Double.NaN);
        }
        for (String day : perDay.keysInOrder()) r.days.addLast(new Object[] { day, perDay.search(day)[0] });
        for (String cat : perCat.keysInOrder()) {
            LinkedList<Double> all = perCat.search(cat), ok = new LinkedList<>();
            for (Double x : all) if (!x.isNaN()) ok.addLast(x);
            r.categories.addLast(new Object[] { cat, all.size(), ok.isEmpty() ? -1.0 : pct(ok, 50) });
        }
        if (!ack.isEmpty()) { r.ackMedian = pct(ack, 50); r.ackP90 = pct(ack, 90); }
        if (!scene.isEmpty()) { r.sceneMedian = pct(scene, 50); r.sceneP90 = pct(scene, 90); }
        for (Alert a : alerts) {
            if (a.time.isBefore(start) || !a.time.isBefore(end)) continue;
            if (a.kind == AlertKind.SUPERVISION) r.overdue++;
            else if (a.kind == AlertKind.NO_ACK) r.noAnswer++;
            else if (a.kind == AlertKind.SOS) r.sos++;
        }
        return r;
    }

    private static double avg(double[] hs, int h) { return hs[24 + h] == 0 ? -1 : hs[h] / hs[24 + h]; }

    private static double minutes(LocalDateTime a, LocalDateTime b) { return Math.max(0, Duration.between(a, b).getSeconds() / 60.0); }

    // the p-th percentile of the values (nearest rank)
    private static double pct(LinkedList<Double> v, int p) {
        Double[] a = v.toArray(new Double[0]);
        MergeSort.sort(a, Double::compare);
        int k = (int) Math.ceil(p / 100.0 * a.length) - 1;
        return Math.round(a[Math.max(0, Math.min(a.length - 1, k))] * 10) / 10.0;
    }

    private static String q(Object o) {
        if (o == null) return "null";
        if (o instanceof Number || o instanceof Boolean) return String.valueOf(o);
        String s = String.valueOf(o);
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) { if (c == '"' || c == '\\') sb.append('\\'); if (c >= 0x20) sb.append(c); }
        return sb.append('"').toString();
    }

    /** @return the report as JSON */
    public static String json(Report r) {
        StringBuilder sb = new StringBuilder("{\"venue\":").append(q(r.venue)).append(",\"from\":").append(q(r.from)).append(",\"to\":").append(q(r.to))
            .append(",\"frames\":").append(r.frames).append(",\"areas\":[");
        int k = 0;
        for (Object[] a : r.areas) sb.append(k++ > 0 ? "," : "").append("{\"id\":").append(q(a[0])).append(",\"name\":").append(q(a[1])).append(",\"peak\":").append(a[2])
            .append(",\"peakPct\":").append(a[3]).append(",\"peakAt\":").append(q(a[4].toString().replace('T', ' '))).append(",\"busiestHour\":").append(q(a[5])).append(",\"busiestPct\":").append(a[6]).append('}');
        sb.append("],\"hourly\":[");
        for (int h = 0; h < 24; h++) sb.append(h > 0 ? "," : "").append((long) r.hourly[h]);
        sb.append("],\"days\":[");
        k = 0;
        for (Object[] d : r.days) sb.append(k++ > 0 ? "," : "").append("{\"day\":").append(q(d[0])).append(",\"incidents\":").append(d[1]).append('}');
        sb.append("],\"categories\":[");
        k = 0;
        for (Object[] c : r.categories) sb.append(k++ > 0 ? "," : "").append("{\"category\":").append(q(c[0])).append(",\"incidents\":").append(c[1]).append(",\"sceneMedian\":").append(c[2]).append('}');
        return sb.append("],\"incidents\":").append(r.incidents).append(",\"automatic\":").append(r.automatic).append(",\"acknowledged\":").append(r.acknowledged)
            .append(",\"onScene\":").append(r.onScene).append(",\"ackMedian\":").append(r.ackMedian).append(",\"ackP90\":").append(r.ackP90)
            .append(",\"sceneMedian\":").append(r.sceneMedian).append(",\"sceneP90\":").append(r.sceneP90)
            .append(",\"overdue\":").append(r.overdue).append(",\"noAnswer\":").append(r.noAnswer).append(",\"sos\":").append(r.sos).append('}').toString();
    }

    private static String c(Object o) {
        String s = o == null ? "" : String.valueOf(o);
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) s = "'" + s;   // never a formula in Excel
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** @return the report as CSV (sections separated by a blank line), opens in Excel */
    public static String csv(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("CrowdWatch report,").append(c(r.venue)).append(',').append(r.from).append(',').append(r.to).append("\n\n");
        sb.append("Area,Name,Peak people,Peak %,Peak at,Busiest hour,Average % in that hour\n");
        for (Object[] a : r.areas) sb.append(c(a[0])).append(',').append(c(a[1])).append(',').append(a[2]).append(',').append(a[3]).append(',').append(a[4].toString().replace('T', ' ')).append(',').append(a[5]).append(',').append(a[6]).append('\n');
        sb.append("\nHour,Average people on site\n");
        for (int h = 0; h < 24; h++) sb.append(String.format("%02d:00", h)).append(',').append((long) r.hourly[h]).append('\n');
        sb.append("\nDay,Incidents\n");
        for (Object[] d : r.days) sb.append(d[0]).append(',').append(d[1]).append('\n');
        sb.append("\nCategory,Incidents,Median minutes to on scene\n");
        for (Object[] x : r.categories) sb.append(c(x[0])).append(',').append(x[1]).append(',').append((Double) x[2] < 0 ? "" : x[2]).append('\n');
        sb.append("\nMeasure,Value\n");
        sb.append("Incidents,").append(r.incidents).append('\n').append("Automatic incidents,").append(r.automatic).append('\n')
          .append("Median minutes to acknowledge,").append(r.ackMedian < 0 ? "" : r.ackMedian).append('\n').append("90% acknowledged within (min),").append(r.ackP90 < 0 ? "" : r.ackP90).append('\n')
          .append("Median minutes to on scene,").append(r.sceneMedian < 0 ? "" : r.sceneMedian).append('\n').append("90% on scene within (min),").append(r.sceneP90 < 0 ? "" : r.sceneP90).append('\n')
          .append("Officer check-in overdue,").append(r.overdue).append('\n').append("Units that did not answer a task,").append(r.noAnswer).append('\n').append("SOS,").append(r.sos).append('\n');
        return sb.toString();
    }

    private static String h(Object o) { return String.valueOf(o == null ? "" : o).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    /** @return the report as a page to print or save as PDF from the browser */
    public static String html(Report r) {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html><html><head><meta charset='utf-8'><title>CrowdWatch report - ").append(h(r.venue)).append("</title><style>")
            .append("body{font-family:system-ui,Arial,sans-serif;margin:24px;color:#0F1B33}h1{margin:0 0 4px}h2{margin:22px 0 8px;font-size:16px}table{border-collapse:collapse;width:100%;font-size:13px}")
            .append("th,td{border-bottom:1px solid #D9E0EC;padding:5px 8px;text-align:left}th{background:#EEF2F8}.bar{display:inline-block;height:10px;background:#2F7CF6;border-radius:3px}")
            .append(".k{display:inline-block;margin:0 18px 8px 0}.k b{font-size:20px;display:block}button{margin:10px 0;padding:8px 14px}@media print{button{display:none}}</style></head><body>")
            .append("<h1>").append(h(r.venue)).append("</h1><div>Report ").append(r.from).append(" to ").append(r.to).append(" - ").append(r.frames).append(" minutes recorded</div>")
            .append("<button onclick='print()'>Print / save as PDF</button>");
        sb.append("<h2>Response</h2>");
        sb.append(kpi("Incidents", r.incidents)).append(kpi("Automatic", r.automatic)).append(kpi("Median to acknowledge (min)", r.ackMedian < 0 ? "-" : r.ackMedian))
          .append(kpi("90% acknowledged within", r.ackP90 < 0 ? "-" : r.ackP90 + " min")).append(kpi("Median to on scene (min)", r.sceneMedian < 0 ? "-" : r.sceneMedian))
          .append(kpi("90% on scene within", r.sceneP90 < 0 ? "-" : r.sceneP90 + " min")).append(kpi("Check-in overdue", r.overdue)).append(kpi("Unit did not answer", r.noAnswer)).append(kpi("SOS", r.sos));
        sb.append("<h2>Busiest areas</h2><table><tr><th>Area</th><th>Peak</th><th>When</th><th>Busiest hour (average)</th></tr>");
        for (Object[] a : r.areas) sb.append("<tr><td><b>").append(h(a[0])).append("</b> ").append(h(a[1])).append("</td><td>").append(a[2]).append(" (").append(a[3]).append("%)</td><td>")
            .append(h(a[4].toString().replace('T', ' '))).append("</td><td>").append(a[5]).append(" (").append(a[6]).append("%)</td></tr>");
        sb.append("</table><h2>People on site by hour (average)</h2><table>");
        double max = 1;
        for (double x : r.hourly) max = Math.max(max, x);
        for (int hr = 0; hr < 24; hr++) if (r.hourly[hr] > 0)
            sb.append("<tr><td style='width:70px'>").append(String.format("%02d:00", hr)).append("</td><td><span class='bar' style='width:").append(Math.round(r.hourly[hr] * 400 / max)).append("px'></span> ").append((long) r.hourly[hr]).append("</td></tr>");
        sb.append("</table><h2>Incidents per day</h2><table><tr><th>Day</th><th>Incidents</th></tr>");
        for (Object[] d : r.days) sb.append("<tr><td>").append(d[0]).append("</td><td>").append(d[1]).append("</td></tr>");
        sb.append("</table><h2>By category</h2><table><tr><th>Category</th><th>Incidents</th><th>Median to on scene</th></tr>");
        for (Object[] x : r.categories) sb.append("<tr><td>").append(h(x[0])).append("</td><td>").append(x[1]).append("</td><td>").append((Double) x[2] < 0 ? "-" : x[2] + " min").append("</td></tr>");
        return sb.append("</table></body></html>").toString();
    }

    private static String kpi(String label, Object v) { return "<span class='k'><b>" + h(v) + "</b>" + h(label) + "</span>"; }
}
