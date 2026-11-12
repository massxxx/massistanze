package it.massxxx.massistanze.centro;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON minimo: oggetti = LinkedHashMap, liste = ArrayList, numeri = Double/Long. */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object leggi(String testo) {
        Json j = new Json(testo);
        j.spazi();
        Object v = j.valore();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> oggetto(String testo) {
        Object o = leggi(testo);
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
    }

    private void spazi() { while (i < s.length() && s.charAt(i) <= ' ') i++; }

    private Object valore() {
        spazi();
        if (i >= s.length()) throw new IllegalArgumentException("JSON finito");
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            spazi();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                spazi();
                String k = stringa();
                spazi();
                if (s.charAt(i++) != ':') throw new IllegalArgumentException("manca :");
                m.put(k, valore());
                spazi();
                char d = s.charAt(i++);
                if (d == '}') return m;
                if (d != ',') throw new IllegalArgumentException("manca ,");
            }
        }
        if (c == '[') {
            i++;
            List<Object> l = new ArrayList<>();
            spazi();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(valore());
                spazi();
                char d = s.charAt(i++);
                if (d == ']') return l;
                if (d != ',') throw new IllegalArgumentException("manca ,");
            }
        }
        if (c == '"') return stringa();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        int a = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(a, i);
        if (n.isEmpty()) throw new IllegalArgumentException("valore strano");
        if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
            try { return Long.parseLong(n); } catch (NumberFormatException e) { /* sotto */ }
        }
        return Double.parseDouble(n);
    }

    private String stringa() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("manca \"");
        i++;
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else b.append(c);
        }
    }

    // ---------------------------------------------------------------- scrittura
    public static String scrivi(Object o) {
        StringBuilder b = new StringBuilder();
        scrivi(b, o);
        return b.toString();
    }

    private static void scrivi(StringBuilder b, Object o) {
        if (o == null) b.append("null");
        else if (o instanceof String) stringa(b, (String) o);
        else if (o instanceof Boolean) b.append(o.toString());
        else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d).append(".0");
            else b.append(d);
        } else if (o instanceof Number) b.append(o.toString());
        else if (o instanceof Map) {
            b.append('{');
            boolean primo = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!primo) b.append(',');
                primo = false;
                stringa(b, String.valueOf(e.getKey()));
                b.append(':');
                scrivi(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof Iterable) {
            b.append('[');
            boolean primo = true;
            for (Object x : (Iterable<?>) o) {
                if (!primo) b.append(',');
                primo = false;
                scrivi(b, x);
            }
            b.append(']');
        } else stringa(b, o.toString());
    }

    private static void stringa(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // ---------------------------------------------------------------- comodità
    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : v.toString();
    }

    public static double num(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) try { return Double.parseDouble((String) v); } catch (Exception e) { return def; }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : v instanceof Number ? ((Number) v).doubleValue() != 0 : v != null && !"".equals(v);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> lista(Object v) {
        return v instanceof List ? (List<Object>) v : new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mappa(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<String, Object>();
    }

    public static Map<String, Object> m(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return m;
    }
}
