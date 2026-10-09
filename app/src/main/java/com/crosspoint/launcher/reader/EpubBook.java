package com.crosspoint.launcher.reader;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;
import org.xml.sax.XMLReader;
import javax.xml.parsers.SAXParserFactory;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Android adaptation of CrossPoint's container -> OPF -> spine, one-section-at-a-time reader. */
public final class EpubBook {
    // ponytail: cap chapter size at 1 MiB; stream text blocks if larger chapters are needed.
    private static final int MAX_ENTRY = 1024 * 1024;
    public final File file;
    public final String title;
    public final String author;
    /** BCP 47 tag from dc:language (empty when absent); drives hyphenation patterns. */
    public final String language;
    public final List<String> chapters;
    public final List<Integer> spineIndices;
    /** Table of contents from the EPUB 3 nav document or EPUB 2 NCX; empty when the book has none. */
    public final List<TocEntry> toc;

    public static final class TocEntry {
        public final String title;
        public final int chapter;
        public final int depth;
        public final String fragment;
        TocEntry(String title, int chapter, int depth, String fragment) {
            this.title = title; this.chapter = chapter; this.depth = depth; this.fragment = fragment;
        }
    }

    public EpubBook(File file) throws Exception {
        this.file = file;
        Map<String, String> manifest = new HashMap<>();
        List<String> spine = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        String[] opf = {null};
        String[] nav = {null, null}; // EPUB 3 nav path, EPUB 2 NCX manifest id
        Map<String, String> items = new HashMap<>();
        StringBuilder name = new StringBuilder();
        StringBuilder creator = new StringBuilder();
        StringBuilder lang = new StringBuilder();
        try (ZipFile zip = new ZipFile(file)) {
            parse(readEntry(zip, "META-INF/container.xml"), new DefaultHandler2() {
                @Override public void startElement(String uri, String local, String q, Attributes a) {
                    if (local.equals("rootfile") && opf[0] == null)
                        opf[0] = a.getValue("full-path");
                }
            });
            if (opf[0] == null) throw new IOException("Missing EPUB package");
            opf[0] = resolve("", opf[0]);
            parse(readEntry(zip, opf[0]), new DefaultHandler2() {
                boolean inTitle, inAuthor, inLanguage, inManifest, inSpine;
                int spineIndex = 0;
                @Override public void startElement(String uri, String local, String q, Attributes a) {
                    if (local.equals("title") && name.length() == 0) inTitle = true;
                    if (local.equals("creator") && creator.length() == 0) inAuthor = true;
                    if (local.equals("language") && lang.length() == 0) inLanguage = true;
                    if (local.equals("manifest")) inManifest = true;
                    if (local.equals("spine")) { inSpine = true; nav[1] = a.getValue("toc"); }
                    if (inManifest && local.equals("item")) {
                        String type = a.getValue("media-type");
                        String id = a.getValue("id"), href = a.getValue("href");
                        if (id != null && href != null && ("application/xhtml+xml".equals(type) || "text/html".equals(type)))
                            manifest.put(id, resolve(opf[0], href));
                        if (id != null && href != null) {
                            try { items.put(id, resolve(opf[0], href)); } catch (IllegalArgumentException ignored) { }
                            String properties = a.getValue("properties");
                            if (properties != null && Arrays.asList(properties.split("\\s+")).contains("nav")) nav[0] = items.get(id);
                        }
                    }
                    if (inSpine && local.equals("itemref")) {
                        if (!"no".equals(a.getValue("linear"))) { spine.add(a.getValue("idref")); indices.add(spineIndex); }
                        spineIndex++;
                    }
                }
                @Override public void endElement(String uri, String local, String q) {
                    if (local.equals("title")) inTitle = false;
                    if (local.equals("creator")) inAuthor = false;
                    if (local.equals("language")) inLanguage = false;
                    if (local.equals("manifest")) inManifest = false;
                    if (local.equals("spine")) inSpine = false;
                }
                @Override public void characters(char[] ch, int start, int length) {
                    if (inTitle && name.length() < 512) name.append(ch, start, Math.min(length, 512 - name.length()));
                    if (inAuthor && creator.length() < 512) creator.append(ch, start, Math.min(length, 512 - creator.length()));
                    if (inLanguage && lang.length() < 35) lang.append(ch, start, Math.min(length, 35 - lang.length()));
                }
            });
            List<String> paths = new ArrayList<>();
            for (String id : spine) {
                String path = manifest.get(id);
                if (path == null || zip.getEntry(path) == null) throw new IOException("Missing or unsupported EPUB chapter");
                paths.add(path);
            }
            if (paths.isEmpty()) throw new IOException("No readable chapters");
            if (zip.getEntry("META-INF/encryption.xml") != null) {
                parse(readEntry(zip, "META-INF/encryption.xml"), new DefaultHandler2() {
                    @Override public void startElement(String uri, String local, String q, Attributes a) throws SAXException {
                        if (local.equals("CipherReference") && a.getValue("URI") != null &&
                                paths.contains(resolve("", a.getValue("URI"))))
                            throw new SAXException("DRM-protected EPUBs require an external reader");
                    }
                });
            }
            List<TocEntry> entries = new ArrayList<>();
            // A broken TOC must never stop the book from opening; chapter selection falls back to file names.
            try { if (nav[0] != null) parseNav(zip, nav[0], paths, entries); } catch (Exception e) { entries.clear(); }
            try { if (entries.isEmpty() && nav[1] != null && items.get(nav[1]) != null) parseNcx(zip, items.get(nav[1]), paths, entries); }
            catch (Exception e) { entries.clear(); }
            toc = Collections.unmodifiableList(entries);
            chapters = Collections.unmodifiableList(paths);
            spineIndices = Collections.unmodifiableList(indices);
        }
        author = creator.toString().trim();
        language = lang.toString().trim();
        title = name.toString().trim().isEmpty() ? file.getName() : name.toString().trim();
    }

