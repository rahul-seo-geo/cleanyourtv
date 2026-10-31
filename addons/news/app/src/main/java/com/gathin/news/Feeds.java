package com.gathin.news;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.xmlpull.v1.XmlPullParser;

final class Feeds {
    static final class Item {
        String title;
        String link;
        String pubDate;
        long pubDateMs;
    }

    static List<Item> parse(InputStream in) throws IOException {
        List<Item> items = new ArrayList<>();
        if (in == null) return items;
        try {
            XmlPullParser parser = new org.kxml2.io.KXmlParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(in, "UTF-8");
            Item item = null;
            String field = null;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    if ("item".equals(name)) item = new Item();
                    else if (item != null && ("title".equals(name) || "link".equals(name) || "pubDate".equals(name))) field = name;
                } else if (event == XmlPullParser.TEXT && item != null && field != null) {
                    String value = parser.getText();
                    if ("title".equals(field)) item.title = (item.title == null ? "" : item.title) + value;
                    if ("link".equals(field)) item.link = (item.link == null ? "" : item.link) + value;
                    if ("pubDate".equals(field)) item.pubDate = (item.pubDate == null ? "" : item.pubDate) + value;
                } else if (event == XmlPullParser.END_TAG) {
                    if ("pubDate".equals(field) && item != null && item.pubDate != null) {
                        try { item.pubDateMs = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(item.pubDate.trim()).getTime(); }
                        catch (ParseException ignored) { item.pubDateMs = 0; }
                    }
                    if ("item".equals(parser.getName()) && item != null) {
                        if (item.title != null && !item.title.isBlank()) items.add(item);
                        item = null;
                    }
                    field = null;
                }
            }
        } catch (Exception ignored) { return new ArrayList<>(); }
        return items;
    }
}
