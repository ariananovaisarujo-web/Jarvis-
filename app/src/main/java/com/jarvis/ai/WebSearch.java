package com.jarvis.ai;

import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Free web search without any API key (DuckDuckGo results, with Wikipedia as a backup). */
final class WebSearch {
    private WebSearch() {
    }

    private static String clean(String s) {
        return s.replaceAll("(?s)<[^>]+>", " ")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
    }

    static String search(String query) {
        StringBuilder sb = new StringBuilder();
        String firstUrl = null;
        try {
            String html = Http.get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"));
            Pattern title = Pattern.compile("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
            Pattern snip = Pattern.compile("class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
            Matcher mt = title.matcher(html);
            Matcher ms = snip.matcher(html);
            int n = 0;
            while (mt.find() && n < 6) {
                String sn = ms.find(mt.end()) ? clean(ms.group(1)) : "";
                String href = mt.group(1);
                int u = href.indexOf("uddg=");
                if (u >= 0) {
                    String enc = href.substring(u + 5);
                    int amp = enc.indexOf('&');
                    if (amp > 0) enc = enc.substring(0, amp);
                    try {
                        href = java.net.URLDecoder.decode(enc, "UTF-8");
                    } catch (Exception ignored) {
                    }
                }
                if (firstUrl == null && href.startsWith("http")) firstUrl = href;
                sb.append(clean(mt.group(2))).append(": ").append(sn).append("\n");
                n++;
            }
        } catch (Exception ignored) {
        }
        try {
            String w = Http.get("https://en.wikipedia.org/w/api.php?action=query&list=search&srlimit=2&format=json&srsearch="
                    + URLEncoder.encode(query, "UTF-8"));
            org.json.JSONArray arr = new org.json.JSONObject(w).getJSONObject("query").getJSONArray("search");
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                sb.append("Wikipedia - ").append(o.optString("title")).append(": ").append(clean(o.optString("snippet"))).append("\n");
            }
        } catch (Exception ignored) {
        }
        if (firstUrl != null && sb.length() < 2500) {
            try {
                String page = Http.get(firstUrl)
                        .replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>", " ");
                page = clean(page);
                if (page.length() > 1800) page = page.substring(0, 1800);
                sb.append("Top page text: ").append(page).append("\n");
            } catch (Exception ignored) {
            }
        }
        return sb.length() == 0 ? "No results found. The internet may be off or the search was blocked." : sb.toString();
    }
}