    public String chapterHtml(int index) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            byte[] bytes = readEntry(zip, chapters.get(index));
            // EPUB content documents use UTF-8 or UTF-16, including BOM-marked UTF-16.
            String charset = bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) ||
                    (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff)) ? "UTF-16" : "UTF-8";
            return new String(bytes, charset);
        }
    }

    /** Raw bytes of a resource (image, footnote document) referenced from a chapter. */
    public byte[] resource(int chapter, String href) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            return readEntry(zip, resolve(chapters.get(chapter), href.contains("#") ? href.substring(0, href.indexOf('#')) : href));
        }
    }

    private static void addEntry(List<TocEntry> out, List<String> chapters, String base, String href, String title, int depth) {
        if (href == null || title.trim().isEmpty()) return;
        int hash = href.indexOf('#');
        String fragment = hash >= 0 ? href.substring(hash + 1) : "";
        try {
            int chapter = chapters.indexOf(resolve(base, hash >= 0 ? href.substring(0, hash) : href));
            if (chapter >= 0) out.add(new TocEntry(title.trim().replaceAll("\\s+", " "), chapter, Math.max(0, depth), fragment));
        } catch (IllegalArgumentException ignored) { }
    }

    private static void parseNav(ZipFile zip, String path, List<String> chapters, List<TocEntry> out) throws Exception {
        parse(readEntry(zip, path), new DefaultHandler2() {
            int navDepth = 0, listDepth = 0;
            boolean inToc, done, inLink;
            String href;
            final StringBuilder label = new StringBuilder();
            @Override public void startElement(String uri, String local, String q, Attributes a) {
                if (done) return;
                if (local.equals("nav")) {
                    navDepth++;
                    String type = a.getValue("http://www.idpf.org/2007/ops", "type");
                    if (!inToc && (type == null || Arrays.asList(type.split("\\s+")).contains("toc"))) { inToc = true; navDepth = 1; }
                }
                if (!inToc) return;
                if (local.equals("ol")) listDepth++;
                if (local.equals("a")) { inLink = true; href = a.getValue("href"); label.setLength(0); }
            }
            @Override public void endElement(String uri, String local, String q) {
                if (!inToc || done) return;
                if (local.equals("a") && inLink) { inLink = false; addEntry(out, chapters, path, href, label.toString(), listDepth - 1); }
                if (local.equals("ol")) listDepth--;
                if (local.equals("nav") && --navDepth == 0) done = true;
            }
            @Override public void characters(char[] ch, int start, int length) {
                if (inLink && label.length() < 512) label.append(ch, start, Math.min(length, 512 - label.length()));
            }
        });
    }

    private static void parseNcx(ZipFile zip, String path, List<String> chapters, List<TocEntry> out) throws Exception {
        parse(readEntry(zip, path), new DefaultHandler2() {
            int depth = -1;
            boolean inText;
            final StringBuilder label = new StringBuilder();
            @Override public void startElement(String uri, String local, String q, Attributes a) {
                if (local.equals("navPoint")) { depth++; label.setLength(0); }
                if (local.equals("text") && depth >= 0) inText = true;
                if (local.equals("content") && depth >= 0) addEntry(out, chapters, path, a.getValue("src"), label.toString(), depth);
            }
            @Override public void endElement(String uri, String local, String q) {
                if (local.equals("navPoint")) depth--;
                if (local.equals("text")) inText = false;
            }
            @Override public void characters(char[] ch, int start, int length) {
                if (inText && label.length() < 512) label.append(ch, start, Math.min(length, 512 - label.length()));
            }
        });
    }

    public static String resolve(String base, String href) {
        URI uri = URI.create(href.replace(" ", "%20"));
        if (uri.isAbsolute() || uri.getAuthority() != null) throw new IllegalArgumentException("External EPUB resource");
        String path = uri.getPath();
        if (path == null || path.startsWith("/") || path.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Invalid EPUB path");
        Deque<String> parts = new ArrayDeque<>();
        String parent = base.contains("/") ? base.substring(0, base.lastIndexOf('/') + 1) : "";
        for (String part : (parent + path).split("/")) {
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IllegalArgumentException("EPUB path escapes archive");
                parts.removeLast();
            } else if (!part.isEmpty() && !part.equals(".")) parts.addLast(part);
        }
        return String.join("/", parts);
    }

    public static byte[] readEntry(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) throw new IOException("Missing EPUB entry: " + path);
        if (entry.getSize() > MAX_ENTRY) throw new IOException("EPUB section exceeds the 1 MiB low-memory limit. Use Open with for this book.");
        try (InputStream in = zip.getInputStream(entry); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > MAX_ENTRY) throw new IOException("EPUB section is too large");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static void parse(byte[] xml, DefaultHandler2 handler) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        DefaultHandler2 guard = new DefaultHandler2() {
            @Override public void startDTD(String name, String publicId, String systemId) throws SAXException {
                throw new SAXException("DOCTYPE is not allowed in EPUB metadata");
            }
            @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
                throw new SAXException("External entities are not allowed");
            }
        };
        reader.setEntityResolver(guard);
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", guard);
        // XHTML nav documents usually carry <!DOCTYPE html>; drop a plain one, any DTD that remains is still rejected.
        boolean doctype = new String(xml, 0, Math.min(xml.length, 1024), "ISO-8859-1").contains("<!DOCTYPE");
        reader.parse(doctype ? new InputSource(new StringReader(new String(xml, "UTF-8")
                .replaceFirst("(?is)^\\uFEFF?(\\s*(<\\?xml[^>]*>)?\\s*)<!DOCTYPE[^>\\[]*>", "$1")))
                : new InputSource(new ByteArrayInputStream(xml)));
    }
}
