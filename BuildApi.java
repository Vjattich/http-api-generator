import java.io.IOException;
import javax.lang.model.SourceVersion;

private static final Pattern REQUEST = Pattern.compile("([A-Z]+)\\s+(.+?)(?:\\s+HTTP/\\S+)?");
private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");
private static final Pattern DECLARED_VARIABLE = Pattern.compile("@([\\w.-]+)\\s*=.*");
private static final Pattern INSTANCE_VARIABLE = Pattern.compile("@([\\w.-]+)\\s*=.*;\\s*instance");
private static final Pattern RESPONSE = Pattern.compile(
        "(?:#|//)?\\s*@response\\s*([A-Z][\\w]*)?\\(([^()]*)\\)");
private static final Pattern RESPONSE_COMPONENT = Pattern.compile(
        "[A-Za-z][\\w.]*(?:<[A-Za-z][\\w.]*>)?\\s+[A-Za-z_][\\w]*");

private record ResponseRecord(String name, String components) {
}

private record Endpoint(String title, String method, String url,
                        List<String> headers, String body, List<ResponseRecord> responses,
                        Map<String, Object> jsonResponse) {
}

private record SharedShape(String name, Map<String, Object> sample) {
}

private record EndpointFlags(boolean queryEncoder, boolean formBuffer, boolean jsonBuffer) {
}

void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2) {
        throw new IllegalArgumentException("Usage: java BuildApi.java <file.http> [output-directory]");
    }
    String input = args[0].startsWith("--") ? args[0].substring(2) : args[0];
    Path source = Path.of(input);
    String filename = source.getFileName().toString();
    if (!filename.endsWith(".http") || filename.length() == 5) {
        throw new IllegalArgumentException("Input must be a named .http file: " + source);
    }
    String stem = filename.substring(0, filename.length() - 5);
    String className = capitalize(SourceVersion.isIdentifier(stem) ? stem : identifier(stem)) + "Api";
    Path output = Path.of(args.length == 2 ? args[1] : ".").resolve(className + ".java");
    if (Files.exists(output) && Files.isSameFile(source, output)) {
        throw new IllegalArgumentException("Input and output must be different files");
    }
    var declaredVariables = new HashSet<String>();
    var instanceVariables = new HashSet<String>();
    var endpoints = parse(Files.readString(source), declaredVariables, instanceVariables);
    Files.writeString(output, generate(className, endpoints, declaredVariables, instanceVariables));
    IO.println(output);
}

private static List<Endpoint> parse(String source, Set<String> declaredVariables,
                                    Set<String> instanceVariables) {
    var endpoints = new ArrayList<Endpoint>();
    String[] sections = source.replaceFirst("^\\uFEFF", "").split("(?m)^###", -1);
    for (int section = 0; section < sections.length; section++) {
        String[] lines = sections[section].split("\\R", -1);
        String title = section == 0 ? "" : lines[0].strip();
        int index = section == 0 ? 0 : 1;
        var responses = new ArrayList<ResponseRecord>();
        Map<String, Object> jsonResponse = null;
        while (index < lines.length) {
            String line = lines[index].strip();
            var instanceVariable = INSTANCE_VARIABLE.matcher(line);
            var declaredVariable = DECLARED_VARIABLE.matcher(line);
            if (instanceVariable.matches()) {
                instanceVariables.add(instanceVariable.group(1));
                declaredVariables.add(instanceVariable.group(1));
            } else if (declaredVariable.matches()) {
                declaredVariables.add(declaredVariable.group(1));
            } else if (line.startsWith("={%")) {
                while (!lines[index].contains("%}")) {
                    if (++index == lines.length) {
                        throw new IllegalArgumentException("Unclosed shared HTTP script block");
                    }
                }
            } else if (line.matches("(?:#|//)?\\s*@response\\s*\\{.*")) {
                if (jsonResponse != null) {
                    throw new IllegalArgumentException("Duplicate JSON @response in section " + (section + 1));
                }
                jsonResponse = new ResponseExample(line.substring(line.indexOf('{'))).object();
            } else if (line.matches("(?:#|//)?\\s*@response\\b.*")) {
                var response = RESPONSE.matcher(line);
                if (!response.matches()) {
                    throw new IllegalArgumentException("Invalid @response in section " + (section + 1));
                }
                String components = response.group(2).strip();
                if (!components.isEmpty()) {
                    for (String component : components.split(",", -1)) {
                        if (!RESPONSE_COMPONENT.matcher(component.strip()).matches()) {
                            throw new IllegalArgumentException("Invalid @response component: " + component);
                        }
                    }
                }
                responses.add(new ResponseRecord(response.group(1), components));
            } else if (!isPreamble(line)) {
                break;
            }
            index++;
        }
        if (index == lines.length) {
            continue;
        }
        var request = REQUEST.matcher(lines[index++].strip());
        if (!request.matches()) {
            throw new IllegalArgumentException("Expected an HTTP request in section " + (endpoints.size() + 1));
        }
        var headers = new ArrayList<String>();
        while (index < lines.length && !lines[index].isBlank()) {
            if (isResponseHandler(lines[index])) {
                break;
            }
            String header = lines[index++].strip();
            if (header.startsWith("#") || header.startsWith("//")) {
                continue;
            }
            if (header.indexOf(':') < 1) {
                throw new IllegalArgumentException("Invalid header in request " + (endpoints.size() + 1));
            }
            headers.add(header);
        }
        var body = new StringBuilder();
        if (index < lines.length && lines[index].isBlank()) {
            index++;
        }
        for (; index < lines.length && !isResponseHandler(lines[index]); index++) {
            body.append(lines[index]).append('\n');
        }
        if (jsonResponse != null && !responses.isEmpty()) {
            throw new IllegalArgumentException("Use a single @response format in section " + (section + 1));
        }
        endpoints.add(new Endpoint(title, request.group(1), request.group(2), headers,
                body.toString().strip(), responses, jsonResponse));
    }
    if (endpoints.isEmpty()) {
        throw new IllegalArgumentException("No HTTP requests found");
    }
    return endpoints;
}

private static boolean isPreamble(String line) {
    return line.isEmpty() || line.startsWith("@") || line.startsWith("#") || line.startsWith("//");
}

private static boolean isResponseHandler(String line) {
    return line.stripLeading().startsWith("> ");
}

private static String generate(String className, List<Endpoint> endpoints,
                               Set<String> declaredVariables,
                               Set<String> instanceVariables) throws IOException {
    var body = new StringBuilder();
    var instanceUris = new LinkedHashMap<String, String>();
    var ctorInits = new StringBuilder();
    var names = new HashSet<>(Set.of("variable", "encode", "headersOf", "isUrlSafe",
            "buildQueryUrl", "appendQueryValue", "appendQueryFrom", "appendQueryByte",
            "hashCode", "toString", "clone", "finalize", "getClass", "notify", "notifyAll", "wait"));
    var typeNames = new HashSet<>(Set.of(className, "ResponseJson", "FormBuffer", "JsonBuffer",
            "FastRequest", "JsonBodySubscriber", "ParseFailure", "BodySub",
            "List", "Map", "Object", "String", "Long", "Boolean", "BigDecimal"));

    var sharedShapes = collectSharedShapes(endpoints, typeNames);
    var sharedTypeNames = new LinkedHashMap<String, String>();
    sharedShapes.forEach((sig, shape) -> sharedTypeNames.put(sig, shape.name()));

    boolean needsQueryEncoder = false;
    boolean needsFormBuffer = false;
    boolean needsJsonBuffer = false;
    for (Endpoint endpoint : endpoints) {
        var flags = appendEndpoint(body, endpoint, names, typeNames, declaredVariables,
                instanceVariables, instanceUris, ctorInits, sharedTypeNames);
        needsQueryEncoder |= flags.queryEncoder();
        needsFormBuffer |= flags.formBuffer();
        needsJsonBuffer |= flags.jsonBuffer();
    }
    for (SharedShape shape : sharedShapes.values()) {
        appendJsonResponse(body, shape.name(), shape.sample(), sharedTypeNames, false);
    }

    boolean typedResponses = endpoints.stream().anyMatch(endpoint -> endpoint.jsonResponse() != null);
    var java = new StringBuilder("""
            import java.io.IOException;
            import java.net.URI;
            import java.net.URLEncoder;
            import java.net.http.HttpClient;
            import java.net.http.HttpHeaders;
            import java.net.http.HttpRequest;
            import java.net.http.HttpResponse;
            import java.nio.charset.StandardCharsets;
            import java.time.Duration;
            import java.util.ArrayList;
            import java.util.Arrays;
            import java.util.Collections;
            import java.util.List;
            import java.util.Map;
            import java.util.Objects;
            import java.util.Optional;
            import java.util.TreeMap;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.CompletionException;
            """);
    if (typedResponses) {
        java.append("""
                import java.nio.ByteBuffer;
                import java.util.concurrent.CompletionStage;
                import java.util.concurrent.Flow;
                """);
    }
    java.append("\npublic final class ").append(className).append(" {\n");
    java.append("""
                private static final HttpHeaders EMPTY_HEADERS = HttpHeaders.of(Map.of(), (k, v) -> true);
                private final HttpClient client;
                private final Map<String, String> variables;
            
            """);
    java.append("    public ").append(className).append("(Map<String, String> variables) {\n");
    java.append("        this(HttpClient.newHttpClient(), variables);\n    }\n\n");
    java.append("    public ").append(className).append("(HttpClient client, Map<String, String> variables) {\n");
    java.append("""
                    this.client = Objects.requireNonNull(client);
                    this.variables = Map.copyOf(variables);
            """);
    instanceUris.forEach((url, field) -> java.append("        this.").append(field)
            .append(" = URI.create(").append(template(url, true)).append(");\n"));
    java.append(ctorInits);
    java.append("    }\n");
    java.append(body);
    java.append("""

                private static final class FastRequest extends HttpRequest {
                    static final Optional<BodyPublisher> NO_BODY = Optional.of(BodyPublishers.noBody());
                    private final URI uri;
                    private final String method;
                    private final HttpHeaders headers;
                    private final Optional<BodyPublisher> body;

                    FastRequest(URI uri, String method, HttpHeaders headers, Optional<BodyPublisher> body) {
                        this.uri = uri;
                        this.method = method;
                        this.headers = headers;
                        this.body = body;
                    }

                    @Override public Optional<BodyPublisher> bodyPublisher() { return body; }
                    @Override public String method() { return method; }
                    @Override public Optional<Duration> timeout() { return Optional.empty(); }
                    @Override public boolean expectContinue() { return false; }
                    @Override public URI uri() { return uri; }
                    @Override public Optional<HttpClient.Version> version() { return Optional.empty(); }
                    @Override public HttpHeaders headers() { return headers; }
                }

                private static HttpHeaders headersOf(String... pairs) {
                    var map = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
                    for (int i = 0; i < pairs.length; i += 2) {
                        map.computeIfAbsent(pairs[i], ignored -> new ArrayList<>(1)).add(pairs[i + 1]);
                    }
                    return HttpHeaders.of(map, (k, v) -> true);
                }
            """);
    if (typedResponses) {
        java.append("""

                private record ParseFailure(RuntimeException error) {}

                private abstract static class JsonBodySubscriber extends CompletableFuture<Object>
                        implements HttpResponse.BodySubscriber<Object> {
                    private static final byte[] EMPTY = new byte[0];
                    private final HttpHeaders headers;
                    private ByteBuffer single;
                    private byte[] buf;
                    private int len;

                    JsonBodySubscriber(HttpHeaders headers) {
                        this.headers = headers;
                    }

                    @Override
                    public final CompletionStage<Object> getBody() {
                        return this;
                    }

                    @Override
                    public final void onSubscribe(Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public final void onNext(List<ByteBuffer> items) {
                        int size = items.size();
                        for (int i = 0; i < size; i++) {
                            ByteBuffer bb = items.get(i);
                            int rem = bb.remaining();
                            if (rem == 0) continue;
                            if (buf == null && single == null && bb.hasArray()) {
                                single = bb;
                                continue;
                            }
                            append(bb, rem);
                        }
                    }

                    private void append(ByteBuffer bb, int rem) {
                        if (buf == null) {
                            int singleRem = single != null ? single.remaining() : 0;
                            int initial = Math.max(256, singleRem + rem);
                            long cl = headers != null ? headers.firstValueAsLong("content-length").orElse(-1L) : -1L;
                            if (cl > initial && cl <= 16_777_216L) {
                                initial = (int) cl;
                            }
                            buf = new byte[initial];
                            if (single != null) {
                                single.get(buf, 0, singleRem);
                                len = singleRem;
                                single = null;
                            }
                        } else if (len + rem > buf.length) {
                            buf = Arrays.copyOf(buf, Math.max(buf.length << 1, len + rem));
                        }
                        bb.get(buf, len, rem);
                        len += rem;
                    }

                    @Override
                    public final void onError(Throwable throwable) {
                        completeExceptionally(throwable);
                    }

                    final ResponseJson toJson() {
                        if (buf != null) {
                            return new ResponseJson(buf, 0, len);
                        }
                        ByteBuffer s = single;
                        if (s != null) {
                            int start = s.arrayOffset() + s.position();
                            return new ResponseJson(s.array(), start, start + s.remaining());
                        }
                        return new ResponseJson(EMPTY, 0, 0);
                    }
                }
            """);
        java.append(responseParser());
    }
    if (needsQueryEncoder || needsFormBuffer) {
        java.append("""

                private static final long SAFE_LO = (0x3FFL << '0') | (1L << '-') | (1L << '.') | (1L << '*');
                private static final long SAFE_HI = (0x3FFFFFFL << ('A' - 64))
                        | (0x3FFFFFFL << ('a' - 64)) | (1L << ('_' - 64));

                private static boolean isUrlSafe(char c) {
                    return c < 64 ? ((SAFE_LO >>> c) & 1L) != 0
                            : c < 128 && ((SAFE_HI >>> (c - 64)) & 1L) != 0;
                }
            """);
    }
    if (needsFormBuffer) {
        java.append("""

                private static final class FormBuffer {
                    private static final byte[] HEX = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
                    private byte[] buf;
                    private int len;

                    FormBuffer(int capacity) {
                        this.buf = new byte[Math.max(16, capacity)];
                    }

                    int length() {
                        return len;
                    }

                    void appendByte(int b) {
                        if (len == buf.length) buf = Arrays.copyOf(buf, buf.length << 1);
                        buf[len++] = (byte) b;
                    }

                    void appendAscii(String s) {
                        int n = s.length();
                        ensure(n);
                        byte[] b = buf;
                        int p = len;
                        for (int i = 0; i < n; i++) {
                            b[p++] = (byte) s.charAt(i);
                        }
                        len = p;
                    }

                    void appendUtf8(String s) {
                        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                        ensure(bytes.length);
                        System.arraycopy(bytes, 0, buf, len, bytes.length);
                        len += bytes.length;
                    }

                    void appendValue(String value) {
                        int n = value.length();
                        ensure(n);
                        byte[] b = buf;
                        int p = len;
                        int cap = b.length;
                        for (int i = 0; i < n; i++) {
                            char c = value.charAt(i);
                            if (isUrlSafe(c)) {
                                if (p == cap) { len = p; ensure(n - i); b = buf; cap = b.length; }
                                b[p++] = (byte) c;
                            } else if (c == ' ') {
                                if (p == cap) { len = p; ensure(n - i); b = buf; cap = b.length; }
                                b[p++] = '+';
                            } else if (c < 0x80) {
                                if (p + 3 > cap) { len = p; ensure((n - i) + 3); b = buf; cap = b.length; }
                                b[p++] = '%';
                                b[p++] = HEX[c >> 4];
                                b[p++] = HEX[c & 15];
                            } else {
                                len = p;
                                i = appendUtf8Percent(value, i, c);
                                p = len;
                                b = buf;
                                cap = b.length;
                            }
                        }
                        len = p;
                    }

                    private int appendUtf8Percent(String value, int i, char c) {
                        int cp = value.codePointAt(i);
                        if (Character.isSupplementaryCodePoint(cp)) {
                            appendPercent(0xf0 | (cp >> 18));
                            appendPercent(0x80 | ((cp >> 12) & 63));
                            appendPercent(0x80 | ((cp >> 6) & 63));
                            appendPercent(0x80 | (cp & 63));
                            return i + 1;
                        } else if (Character.isSurrogate(c)) {
                            appendPercent('?');
                        } else if (cp >= 0x800) {
                            appendPercent(0xe0 | (cp >> 12));
                            appendPercent(0x80 | ((cp >> 6) & 63));
                            appendPercent(0x80 | (cp & 63));
                        } else {
                            appendPercent(0xc0 | (cp >> 6));
                            appendPercent(0x80 | (cp & 63));
                        }
                        return i;
                    }

                    private void appendPercent(int b) {
                        ensure(3);
                        buf[len++] = '%';
                        buf[len++] = HEX[(b >> 4) & 15];
                        buf[len++] = HEX[b & 15];
                    }

                    private void ensure(int extra) {
                        int required = len + extra;
                        if (required > buf.length) {
                            buf = Arrays.copyOf(buf, Math.max(buf.length << 1, required));
                        }
                    }

                    HttpRequest.BodyPublisher toPublisher() {
                        return HttpRequest.BodyPublishers.ofByteArray(buf, 0, len);
                    }
                }
            """);
    }
    if (needsJsonBuffer) {
        java.append("""

                private static final class JsonBuffer {
                    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
                    private byte[] buf;
                    private int len;

                    JsonBuffer(int capacity) {
                        this.buf = new byte[Math.max(32, capacity)];
                    }

                    void appendByte(int b) {
                        if (len == buf.length) buf = Arrays.copyOf(buf, buf.length << 1);
                        buf[len++] = (byte) b;
                    }

                    void appendAscii(String s) {
                        int n = s.length();
                        ensure(n);
                        byte[] b = buf;
                        int p = len;
                        for (int i = 0; i < n; i++) {
                            b[p++] = (byte) s.charAt(i);
                        }
                        len = p;
                    }

                    void appendUtf8(String s) {
                        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                        ensure(bytes.length);
                        System.arraycopy(bytes, 0, buf, len, bytes.length);
                        len += bytes.length;
                    }

                    void appendJsonRaw(String value) {
                        if (value == null) {
                            appendAscii("null");
                        } else {
                            appendUtf8(value);
                        }
                    }

                    void appendJsonString(String value) {
                        if (value == null) {
                            appendAscii("null");
                            return;
                        }
                        appendByte('"');
                        appendJsonStringContent(value);
                        appendByte('"');
                    }

                    void appendJsonStringContent(String value) {
                        int n = value.length();
                        ensure(n);
                        for (int i = 0; i < n; i++) {
                            char c = value.charAt(i);
                            if (c >= 0x20 && c < 0x7F && c != '"' && c != '\\\\') {
                                appendByte(c);
                            } else if (c == '"' || c == '\\\\') {
                                ensure(2);
                                buf[len++] = '\\\\';
                                buf[len++] = (byte) c;
                            } else if (c == '\\n') {
                                ensure(2);
                                buf[len++] = '\\\\';
                                buf[len++] = 'n';
                            } else if (c == '\\r') {
                                ensure(2);
                                buf[len++] = '\\\\';
                                buf[len++] = 'r';
                            } else if (c == '\\t') {
                                ensure(2);
                                buf[len++] = '\\\\';
                                buf[len++] = 't';
                            } else if (c == '\\b') {
                                ensure(2);
                                buf[len++] = '\\\\';
                                buf[len++] = 'b';
                            } else if (c == '\\f') {
                                ensure(2);
                                buf[len++] = '\\\\';
                                buf[len++] = 'f';
                            } else if (c < 0x20) {
                                ensure(6);
                                buf[len++] = '\\\\';
                                buf[len++] = 'u';
                                buf[len++] = '0';
                                buf[len++] = '0';
                                buf[len++] = HEX[(c >> 4) & 15];
                                buf[len++] = HEX[c & 15];
                            } else {
                                int cp = value.codePointAt(i);
                                if (Character.isSupplementaryCodePoint(cp)) {
                                    i++;
                                    ensure(4);
                                    buf[len++] = (byte) (0xf0 | (cp >> 18));
                                    buf[len++] = (byte) (0x80 | ((cp >> 12) & 63));
                                    buf[len++] = (byte) (0x80 | ((cp >> 6) & 63));
                                    buf[len++] = (byte) (0x80 | (cp & 63));
                                } else if (cp >= 0x800) {
                                    ensure(3);
                                    buf[len++] = (byte) (0xe0 | (cp >> 12));
                                    buf[len++] = (byte) (0x80 | ((cp >> 6) & 63));
                                    buf[len++] = (byte) (0x80 | (cp & 63));
                                } else {
                                    ensure(2);
                                    buf[len++] = (byte) (0xc0 | (cp >> 6));
                                    buf[len++] = (byte) (0x80 | (cp & 63));
                                }
                            }
                        }
                    }

                    private void ensure(int extra) {
                        int required = len + extra;
                        if (required > buf.length) {
                            buf = Arrays.copyOf(buf, Math.max(buf.length << 1, required));
                        }
                    }

                    HttpRequest.BodyPublisher toPublisher() {
                        return HttpRequest.BodyPublishers.ofByteArray(buf, 0, len);
                    }
                }
            """);
    }
    if (needsQueryEncoder) {
        java.append("""

                private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

                private static String buildQueryUrl(String prefix, String value) {
                    int n = value.length();
                    for (int i = 0; i < n; i++) {
                        if (!isUrlSafe(value.charAt(i))) {
                            var out = new StringBuilder(prefix.length() + (n << 1) + 16).append(prefix);
                            if (i > 0) out.append(value, 0, i);
                            appendQueryFrom(out, value, i, prefix.length());
                            return out.toString();
                        }
                    }
                    return prefix + value;
                }

                private static String buildQueryUrl(String prefix, String value, String suffix) {
                    int n = value.length();
                    for (int i = 0; i < n; i++) {
                        if (!isUrlSafe(value.charAt(i))) {
                            var out = new StringBuilder(prefix.length() + (n << 1) + suffix.length() + 16).append(prefix);
                            if (i > 0) out.append(value, 0, i);
                            appendQueryFrom(out, value, i, prefix.length());
                            return out.append(suffix).toString();
                        }
                    }
                    return prefix + value + suffix;
                }

                private static void appendQueryValue(StringBuilder url, String value) {
                    int n = value.length();
                    for (int i = 0; i < n; i++) {
                        if (!isUrlSafe(value.charAt(i))) {
                            int valueStart = url.length();
                            if (i > 0) url.append(value, 0, i);
                            appendQueryFrom(url, value, i, valueStart);
                            return;
                        }
                    }
                    url.append(value);
                }

                private static void appendQueryFrom(StringBuilder url, String value, int startIndex, int valueStart) {
                    int start = startIndex;
                    int n = value.length();
                    for (int i = startIndex; i < n; i++) {
                        char c = value.charAt(i);
                        if (isUrlSafe(c)) continue;
                        if (i > start) url.append(value, start, i);
                        if (c == ' ') {
                            url.append("%20");
                        } else {
                            int cp = value.codePointAt(i);
                            if (Character.isSupplementaryCodePoint(cp)) {
                                i++;
                                appendQueryByte(url, 0xf0 | cp >> 18);
                                appendQueryByte(url, 0x80 | (cp >> 12 & 63));
                                appendQueryByte(url, 0x80 | (cp >> 6 & 63));
                            } else if (Character.isSurrogate(c)) {
                                url.setLength(valueStart);
                                url.append(encode(value).replace("+", "%20"));
                                return;
                            } else if (cp >= 0x800) {
                                appendQueryByte(url, 0xe0 | cp >> 12);
                                appendQueryByte(url, 0x80 | (cp >> 6 & 63));
                            } else if (cp >= 0x80) {
                                appendQueryByte(url, 0xc0 | cp >> 6);
                            }
                            appendQueryByte(url, cp < 128 ? cp : 0x80 | (cp & 63));
                        }
                        start = i + 1;
                    }
                    if (start < n) url.append(value, start, n);
                }

                private static void appendQueryByte(StringBuilder url, int b) {
                    url.append('%').append(HEX_CHARS[b >> 4]).append(HEX_CHARS[b & 15]);
                }
            """);
    }
    return java.append("""
            
                private String variable(String name) {
                    String value = variables.get(name);
                    if (value == null) {
                        throw new IllegalArgumentException("Missing HTTP variable: " + name);
                    }
                    return value;
                }
            
                private static String encode(String value) {
                    return URLEncoder.encode(value, StandardCharsets.UTF_8);
                }
            }
            """).toString();
}

private static Map<String, SharedShape> collectSharedShapes(List<Endpoint> endpoints, Set<String> typeNames) {
    var counts = new LinkedHashMap<String, Integer>();
    var labels = new LinkedHashMap<String, String>();
    var samples = new LinkedHashMap<String, Map<String, Object>>();
    for (Endpoint endpoint : endpoints) {
        if (endpoint.jsonResponse() == null) continue;
        endpoint.jsonResponse().forEach((key, value) ->
                scanNestedShapes(fieldLabel(key), value, counts, labels, samples));
    }
    var shared = new LinkedHashMap<String, SharedShape>();
    counts.forEach((sig, count) -> {
        if (count > 1) {
            String name = unique(labels.get(sig), typeNames);
            shared.put(sig, new SharedShape(name, samples.get(sig)));
        }
    });
    return shared;
}

private static void scanNestedShapes(String label, Object sample,
                                     Map<String, Integer> counts,
                                     Map<String, String> labels,
                                     Map<String, Map<String, Object>> samples) {
    switch (sample) {
        case Map<?, ?> raw -> {
            Map<String, Object> object = ResponseExample.fields(raw);
            String sig = shapeSignature(object);
            counts.merge(sig, 1, Integer::sum);
            labels.putIfAbsent(sig, label);
            samples.putIfAbsent(sig, object);
            object.forEach((key, value) -> scanNestedShapes(fieldLabel(key), value, counts, labels, samples));
        }
        case List<?> list -> {
            if (list.size() == 1 && list.getFirst() != null) {
                scanNestedShapes(label, list.getFirst(), counts, labels, samples);
            }
        }
        default -> {
        }
    }
}

private static String shapeSignature(Object sample) {
    return switch (sample) {
        case Map<?, ?> raw -> {
            Map<String, Object> object = ResponseExample.fields(raw);
            var sb = new StringBuilder("{");
            object.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(e.getKey()).append(':').append(shapeSignature(e.getValue())).append(','));
            yield sb.append('}').toString();
        }
        case List<?> list -> list.size() == 1 && list.getFirst() != null
                ? "[" + shapeSignature(list.getFirst()) + "]" : "[]";
        case String ignored -> "S";
        case Boolean ignored -> "B";
        case java.math.BigDecimal n -> n.scale() <= 0 && n.precision() - n.scale() < 19 ? "L" : "D";
        case null -> "O";
        default -> "?";
    };
}

private static String fieldLabel(String key) {
    return capitalize(identifier(key.replaceAll("([a-z0-9])([A-Z])", "$1 $2")));
}

private static int authorityEnd(String value) {
    int end = value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")
            ? value.indexOf("://") + 3 : 0;
    while (end > 0 && end < value.length() && "/?#".indexOf(value.charAt(end)) < 0) {
        end++;
    }
    return end;
}

private static EndpointFlags appendEndpoint(StringBuilder java, Endpoint endpoint, Set<String> names,
                                            Set<String> typeNames, Set<String> declaredVariables,
                                            Set<String> instanceVariables,
                                            Map<String, String> instanceUris,
                                            StringBuilder ctorInits,
                                            Map<String, String> sharedTypeNames) {
    String label = endpoint.title().isBlank() ? endpoint.method() + " " + endpoint.url() : endpoint.title();
    String methodName = unique(identifier(label), names);
    String asyncMethodName = unique(methodName + "Async", names);
    String buildHelperName = unique("_build" + capitalize(methodName), names);
    String parseHelperName = unique("_parse" + capitalize(methodName), names);
    String constPrefix = methodName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    String recordName = unique(capitalize(methodName) + "Request", typeNames);
    boolean form = false;
    boolean jsonBody = false;
    boolean anyHeaderVariable = false;
    boolean allHeaderVarsInstance = true;
    var headerPairs = new ArrayList<String>();
    for (String header : endpoint.headers()) {
        int colon = header.indexOf(':');
        String name = header.substring(0, colon).strip();
        String value = header.substring(colon + 1).strip();
        if (name.equalsIgnoreCase("Content-Type")) {
            String mediaType = value.split(";", 2)[0].strip();
            form = mediaType.equalsIgnoreCase("application/x-www-form-urlencoded");
            jsonBody = mediaType.equalsIgnoreCase("application/json");
        }
        if (VARIABLE.matcher(value).find()) {
            anyHeaderVariable = true;
            allHeaderVarsInstance &= VARIABLE.matcher(value).results()
                    .allMatch(m -> instanceVariables.contains(m.group(1)));
        }
        headerPairs.add(literal(name));
        headerPairs.add(template(value, false));
    }

    String headersExpr;
    String lazyHeadersField = null;
    String headersInitExpr = headerPairs.isEmpty() ? "EMPTY_HEADERS"
            : "headersOf(" + String.join(", ", headerPairs) + ")";
    if (headerPairs.isEmpty()) {
        headersExpr = "EMPTY_HEADERS";
    } else if (!anyHeaderVariable) {
        String field = constPrefix + "_HEADERS";
        java.append("\n    private static final HttpHeaders ").append(field)
                .append(" = ").append(headersInitExpr).append(";\n");
        headersExpr = field;
    } else if (allHeaderVarsInstance) {
        String field = methodName + "Headers";
        java.append("\n    private final HttpHeaders ").append(field).append(";\n");
        ctorInits.append("        this.").append(field).append(" = ").append(headersInitExpr).append(";\n");
        headersExpr = field;
    } else {
        lazyHeadersField = methodName + "Headers";
        java.append("\n    private volatile HttpHeaders ").append(lazyHeadersField).append(";\n");
        headersExpr = "_hdrs";
    }

    var params = new ArrayList<String>();
    var paramList = new ArrayList<String>();
    var fieldParameters = new LinkedHashMap<String, String>();
    var pathParamSet = new LinkedHashSet<String>();
    var queryParamSet = new LinkedHashSet<String>();
    var formParamSet = new LinkedHashSet<String>();
    var rawQueryFields = new ArrayList<String>();
    var queryFields = new ArrayList<String>();
    var fixedQueryFields = new ArrayList<String>();
    var formFields = new ArrayList<String>();
    var jsonBodyWrites = new ArrayList<String>();
    var paramNames = new HashSet<>(Set.of("clone", "finalize", "getClass", "hashCode",
            "notify", "notifyAll", "toString", "wait",
            "_req", "_res", "_body", "_bytes", "_uri", "_reqUri", "_base", "_baseLen", "_url",
            "_form", "_jbuf", "_json", "_result", "_hdrs"));
    String url = endpoint.url();
    int fragmentIndex = url.indexOf('#');
    String fragment = fragmentIndex < 0 ? "" : url.substring(fragmentIndex);
    String urlWithoutFragment = fragmentIndex < 0 ? url : url.substring(0, fragmentIndex);
    int queryIndex = urlWithoutFragment.indexOf('?');
    String baseUrl = queryIndex >= 0 ? urlWithoutFragment.substring(0, queryIndex) : urlWithoutFragment;
    if (queryIndex < 0 && fragmentIndex >= 0) {
        baseUrl = url;
    }

    int authEnd = authorityEnd(baseUrl);
    int pathParamOccurrences = 0;
    int singlePathStart = -1;
    int singlePathEnd = -1;
    String singlePathParam = null;
    boolean baseHasConfigVar = false;
    boolean allBaseConfigVarsInstance = true;
    var pathMatcher = VARIABLE.matcher(baseUrl);
    while (pathMatcher.find()) {
        String varName = pathMatcher.group(1);
        boolean inPath = pathMatcher.start() >= authEnd && (authEnd > 0 || pathMatcher.start() > 0);
        if (inPath && !declaredVariables.contains(varName)) {
            String param = registerParam(varName, params, paramList, paramNames, fieldParameters);
            pathParamSet.add(param);
            pathParamOccurrences++;
            singlePathStart = pathMatcher.start();
            singlePathEnd = pathMatcher.end();
            singlePathParam = param;
        } else {
            baseHasConfigVar = true;
            allBaseConfigVarsInstance &= instanceVariables.contains(varName);
        }
    }
    boolean hasPathParams = !pathParamSet.isEmpty();

    boolean mayHaveEmptyField = false;
    int staticQueryChars = 0;
    if (queryIndex >= 0) {
        for (String field : urlWithoutFragment.substring(queryIndex + 1).split("&")) {
            if (field.isEmpty()) {
                continue;
            }
            rawQueryFields.add(field);
            queryFields.add(queryField(field, params, paramList, paramNames, fieldParameters, queryParamSet));
            if (!VARIABLE.matcher(field).find()) fixedQueryFields.add(field);
            String stripped = VARIABLE.matcher(field).replaceAll("");
            staticQueryChars += stripped.length() + 1;
            mayHaveEmptyField |= stripped.isEmpty();
        }
        if (queryFields.isEmpty() && !hasPathParams) {
            baseUrl = url;
        }
    }
    var queryParameters = List.copyOf(queryParamSet);
    String bodyOptionalExpr = "FastRequest.NO_BODY";
    boolean usesFormBuffer = false;
    boolean usesJsonBuffer = false;
    int staticFormChars = 0;
    String staticBodyField = null;
    if (form && !endpoint.body().isEmpty()) {
        if (!VARIABLE.matcher(endpoint.body()).find()) {
            staticBodyField = constPrefix + "_BODY";
            bodyOptionalExpr = staticBodyField;
        } else {
            usesFormBuffer = true;
            for (String field : endpoint.body().split("&", -1)) {
                formFields.add(formField(field, params, paramList, paramNames, fieldParameters, formParamSet));
                staticFormChars += VARIABLE.matcher(field).replaceAll("").length() + 1;
            }
            bodyOptionalExpr = "Optional.of(_form.toPublisher())";
        }
    } else if (jsonBody && !endpoint.body().isEmpty() && VARIABLE.matcher(endpoint.body()).find()) {
        usesJsonBuffer = true;
        compileJsonBody(endpoint.body(), jsonBodyWrites, params, paramList, paramNames, fieldParameters);
        bodyOptionalExpr = "Optional.of(_jbuf.toPublisher())";
    } else if (!endpoint.body().isEmpty()) {
        String parameter = unique("body", paramNames);
        params.add("String " + parameter);
        paramList.add(parameter);
        bodyOptionalExpr = "Optional.of(HttpRequest.BodyPublishers.ofString(" + parameter + "))";
    }
    boolean typedResponse = endpoint.jsonResponse() != null;
    boolean staticOrNoBody = endpoint.body().isEmpty() || staticBodyField != null;
    boolean reusableRequest = params.isEmpty() && staticOrNoBody;
    String responseType = endpoint.responses().isEmpty() && !typedResponse ? null
            : unique(capitalize(methodName) + "Response", typeNames);
    boolean variableUrl = !hasPathParams && baseHasConfigVar;
    boolean instanceUrl = variableUrl && allBaseConfigVarsInstance;
    String uriField = constPrefix + "_URI";
    String uriExpression = "URI.create(" + template(baseUrl, true) + ")";
    if (!hasPathParams) {
        if (instanceUrl) {
            uriField = instanceUris.get(baseUrl);
            if (uriField == null) {
                uriField = methodName + "Uri";
                instanceUris.put(baseUrl, uriField);
                java.append("\n    private final URI ").append(uriField).append(";\n");
            }
        } else if (variableUrl) {
            if (reusableRequest) {
                uriField = uriExpression;
            } else {
                uriField = methodName + "Uri";
                java.append("\n    private volatile URI ").append(uriField).append(";\n");
            }
        } else {
            java.append("\n    private static final URI ").append(uriField)
                    .append(" = ").append(uriExpression).append(";\n");
        }
    }
    if (staticBodyField != null) {
        java.append("\n    private static final Optional<HttpRequest.BodyPublisher> ").append(staticBodyField)
                .append(" = Optional.of(HttpRequest.BodyPublishers.ofByteArray(")
                .append(literal(endpoint.body())).append(".getBytes(StandardCharsets.UTF_8)));\n");
    }
    java.append("\n    public record ").append(recordName).append("(")
            .append(String.join(", ", params)).append(") {}\n");
    if (typedResponse) {
        appendJsonResponse(java, responseType, endpoint.jsonResponse(), sharedTypeNames, true);
    } else if (responseType != null) {
        appendResponse(java, endpoint.responses(), responseType, methodName);
    }

    String defaultRequestExpr = null;
    String fallback = !hasPathParams && !queryFields.isEmpty() && !queryParameters.isEmpty()
                      && !variableUrl && !VARIABLE.matcher(fragment).find()
            ? appendDefaultUri(java, uriField, baseUrl, fixedQueryFields, fragment) : null;
    if (fallback != null && staticOrNoBody && !usesFormBuffer && !usesJsonBuffer) {
        if (!anyHeaderVariable) {
            String field = constPrefix + "_DEFAULT_REQUEST";
            java.append("\n    private static final HttpRequest ").append(field)
                    .append(" = new FastRequest(").append(fallback).append(", ")
                    .append(literal(endpoint.method())).append(", ").append(headersExpr)
                    .append(", ").append(bodyOptionalExpr).append(");\n");
            defaultRequestExpr = field;
        } else if (allHeaderVarsInstance) {
            String field = methodName + "DefaultRequest";
            java.append("\n    private final HttpRequest ").append(field).append(";\n");
            ctorInits.append("        this.").append(field).append(" = new FastRequest(")
                    .append(fallback).append(", ").append(literal(endpoint.method())).append(", ")
                    .append(headersExpr).append(", ").append(bodyOptionalExpr).append(");\n");
            defaultRequestExpr = field;
        }
    }

    String singleQueryPrefixField = null;
    String singleQuerySuffix = "";
    String singleQueryParam = null;
    if (!hasPathParams && !queryFields.isEmpty() && !variableUrl && !VARIABLE.matcher(fragment).find()
        && !mayHaveEmptyField && queryParameters.size() == 1
        && rawQueryFields.size() - fixedQueryFields.size() == 1) {
        int dynIdx = -1;
        for (int i = 0; i < rawQueryFields.size(); i++) {
            if (VARIABLE.matcher(rawQueryFields.get(i)).find()) {
                dynIdx = i;
                break;
            }
        }
        String dynField = rawQueryFields.get(dynIdx);
        var m = VARIABLE.matcher(dynField);
        if (m.find() && !m.find(m.end())) {
            m.reset();
            m.find();
            var before = new StringBuilder(baseUrl).append('?');
            for (int i = 0; i < dynIdx; i++) {
                before.append(rawQueryFields.get(i)).append('&');
            }
            before.append(dynField, 0, m.start());
            var after = new StringBuilder(dynField.substring(m.end()));
            for (int i = dynIdx + 1; i < rawQueryFields.size(); i++) {
                after.append('&').append(rawQueryFields.get(i));
            }
            after.append(fragment);
            singleQueryPrefixField = constPrefix + "_QUERY_PREFIX";
            singleQuerySuffix = after.toString();
            singleQueryParam = queryParameters.getFirst();
            java.append("\n    private static final String ").append(singleQueryPrefixField)
                    .append(" = ").append(literal(before.toString())).append(";\n");
        }
    }

    String singlePathPrefixField = null;
    String singlePathSuffix = "";
    if (hasPathParams && queryFields.isEmpty() && fragment.isEmpty()
        && pathParamOccurrences == 1 && allBaseConfigVarsInstance) {
        String prefixRaw = baseUrl.substring(0, singlePathStart);
        String suffixRaw = baseUrl.substring(singlePathEnd);
        if (!VARIABLE.matcher(suffixRaw).find()) {
            singlePathSuffix = suffixRaw;
            if (!VARIABLE.matcher(prefixRaw).find()) {
                singlePathPrefixField = constPrefix + "_PATH_PREFIX";
                java.append("\n    private static final String ").append(singlePathPrefixField)
                        .append(" = ").append(literal(prefixRaw)).append(";\n");
            } else {
                singlePathPrefixField = methodName + "PathPrefix";
                java.append("\n    private final String ").append(singlePathPrefixField).append(";\n");
                ctorInits.append("        this.").append(singlePathPrefixField).append(" = ")
                        .append(template(prefixRaw, true)).append(";\n");
            }
        }
    }

    StringBuilder requestCode = new StringBuilder();
    if (lazyHeadersField != null) {
        requestCode.append("        HttpHeaders _hdrs = ").append(lazyHeadersField).append(";\n")
                .append("        if (_hdrs == null) {\n")
                .append("            _hdrs = ").append(headersInitExpr).append(";\n")
                .append("            ").append(lazyHeadersField).append(" = _hdrs;\n")
                .append("        }\n");
    }
    String requestUri = uriField;
    if (hasPathParams) {
        if (singlePathPrefixField != null) {
            String urlExpr = singlePathSuffix.isEmpty()
                    ? "buildQueryUrl(" + singlePathPrefixField + ", Objects.requireNonNull(" + singlePathParam + "))"
                    : "buildQueryUrl(" + singlePathPrefixField + ", Objects.requireNonNull(" + singlePathParam + "), "
                      + literal(singlePathSuffix) + ")";
            requestCode.append("        URI _reqUri = URI.create(").append(urlExpr).append(");\n");
        } else {
            for (String p : pathParamSet) {
                requestCode.append("        Objects.requireNonNull(").append(p).append(");\n");
            }
            var capExpr = new StringBuilder().append(baseUrl.length() + staticQueryChars + fragment.length() + 16);
            for (String p : pathParamSet) {
                capExpr.append(" + (").append(p).append(".length() << 1)");
            }
            for (String p : queryParameters) {
                if (pathParamSet.contains(p)) {
                    capExpr.append(" + (").append(p).append(".length() << 1)");
                } else {
                    capExpr.append(" + (").append(p).append(" != null ? ").append(p).append(".length() << 1 : 0)");
                }
            }
            requestCode.append("        StringBuilder _url = new StringBuilder(").append(capExpr).append(");\n");
            var pm = VARIABLE.matcher(baseUrl);
            int pos = 0;
            while (pm.find()) {
                if (pm.start() > pos) {
                    requestCode.append("        _url.append(")
                            .append(literal(baseUrl.substring(pos, pm.start()))).append(");\n");
                }
                String varName = pm.group(1);
                boolean inPath = pm.start() >= authEnd && (authEnd > 0 || pm.start() > 0);
                if (inPath && !declaredVariables.contains(varName)) {
                    requestCode.append("        appendQueryValue(_url, ")
                            .append(fieldParameters.get(varName)).append(");\n");
                } else if (inPath) {
                    requestCode.append("        appendQueryValue(_url, variable(")
                            .append(literal(varName)).append("));\n");
                } else {
                    requestCode.append("        _url.append(variable(")
                            .append(literal(varName)).append("));\n");
                }
                pos = pm.end();
            }
            if (pos < baseUrl.length()) {
                requestCode.append("        _url.append(")
                        .append(literal(baseUrl.substring(pos))).append(");\n");
            }
            if (!queryFields.isEmpty()) {
                requestCode.append("        int _baseLen = _url.length();\n");
                queryFields.forEach(requestCode::append);
                if (mayHaveEmptyField) {
                    requestCode.append("        if (_url.length() == _baseLen + 1) _url.setLength(_baseLen);\n");
                }
            }
            if (!fragment.isEmpty()) {
                requestCode.append("        _url.append(").append(template(fragment, true)).append(");\n");
            }
            requestCode.append("        URI _reqUri = URI.create(_url.toString());\n");
        }
        requestUri = "_reqUri";
    } else {
        if (variableUrl && !instanceUrl && !reusableRequest) {
            requestCode.append("        URI _uri = ").append(uriField).append(";\n")
                    .append("        if (_uri == null) {\n")
                    .append("            _uri = ").append(uriExpression).append(";\n")
                    .append("            ").append(uriField).append(" = _uri;\n")
                    .append("        }\n");
            requestUri = "_uri";
        }
        if (!queryFields.isEmpty()) {
            String absent = String.join(" && ", queryParameters.stream()
                    .map(p -> p + " == null").toList());
            if (singleQueryPrefixField != null) {
                String urlExpr = singleQuerySuffix.isEmpty()
                        ? "buildQueryUrl(" + singleQueryPrefixField + ", " + singleQueryParam + ")"
                        : "buildQueryUrl(" + singleQueryPrefixField + ", " + singleQueryParam
                          + ", " + literal(singleQuerySuffix) + ")";
                if (defaultRequestExpr != null) {
                    requestCode.append("        if (").append(singleQueryParam).append(" == null) return ")
                            .append(defaultRequestExpr).append(";\n")
                            .append("        return new FastRequest(URI.create(").append(urlExpr).append("), ")
                            .append(literal(endpoint.method())).append(", ").append(headersExpr)
                            .append(", ").append(bodyOptionalExpr).append(");\n");
                    requestUri = null;
                } else if (fallback != null) {
                    requestCode.append("        URI _reqUri = ").append(singleQueryParam)
                            .append(" == null ? ").append(fallback)
                            .append(" : URI.create(").append(urlExpr).append(");\n");
                    requestUri = "_reqUri";
                } else {
                    requestCode.append("        URI _reqUri = ").append(singleQueryParam)
                            .append(" == null ? ").append(requestUri)
                            .append(" : URI.create(").append(urlExpr).append(");\n");
                    requestUri = "_reqUri";
                }
            } else {
                var capExpr = new StringBuilder("_baseLen + ").append(staticQueryChars + fragment.length() + 16);
                for (String p : queryParameters) {
                    capExpr.append(" + (").append(p).append(" != null ? ").append(p).append(".length() << 1 : 0)");
                }
                var dynamic = new StringBuilder();
                dynamic.append("        String _base = ").append(requestUri).append(".toString();\n")
                        .append("        int _baseLen = _base.length();\n")
                        .append("        StringBuilder _url = new StringBuilder(").append(capExpr).append(").append(_base);\n");
                queryFields.forEach(dynamic::append);
                if (mayHaveEmptyField) {
                    dynamic.append("        if (_url.length() == _baseLen + 1) _url.setLength(_baseLen);\n");
                }
                if (!fragment.isEmpty()) {
                    dynamic.append("        _url.append(").append(template(fragment, true)).append(");\n");
                }
                if (defaultRequestExpr != null) {
                    requestCode.append("        if (").append(absent).append(") return ")
                            .append(defaultRequestExpr).append(";\n");
                    dynamic.append("        URI _reqUri = ");
                    if (fragment.isEmpty()) dynamic.append("_url.length() == _baseLen ? ").append(requestUri).append(" : ");
                    dynamic.append("URI.create(_url.toString());\n");
                    dynamic.append("        return new FastRequest(_reqUri, ").append(literal(endpoint.method()))
                            .append(", ").append(headersExpr).append(", ").append(bodyOptionalExpr).append(");\n");
                    requestCode.append(dynamic);
                    requestUri = null;
                } else if (fallback != null) {
                    requestCode.append("        URI _reqUri;\n        if (").append(absent).append(") {\n")
                            .append("            _reqUri = ").append(fallback).append(";\n        } else {\n");
                    dynamic.append("        _reqUri = ");
                    if (fragment.isEmpty()) dynamic.append("_url.length() == _baseLen ? ").append(requestUri).append(" : ");
                    dynamic.append("URI.create(_url.toString());\n");
                    requestCode.append(dynamic.toString().indent(4)).append("        }\n");
                    requestUri = "_reqUri";
                } else {
                    dynamic.append("        URI _reqUri = ");
                    if (fragment.isEmpty()) dynamic.append("_url.length() == _baseLen ? ").append(requestUri).append(" : ");
                    dynamic.append("URI.create(_url.toString());\n");
                    requestCode.append(dynamic);
                    requestUri = "_reqUri";
                }
            }
        }
    }
    if (requestUri != null) {
        if (usesFormBuffer) {
            var capExpr = new StringBuilder().append(Math.max(16, staticFormChars));
            for (String p : formParamSet) {
                capExpr.append(" + (").append(p).append(" != null ? ").append(p)
                        .append(".length() + (").append(p).append(".length() >> 1) : 0)");
            }
            requestCode.append("        FormBuffer _form = new FormBuffer(").append(capExpr).append(");\n");
            formFields.forEach(requestCode::append);
        } else if (usesJsonBuffer) {
            requestCode.append("        JsonBuffer _jbuf = new JsonBuffer(")
                    .append(Math.max(64, endpoint.body().length() * 2)).append(");\n");
            jsonBodyWrites.forEach(requestCode::append);
        }
        requestCode.append("        return new FastRequest(")
                .append(requestUri).append(", ").append(literal(endpoint.method())).append(", ")
                .append(headersExpr).append(", ").append(bodyOptionalExpr).append(");\n");
    }

    String returnType = typedResponse ? responseType : "HttpResponse<String>";
    String cachedRequestExpr = null;
    if (reusableRequest) {
        if (!variableUrl && !anyHeaderVariable) {
            cachedRequestExpr = constPrefix + "_REQUEST";
            java.append("\n    private static final HttpRequest ").append(cachedRequestExpr)
                    .append(" = new FastRequest(").append(uriField).append(", ")
                    .append(literal(endpoint.method())).append(", ").append(headersExpr)
                    .append(", ").append(bodyOptionalExpr).append(");\n");
        } else if ((!variableUrl || instanceUrl) && (!anyHeaderVariable || allHeaderVarsInstance)) {
            cachedRequestExpr = methodName + "Request";
            java.append("\n    private final HttpRequest ").append(cachedRequestExpr).append(";\n");
            ctorInits.append("        this.").append(cachedRequestExpr).append(" = new FastRequest(")
                    .append(uriField).append(", ").append(literal(endpoint.method())).append(", ")
                    .append(headersExpr).append(", ").append(bodyOptionalExpr).append(");\n");
        } else {
            java.append("\n    private volatile HttpRequest ").append(methodName).append("Request;\n");
        }
    }

    java.append("\n    private HttpRequest ").append(buildHelperName).append("(")
            .append(String.join(", ", params)).append(") {\n");
    if (cachedRequestExpr != null) {
        java.append("        return ").append(cachedRequestExpr).append(";\n");
    } else if (reusableRequest) {
        String field = methodName + "Request";
        java.append("        HttpRequest _req = ").append(field).append(";\n")
                .append("        if (_req != null) return _req;\n")
                .append(requestCode.toString().replace("return new FastRequest(", "_req = new FastRequest("))
                .append("        ").append(field).append(" = _req;\n")
                .append("        return _req;\n");
    } else {
        java.append(requestCode);
    }
    java.append("    }\n");

    if (typedResponse) {
        java.append("\n    private static ").append(responseType).append(" ").append(parseHelperName)
                .append("(HttpResponse<Object> _res) throws IOException {\n")
                .append("""
                                if (_res.statusCode() < 200 || _res.statusCode() >= 300) {
                                    throw new IOException("HTTP " + _res.statusCode());
                                }
                                Object _body = _res.body();
                        """)
                .append("        if (_body instanceof ").append(responseType).append(" _r) return _r;\n")
                .append("""
                                if (_body instanceof ParseFailure _f) {
                                    throw new IOException("Invalid JSON response", _f.error());
                                }
                                byte[] _bytes;
                                if (_body instanceof byte[] _b) {
                                    _bytes = _b;
                                } else if (_body instanceof String _s) {
                                    _bytes = _s.getBytes(StandardCharsets.UTF_8);
                                } else {
                                    throw new IOException("Invalid JSON response");
                                }
                                try {
                                    var _json = new ResponseJson(_bytes);
                        """)
                .append("            var _result = ").append(responseType).append(".readJson(_json);\n")
                .append("            _json.finish();\n")
                .append("            return _result;\n")
                .append("""
                                } catch (IllegalArgumentException | ArithmeticException e) {
                                    throw new IOException("Invalid JSON response", e);
                                }
                            }
                        """);
    }

    String unpackArgs = String.join(", ", paramList.stream().map(p -> "arguments." + p + "()").toList());
    String forwardArgs = String.join(", ", paramList);

    if (!typedResponse && responseType != null) {
        java.append("\n    /**\n")
                .append("     * @return the HTTP response. A successful JSON body has the shape of {@link ")
                .append(responseType).append("}.\n")
                .append("     */");
    }
    java.append("\n    public ").append(returnType).append(" ").append(methodName).append("(")
            .append(recordName).append(" arguments) throws IOException, InterruptedException {\n")
            .append("        Objects.requireNonNull(arguments);\n")
            .append("        return ").append(methodName).append("(").append(unpackArgs).append(");\n    }\n");

    java.append("\n    private ").append(returnType).append(" ").append(methodName).append("(")
            .append(String.join(", ", params)).append(") throws IOException, InterruptedException {\n");
    if (typedResponse) {
        java.append("        return ").append(parseHelperName).append("(client.send(")
                .append(buildHelperName).append("(").append(forwardArgs)
                .append("), ").append(responseType).append(".BODY_HANDLER));\n    }\n");
    } else {
        java.append("        return client.send(").append(buildHelperName).append("(").append(forwardArgs)
                .append("), HttpResponse.BodyHandlers.ofString());\n    }\n");
    }

    java.append("\n    public CompletableFuture<").append(returnType).append("> ").append(asyncMethodName)
            .append("(").append(recordName).append(" arguments) {\n")
            .append("        Objects.requireNonNull(arguments);\n")
            .append("        return ").append(asyncMethodName).append("(").append(unpackArgs).append(");\n    }\n");

    java.append("\n    private CompletableFuture<").append(returnType).append("> ").append(asyncMethodName)
            .append("(").append(String.join(", ", params)).append(") {\n");
    if (typedResponse) {
        java.append("        return client.sendAsync(").append(buildHelperName).append("(").append(forwardArgs)
                .append("), ").append(responseType).append(".BODY_HANDLER)\n")
                .append("                .thenApply(_res -> {\n")
                .append("                    try {\n")
                .append("                        return ").append(parseHelperName).append("(_res);\n")
                .append("                    } catch (IOException e) {\n")
                .append("                        throw new CompletionException(e);\n")
                .append("                    }\n")
                .append("                });\n    }\n");
    } else {
        java.append("        return client.sendAsync(").append(buildHelperName).append("(").append(forwardArgs)
                .append("), HttpResponse.BodyHandlers.ofString());\n    }\n");
    }

    return new EndpointFlags(hasPathParams || !queryParameters.isEmpty(), usesFormBuffer, usesJsonBuffer);
}

private static void compileJsonBody(String body, List<String> writes, List<String> params,
                                    List<String> paramList, Set<String> paramNames,
                                    Map<String, String> fieldParameters) {
    int n = body.length();
    boolean inString = false;
    boolean escaped = false;
    var lit = new StringBuilder();
    for (int i = 0; i < n; ) {
        char c = body.charAt(i);
        if (!inString && c == '"') {
            var m = VARIABLE.matcher(body);
            if (m.region(i + 1, n).lookingAt() && m.end() < n && body.charAt(m.end()) == '"') {
                flushJsonLiteral(lit, writes);
                String p = registerParam(m.group(1), params, paramList, paramNames, fieldParameters);
                writes.add("        _jbuf.appendJsonString(" + p + ");\n");
                i = m.end() + 1;
                continue;
            }
            inString = true;
            lit.append(c);
            i++;
            continue;
        }
        if (c == '{' && i + 1 < n && body.charAt(i + 1) == '{') {
            var m = VARIABLE.matcher(body);
            if (m.region(i, n).lookingAt()) {
                flushJsonLiteral(lit, writes);
                String p = registerParam(m.group(1), params, paramList, paramNames, fieldParameters);
                if (inString) {
                    writes.add("        _jbuf.appendJsonStringContent(Objects.requireNonNull(" + p + "));\n");
                } else {
                    writes.add("        _jbuf.appendJsonRaw(" + p + ");\n");
                }
                i = m.end();
                continue;
            }
        }
        if (inString) {
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = false;
            }
        }
        lit.append(c);
        i++;
    }
    flushJsonLiteral(lit, writes);
}

private static void flushJsonLiteral(StringBuilder lit, List<String> writes) {
    if (lit.isEmpty()) return;
    String text = lit.toString();
    lit.setLength(0);
    boolean ascii = text.chars().allMatch(c -> c < 128);
    writes.add("        _jbuf." + (ascii ? "appendAscii(" : "appendUtf8(") + literal(text) + ");\n");
}

private static String appendDefaultUri(StringBuilder java, String uriField, String baseUrl,
                                       List<String> fixedFields, String fragment) {
    if (fixedFields.isEmpty() && fragment.isEmpty()) return uriField;
    String url = baseUrl + (fixedFields.isEmpty() ? "" : "?" + String.join("&", fixedFields)) + fragment;
    try {
        URI.create(url);
    } catch (IllegalArgumentException invalidLiteral) {
        return null;
    }
    String field = uriField + "_WITHOUT_ARGUMENTS";
    java.append("\n    private static final URI ").append(field).append(" = URI.create(")
            .append(literal(url)).append(");\n");
    return field;
}

private static String queryField(String field, List<String> params, List<String> paramList,
                                 Set<String> paramNames, Map<String, String> fieldParameters,
                                 Set<String> queryParamSet) {
    var matcher = VARIABLE.matcher(field);
    var writes = new ArrayList<String>();
    var arguments = new LinkedHashSet<String>();
    int end = 0;
    while (matcher.find()) {
        if (matcher.start() > end) {
            writes.add("_url.append(" + literal(field.substring(end, matcher.start())) + ");");
        }
        String parameter = registerParam(matcher.group(1), params, paramList, paramNames, fieldParameters);
        queryParamSet.add(parameter);
        arguments.add(parameter);
        writes.add("appendQueryValue(_url, " + parameter + ");");
        end = matcher.end();
    }
    if (end < field.length() || writes.isEmpty()) {
        writes.add("_url.append(" + literal(field.substring(end)) + ");");
    }
    writes.addFirst("_url.append(_url.length() == _baseLen ? '?' : '&');");
    if (arguments.isEmpty()) return String.join("\n", writes).indent(8);
    String condition = String.join(" != null && ", arguments) + " != null";
    return "        if (" + condition + ") {\n" + String.join("\n", writes).indent(12) + "        }\n";
}

private static String formField(String field, List<String> params, List<String> paramList,
                                Set<String> paramNames, Map<String, String> fieldParameters,
                                Set<String> formParamSet) {
    var matcher = VARIABLE.matcher(field);
    var writes = new ArrayList<String>();
    var arguments = new LinkedHashSet<String>();
    int end = 0;
    while (matcher.find()) {
        if (matcher.start() > end) {
            writes.add(formLiteralWrite(field.substring(end, matcher.start())));
        }
        String parameter = registerParam(matcher.group(1), params, paramList, paramNames, fieldParameters);
        formParamSet.add(parameter);
        arguments.add(parameter);
        writes.add("_form.appendValue(" + parameter + ");");
        end = matcher.end();
    }
    if (end < field.length() || writes.isEmpty()) {
        writes.add(formLiteralWrite(field.substring(end)));
    }
    writes.addFirst("if (_form.length() > 0) _form.appendByte('&');");
    if (arguments.isEmpty()) return String.join("\n", writes).indent(8);
    String condition = String.join(" != null && ", arguments) + " != null";
    return "        if (" + condition + ") {\n" + String.join("\n", writes).indent(12) + "        }\n";
}

private static String formLiteralWrite(String text) {
    boolean ascii = text.chars().allMatch(c -> c < 128);
    return (ascii ? "_form.appendAscii(" : "_form.appendUtf8(") + literal(text) + ");";
}

private static String registerParam(String variable, List<String> params, List<String> paramList,
                                    Set<String> paramNames, Map<String, String> fieldParameters) {
    String base = SourceVersion.isIdentifier(variable) && !SourceVersion.isKeyword(variable)
            ? variable : identifier(variable);
    return fieldParameters.computeIfAbsent(variable, key -> {
        String name = unique(base, paramNames);
        params.add("String " + name);
        paramList.add(name);
        return name;
    });
}

private static void appendResponse(StringBuilder java, List<ResponseRecord> responses,
                                   String name, String methodName) {
    var root = responses.stream().filter(response -> response.name() == null).toList();
    if (root.size() != 1) {
        throw new IllegalArgumentException("Expected one unnamed @response for " + methodName);
    }
    java.append("\n    public record ").append(name).append("(")
            .append(root.getFirst().components()).append(") {");
    if (responses.size() > 1) {
        java.append('\n');
        var nestedNames = new HashSet<>(Set.of(name));
        for (ResponseRecord response : responses) {
            if (response.name() == null) {
                continue;
            }
            if (!nestedNames.add(response.name())) {
                throw new IllegalArgumentException("Duplicate @response record: " + response.name());
            }
            java.append("        public record ").append(response.name()).append("(")
                    .append(response.components()).append(") {}\n");
        }
        java.append("    ");
    }
    java.append("}\n");
}

private static String responseParser() throws IOException {
    String launcherSource = System.getProperty("jdk.launcher.sourcefile");
    Path source = Path.of(launcherSource == null ? "BuildApi.java" : launcherSource);
    // Copy the cursor implementation shared with the annotation reader.
    String script = Files.readString(source);
    int start = script.indexOf("\nprivate static class ResponseJson {") + 1;
    int end = script.indexOf("\nprivate static final class ResponseExample", start);
    return "\n" + script.substring(start, end).stripTrailing()
            .replaceFirst("class ResponseJson", "final class ResponseJson").indent(4);
}

private record JsonType(String name, String expr, JsonType element) {
    boolean isList() {
        return element != null;
    }
}

private record FieldSpec(String key, String keyConst, String local, String readExpr, boolean fastKey) {
}

private static boolean isSimpleAsciiKey(String key) {
    for (int i = 0; i < key.length(); i++) {
        char c = key.charAt(i);
        if (c < 0x20 || c >= 0x7F || c == '"' || c == '\\') return false;
    }
    return true;
}

private static void appendJsonResponse(StringBuilder java, String name, Map<String, Object> sample,
                                       Map<String, String> sharedTypeNames, boolean withBodyHandler) {
    var nested = new StringBuilder();
    var names = new HashSet<>(Set.of("ResponseJson", "FormBuffer", "JsonBuffer", "FastRequest",
            "JsonBodySubscriber", "ParseFailure", "BodySub",
            "List", "Map", "Object", "String", "Long", "Boolean", "BigDecimal"));
    names.add(name);
    names.addAll(sharedTypeNames.values());
    appendJsonRecord(java, nested, names, name, sample, "    ", sharedTypeNames, withBodyHandler);
    java.append(nested).append("    }\n");
}

private static void appendJsonRecord(StringBuilder java, StringBuilder nested, Set<String> names,
                                     String name, Map<String, Object> sample, String indent,
                                     Map<String, String> sharedTypeNames, boolean withBodyHandler) {
    var components = new ArrayList<String>();
    var locals = new ArrayList<String>();
    var declarations = new ArrayList<String>();
    var unorderedParams = new ArrayList<String>();
    unorderedParams.add("ResponseJson json");
    var keyConsts = new ArrayList<String>();
    var fieldSpecs = new ArrayList<FieldSpec>();
    var listHelpers = new StringBuilder();
    int[] listCounter = {0};
    var cases = new LinkedHashMap<Integer, List<String>>();
    var fields = new HashSet<>(Set.of("clone", "finalize", "getClass", "hashCode",
            "notify", "notifyAll", "toString", "wait"));
    sample.forEach((key, example) -> {
        int idx = components.size();
        String field = unique(identifier(key.replaceAll("([a-z0-9])([A-Z])", "$1 $2")), fields);
        JsonType type = jsonType(nested, names, capitalize(field), example, sharedTypeNames);
        String local = "field" + idx;
        String keyConst = "KEY_" + idx;
        boolean fastKey = isSimpleAsciiKey(key);
        String readExpr = type.isList()
                ? emitListHelper(listHelpers, type, indent + "    ", listCounter) + "(json)"
                : type.expr();
        components.add(type.name() + " " + field);
        locals.add(local);
        declarations.add(type.name() + " " + local + " = null;");
        unorderedParams.add(type.name() + " " + local);
        if (fastKey) {
            keyConsts.add("private static final byte[] " + keyConst + " = "
                          + literal("\"" + key + "\":") + ".getBytes(StandardCharsets.US_ASCII);");
        } else {
            keyConsts.add("private static final byte[] " + keyConst + " = "
                          + literal(key) + ".getBytes(StandardCharsets.UTF_8);");
        }
        fieldSpecs.add(new FieldSpec(key, keyConst, local, readExpr, fastKey));
        String matchCall = fastKey
                ? "json.keyEqualsToken(" + keyConst + ", " + literal(key) + ")"
                : "json.keyEquals(" + keyConst + ", " + literal(key) + ")";
        cases.computeIfAbsent(key.hashCode(), ignored -> new ArrayList<>())
                .add("if (" + matchCall + ") {\n"
                     + indent + "                        " + local + " = " + readExpr + ";\n"
                     + indent + "                        continue;\n"
                     + indent + "                    }");
    });
    java.append('\n').append(indent).append("public record ").append(name).append("(")
            .append(String.join(", ", components)).append(") {\n");
    for (String keyConst : keyConsts) {
        java.append(indent).append("    ").append(keyConst).append('\n');
    }
    if (withBodyHandler) {
        java.append(indent).append("    private static final HttpResponse.BodyHandler<Object> BODY_HANDLER =\n")
                .append(indent).append("            info -> info.statusCode() >= 200 && info.statusCode() < 300\n")
                .append(indent).append("                    ? new BodySub(info.headers())\n")
                .append(indent).append("                    : HttpResponse.BodySubscribers.replacing(null);\n")
                .append(indent).append("    private static final class BodySub extends JsonBodySubscriber {\n")
                .append(indent).append("        BodySub(HttpHeaders headers) {\n")
                .append(indent).append("            super(headers);\n")
                .append(indent).append("        }\n")
                .append(indent).append("        @Override\n")
                .append(indent).append("        public void onComplete() {\n")
                .append(indent).append("            try {\n")
                .append(indent).append("                var json = toJson();\n")
                .append(indent).append("                var parsed = readJson(json);\n")
                .append(indent).append("                json.finish();\n")
                .append(indent).append("                complete(parsed);\n")
                .append(indent).append("            } catch (IllegalArgumentException | ArithmeticException e) {\n")
                .append(indent).append("                complete(new ParseFailure(e));\n")
                .append(indent).append("            } catch (Throwable t) {\n")
                .append(indent).append("                completeExceptionally(t);\n")
                .append(indent).append("            }\n")
                .append(indent).append("        }\n")
                .append(indent).append("    }\n");
    }
    java.append(indent).append("    private static ").append(name)
            .append(" readJson(ResponseJson json) {\n")
            .append(indent).append("        json.expect('{');\n")
            .append(indent).append("        return readJsonBody(json);\n")
            .append(indent).append("    }\n")
            .append(indent).append("    private static ").append(name)
            .append(" readJsonBody(ResponseJson json) {\n");
    for (String declaration : declarations) {
        java.append(indent).append("        ").append(declaration).append('\n');
    }

    boolean hasFastKeys = fieldSpecs.stream().anyMatch(FieldSpec::fastKey);
    java.append(indent).append("        if (!json.take('}')) {\n");
    if (hasFastKeys) {
        java.append(indent).append("            boolean more = true;\n");
        boolean first = true;
        for (FieldSpec spec : fieldSpecs) {
            if (!spec.fastKey()) continue;
            java.append(indent).append("            if (")
                    .append(first ? "" : "more && ")
                    .append("json.matchKey(").append(spec.keyConst()).append(")) {\n")
                    .append(indent).append("                ").append(spec.local()).append(" = ")
                    .append(spec.readExpr()).append(";\n")
                    .append(indent).append("                more = json.more('}');\n")
                    .append(indent).append("            }\n");
            first = false;
        }
        java.append(indent).append("            if (more) return readJsonUnordered(json, ")
                .append(String.join(", ", locals)).append(");\n")
                .append(indent).append("        }\n")
                .append(indent).append("        return new ").append(name).append("(")
                .append(String.join(", ", locals)).append(");\n")
                .append(indent).append("    }\n");

        java.append(indent).append("    private static ").append(name).append(" readJsonUnordered(")
                .append(String.join(", ", unorderedParams)).append(") {\n")
                .append(indent).append("        do {\n")
                .append(indent).append("            int hash = json.nextKeyHash();\n")
                .append(indent).append("            switch (hash) {\n");
        cases.forEach((hash, branches) -> {
            java.append(indent).append("                case ").append(hash).append(" -> {\n");
            for (String branch : branches) {
                java.append(indent).append("                    ").append(branch).append('\n');
            }
            java.append(indent).append("                }\n");
        });
        java.append(indent).append("            }\n")
                .append(indent).append("            json.skipValue();\n")
                .append(indent).append("        } while (json.more('}'));\n")
                .append(indent).append("        return new ").append(name).append("(")
                .append(String.join(", ", locals)).append(");\n")
                .append(indent).append("    }\n");
    } else {
        java.append(indent).append("            do {\n")
                .append(indent).append("                int hash = json.nextKeyHash();\n")
                .append(indent).append("                switch (hash) {\n");
        cases.forEach((hash, branches) -> {
            java.append(indent).append("                    case ").append(hash).append(" -> {\n");
            for (String branch : branches) {
                java.append(indent).append("                        ")
                        .append(branch.replace("\n" + indent + "                    ", "\n" + indent + "                        "))
                        .append('\n');
            }
            java.append(indent).append("                    }\n");
        });
        java.append(indent).append("                }\n")
                .append(indent).append("                json.skipValue();\n")
                .append(indent).append("            } while (json.more('}'));\n")
                .append(indent).append("        }\n")
                .append(indent).append("        return new ").append(name).append("(")
                .append(String.join(", ", locals)).append(");\n")
                .append(indent).append("    }\n");
    }
    java.append(listHelpers);
    if (java == nested) java.append(indent).append("}\n");
}

private static String emitListHelper(StringBuilder out, JsonType listType, String indent, int[] counter) {
    JsonType elem = listType.element();
    String elemRead = elem.isList()
            ? emitListHelper(out, elem, indent, counter) + "(json)"
            : elem.expr();
    String helper = "readList" + (counter[0]++);
    out.append(indent).append("private static ").append(listType.name()).append(' ').append(helper)
            .append("(ResponseJson json) {\n")
            .append(indent).append("    if (!json.take('[')) return json.<").append(listType.name()).append(">expectNull();\n")
            .append(indent).append("    if (json.take(']')) return List.of();\n")
            .append(indent).append("    int firstStart = json.position;\n")
            .append(indent).append("    ").append(elem.name()).append(" first = ").append(elemRead).append(";\n")
            .append(indent).append("    if (!json.more(']')) return Collections.singletonList(first);\n")
            .append(indent).append("    int itemBytes = Math.max(2, json.position - firstStart);\n")
            .append(indent).append("    int est = Math.min(512, Math.max(4, (json.remaining() / itemBytes) + 4));\n")
            .append(indent).append("    var list = new ArrayList<").append(elem.name()).append(">(est);\n")
            .append(indent).append("    list.add(first);\n")
            .append(indent).append("    do {\n")
            .append(indent).append("        list.add(").append(elemRead).append(");\n")
            .append(indent).append("    } while (json.more(']'));\n")
            .append(indent).append("    return list;\n")
            .append(indent).append("}\n");
    return helper;
}

private static JsonType jsonType(StringBuilder nested, Set<String> names,
                                 String label, Object sample,
                                 Map<String, String> sharedTypeNames) {
    return switch (sample) {
        case Map<?, ?> raw -> {
            Map<String, Object> object = ResponseExample.fields(raw);
            String shared = sharedTypeNames.get(shapeSignature(object));
            String name;
            if (shared != null) {
                name = shared;
            } else {
                name = unique(label, names);
                appendJsonRecord(nested, nested, names, name, object, "        ", sharedTypeNames, false);
            }
            yield new JsonType(name, "json.take('{') ? " + name + ".readJsonBody(json) : json.<" + name + ">expectNull()", null);
        }
        case List<?> list -> {
            if (list.size() != 1 || list.getFirst() == null) {
                throw new IllegalArgumentException("Use one non-null array example for " + label);
            }
            JsonType element = jsonType(nested, names, label, list.getFirst(), sharedTypeNames);
            yield new JsonType("List<" + element.name() + ">", null, element);
        }
        case String ignored -> new JsonType("String", "json.nullableString()", null);
        case Boolean ignored -> new JsonType("Boolean", "json.nullableBool()", null);
        case java.math.BigDecimal number -> number.scale() <= 0 && number.precision() - number.scale() < 19
                ? new JsonType("Long", "json.nullableLong()", null)
                : new JsonType("java.math.BigDecimal", "json.nullableDecimal()", null);
        case null -> new JsonType("Object", "json.nullableRaw()", null);
        default -> throw new IllegalArgumentException("Unsupported JSON example for " + label);
    };
}

private static class ResponseJson {
    private int keyStart;
    private int keyLength;
    private String escapedKey;
    final byte[] input;
    final int limit;
    int position;

    ResponseJson(byte[] input) {
        this(input, 0, input.length);
    }

    ResponseJson(byte[] input, int start, int limit) {
        this.input = input;
        this.position = start;
        this.limit = Math.min(limit, input.length);
    }

    int remaining() {
        return limit - position;
    }

    private static int skipWs(byte[] in, int pos, int len) {
        while (pos < len) {
            byte b = in[pos];
            if (b == ' ') {
                pos++;
                while (pos < len && in[pos] == ' ') pos++;
            } else if (b == '\n' || b == '\r' || b == '\t') {
                pos++;
            } else {
                break;
            }
        }
        return pos;
    }

    boolean matchKey(byte[] tok) {
        byte[] in = input;
        int pos = position;
        int len = Math.min(limit, in.length);
        if (pos < len && in[pos] == ' ') pos++;
        int tlen = tok.length;
        if (pos + tlen <= len) {
            if (Arrays.equals(in, pos, pos + tlen, tok, 0, tlen)) {
                position = pos + tlen;
                return true;
            }
            if (in[pos] == '"' && (in[pos + tlen - 1] & 0xFF) > ' ') {
                position = pos;
                return false;
            }
        }
        return matchKeySlow(tok);
    }

    private boolean matchKeySlow(byte[] tok) {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = skipWs(in, position, len);
        position = pos;
        int qlen = tok.length - 1;
        if (pos + qlen <= len && Arrays.equals(in, pos, pos + qlen, tok, 0, qlen)) {
            int after = pos + qlen;
            if (after < len && in[after] == ':') {
                position = after + 1;
                return true;
            }
            after = skipWs(in, after, len);
            if (after < len && in[after] == ':') {
                position = after + 1;
                return true;
            }
            position = after;
            throw invalid();
        }
        return false;
    }

    Object keyword(String token, Object value) {
        int tlen = token.length();
        int pos = position;
        byte[] in = input;
        int len = Math.min(limit, in.length);
        if (pos + tlen > len) throw invalid();
        for (int i = 0; i < tlen; i++) {
            if (in[pos + i] != (byte) token.charAt(i)) throw invalid();
        }
        position = pos + tlen;
        return value;
    }

    <T> T expectNull() {
        whitespace();
        keyword("null", null);
        return null;
    }

    private void integerPart() {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        if (pos < len && in[pos] == '-') pos++;
        if (pos < len && in[pos] == '0') {
            position = pos + 1;
        } else {
            position = pos;
            digits();
        }
    }

    private void digits() {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        int start = pos;
        while (pos < len) {
            byte c = in[pos];
            if (c < '0' || c > '9') break;
            pos++;
        }
        position = pos;
        if (pos == start) throw invalid();
    }

    private boolean numberSuffix() {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        int start = pos;
        if (pos < len && in[pos] == '.') {
            position = pos + 1;
            digits();
            pos = position;
        }
        if (pos < len && (in[pos] == 'e' || in[pos] == 'E')) {
            pos++;
            if (pos < len && (in[pos] == '+' || in[pos] == '-')) pos++;
            position = pos;
            digits();
            pos = position;
        }
        return pos != start;
    }

    java.math.BigDecimal number() {
        int start = position;
        integerPart();
        numberSuffix();
        return new java.math.BigDecimal(
                new String(input, start, position - start, StandardCharsets.ISO_8859_1));
    }

    Long nullableLong() {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        if (pos < len) {
            byte c = in[pos];
            if (c == ' ' && ++pos < len) c = in[pos];
            boolean neg = c == '-';
            if (neg) {
                if (++pos == len) return nullableLongSlow();
                c = in[pos];
            }
            if (c >= '1' && c <= '9') {
                long value = c - '0';
                int first = pos++;
                while (pos < len) {
                    int d = in[pos] - '0';
                    if (d < 0 || d > 9) break;
                    value = value * 10 + d;
                    pos++;
                }
                if (pos == len || (in[pos] != '.' && in[pos] != 'e' && in[pos] != 'E')) {
                    if (pos - first <= 18) {
                        position = pos;
                        return neg ? -value : value;
                    }
                }
            } else if (c == '0' && (pos + 1 == len
                                    || (in[pos + 1] != '.' && in[pos + 1] != 'e' && in[pos + 1] != 'E'
                                        && (in[pos + 1] < '0' || in[pos + 1] > '9')))) {
                position = pos + 1;
                return 0L;
            }
        }
        return nullableLongSlow();
    }

    private Long nullableLongSlow() {
        whitespace();
        byte[] in = input;
        int len = Math.min(limit, in.length);
        if (position < len && in[position] == 'n') {
            keyword("null", null);
            return null;
        }
        int start = position;
        int pos = start;
        boolean negative = pos < len && in[pos] == '-';
        if (negative) pos++;
        int first = pos;
        long value = 0;
        if (pos < len && in[pos] == '0') {
            pos++;
        } else {
            while (pos < len) {
                int digit = in[pos] - '0';
                if (digit < 0 || digit > 9) break;
                value = value * 10 + digit;
                pos++;
            }
            if (pos == first) { position = pos; throw invalid(); }
        }
        position = pos;
        if (numberSuffix()) {
            return new java.math.BigDecimal(
                    new String(in, start, position - start, StandardCharsets.ISO_8859_1)).longValueExact();
        }
        if (pos - first > 18) {
            return Long.parseLong(new String(in, start, pos - start, StandardCharsets.ISO_8859_1));
        }
        return negative ? -value : value;
    }

    int nextKeyHash() {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        if (pos >= len || in[pos] != '"') {
            expect('"');
            pos = position;
        } else {
            pos++;
        }
        int start = pos;
        keyStart = start;
        escapedKey = null;
        int hash = 0;
        while (pos < len) {
            byte b = in[pos++];
            if (b == '"') {
                keyLength = pos - start - 1;
                if (pos < len && in[pos] == ':') {
                    position = pos + 1;
                } else {
                    position = pos;
                    expect(':');
                }
                return hash;
            }
            if (b < ' ' || b == '\\') {
                if (b >= 0 && b < ' ') { position = pos; throw invalid(); }
                position = start - 1;
                escapedKey = string();
                expect(':');
                return escapedKey.hashCode();
            }
            hash = 31 * hash + b;
        }
        position = pos;
        throw invalid();
    }

    boolean keyEqualsToken(byte[] tok, String expected) {
        if (escapedKey != null) return escapedKey.equals(expected);
        int klen = tok.length - 3;
        return keyLength == klen
               && Arrays.equals(input, keyStart, keyStart + klen, tok, 1, 1 + klen);
    }

    boolean keyEquals(byte[] expectedBytes, String expected) {
        if (escapedKey != null) return escapedKey.equals(expected);
        int len = expectedBytes.length;
        return keyLength == len
               && Arrays.equals(input, keyStart, keyStart + len, expectedBytes, 0, len);
    }

    String nullableString() {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        if (pos < len) {
            byte c = in[pos];
            if (c == ' ' && ++pos < len) c = in[pos];
            if (c == '"') return readStringBody(pos + 1);
            if (c == 'n') { position = pos; return (String) keyword("null", null); }
        }
        return nullableStringSlow();
    }

    private String nullableStringSlow() {
        whitespace();
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        if (pos < len) {
            byte c = in[pos];
            if (c == '"') return readStringBody(pos + 1);
            if (c == 'n') return (String) keyword("null", null);
        }
        throw invalid();
    }

    String string() {
        expect('"');
        return readStringBody(position);
    }

    private String readStringBody(int pos) {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int start = pos;
        int or = 0;
        while (pos < len) {
            byte b = in[pos++];
            if (b == '"') {
                position = pos;
                int n = pos - 1 - start;
                if (n == 0) return "";
                return new String(in, start, n, or >= 0 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
            }
            if (b == '\\') {
                return readStringEscaped(start, pos, or);
            }
            if ((b & 0xFF) < ' ') { position = pos; throw invalid(); }
            or |= b;
        }
        position = pos;
        throw invalid();
    }

    private String readStringEscaped(int start, int pos, int or) {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        var decoded = new StringBuilder();
        while (true) {
            int n = pos - 1 - start;
            if (n > 0) {
                decoded.append(new String(in, start, n, or >= 0 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8));
            }
            position = pos;
            decoded.append(escape());
            pos = position;
            start = pos;
            or = 0;
            while (pos < len) {
                byte b = in[pos++];
                if (b == '"') {
                    position = pos;
                    int rem = pos - 1 - start;
                    if (rem > 0) {
                        decoded.append(new String(in, start, rem, or >= 0 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8));
                    }
                    return decoded.toString();
                }
                if (b == '\\') break;
                if ((b & 0xFF) < ' ') { position = pos; throw invalid(); }
                or |= b;
            }
            if (pos >= len && (pos == 0 || in[pos - 1] != '\\')) {
                position = pos;
                throw invalid();
            }
        }
    }

    private void skipString() {
        expect('"');
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        while (pos < len) {
            byte b = in[pos++];
            if (b == '"') {
                position = pos;
                return;
            }
            if (b == '\\') {
                position = pos;
                escape();
                pos = position;
                continue;
            }
            if ((b & 0xFF) < ' ') { position = pos; throw invalid(); }
        }
        position = pos;
        throw invalid();
    }

    private char escape() {
        byte[] in = input;
        if (position >= Math.min(limit, in.length)) throw invalid();
        return switch (in[position++]) {
            case '"', '\\', '/' -> (char) in[position - 1];
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> unicode();
            default -> throw invalid();
        };
    }

    private char unicode() {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int value = 0;
        for (int i = 0; i < 4; i++) {
            if (position >= len) throw invalid();
            byte b = in[position++];
            int hex = b >= '0' && b <= '9' ? b - '0'
                    : b >= 'a' && b <= 'f' ? b - 'a' + 10
                    : b >= 'A' && b <= 'F' ? b - 'A' + 10 : -1;
            if (hex < 0) throw invalid();
            value = (value << 4) | hex;
        }
        return (char) value;
    }

    void whitespace() {
        byte[] in = input;
        position = skipWs(in, position, Math.min(limit, in.length));
    }

    boolean take(char c) {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        if (pos < len) {
            byte cur = in[pos];
            if (cur == (byte) c) {
                position = pos + 1;
                return true;
            }
            if (cur == ' ' && pos + 1 < len && in[pos + 1] == (byte) c) {
                position = pos + 2;
                return true;
            }
        }
        return takeSlow(c);
    }

    private boolean takeSlow(char c) {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = skipWs(in, position, len);
        position = pos;
        if (pos < len && in[pos] == (byte) c) {
            position = pos + 1;
            return true;
        }
        return false;
    }

    void expect(char c) {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        if (pos < len) {
            byte cur = in[pos];
            if (cur == (byte) c) {
                position = pos + 1;
                return;
            }
            if (cur == ' ' && pos + 1 < len && in[pos + 1] == (byte) c) {
                position = pos + 2;
                return;
            }
        }
        expectSlow(c);
    }

    private void expectSlow(char c) {
        if (!takeSlow(c)) throw invalid();
    }

    boolean more(char end) {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        if (pos < len) {
            byte c = in[pos];
            if (c == ' ' && ++pos < len) c = in[pos];
            if (c == ',') { position = pos + 1; return true; }
            if (c == (byte) end) { position = pos + 1; return false; }
        }
        return moreSlow(end);
    }

    private boolean moreSlow(char end) {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = skipWs(in, position, len);
        if (pos >= len) { position = pos; throw invalid(); }
        byte c = in[pos];
        if (c == ',') { position = pos + 1; return true; }
        if (c == (byte) end) { position = pos + 1; return false; }
        position = pos;
        throw invalid();
    }

    IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid JSON at byte " + position);
    }

    Boolean nullableBool() {
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        if (pos < len) {
            byte c = in[pos];
            if (c == ' ' && ++pos < len) c = in[pos];
            if (c == 't' && pos + 4 <= len && in[pos + 1] == 'r'
                && in[pos + 2] == 'u' && in[pos + 3] == 'e') {
                position = pos + 4;
                return Boolean.TRUE;
            }
            if (c == 'f' && pos + 5 <= len && in[pos + 1] == 'a'
                && in[pos + 2] == 'l' && in[pos + 3] == 's' && in[pos + 4] == 'e') {
                position = pos + 5;
                return Boolean.FALSE;
            }
        }
        return nullableBoolSlow();
    }

    private Boolean nullableBoolSlow() {
        whitespace();
        byte[] in = input;
        int len = Math.min(limit, in.length);
        int pos = position;
        if (pos < len) {
            byte c = in[pos];
            if (c == 't') return (Boolean) keyword("true", Boolean.TRUE);
            if (c == 'f') return (Boolean) keyword("false", Boolean.FALSE);
            if (c == 'n') return (Boolean) keyword("null", null);
        }
        throw invalid();
    }

    java.math.BigDecimal nullableDecimal() {
        whitespace();
        byte[] in = input;
        if (position < Math.min(limit, in.length) && in[position] == 'n') {
            keyword("null", null);
            return null;
        }
        return number();
    }

    String nullableRaw() {
        whitespace();
        byte[] in = input;
        if (position < Math.min(limit, in.length) && in[position] == 'n') {
            keyword("null", null);
            return null;
        }
        int start = position;
        skipValue();
        return new String(in, start, position - start, StandardCharsets.UTF_8);
    }

    void skipValue() {
        whitespace();
        byte[] in = input;
        if (position >= Math.min(limit, in.length)) throw invalid();
        switch (in[position]) {
            case '{' -> {
                position++;
                if (take('}')) return;
                do {
                    skipString();
                    expect(':');
                    skipValue();
                } while (more('}'));
            }
            case '[' -> {
                position++;
                if (take(']')) return;
                do {
                    skipValue();
                } while (more(']'));
            }
            case '"' -> skipString();
            case 't' -> keyword("true", Boolean.TRUE);
            case 'f' -> keyword("false", Boolean.FALSE);
            case 'n' -> keyword("null", null);
            default -> {
                integerPart();
                numberSuffix();
            }
        }
    }

    void finish() {
        whitespace();
        if (position != limit) throw invalid();
    }
}

private static final class ResponseExample extends ResponseJson {
    ResponseExample(String input) {
        super(input.getBytes(StandardCharsets.UTF_8));
    }

    Map<String, Object> object() {
        var result = fields(value());
        whitespace();
        if (position != limit) throw invalid();
        return result;
    }

    private Object value() {
        whitespace();
        if (position == limit) throw invalid();
        return switch (input[position]) {
            case '{' -> readObject();
            case '[' -> array();
            case '"' -> string();
            case 't' -> keyword("true", true);
            case 'f' -> keyword("false", false);
            case 'n' -> keyword("null", null);
            default -> number();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        var fields = new LinkedHashMap<String, Object>();
        if (take('}')) return fields;
        do {
            String key = string();
            expect(':');
            if (fields.containsKey(key)) throw invalid();
            fields.put(key, value());
        } while (take(','));
        expect('}');
        return fields;
    }

    private List<Object> array() {
        expect('[');
        var items = new ArrayList<>();
        if (take(']')) return items;
        do {
            items.add(value());
        } while (take(','));
        expect(']');
        return items;
    }

    static Map<String, Object> fields(Object value) {
        if (!(value instanceof Map<?, ?> fields)) throw new IllegalArgumentException("Expected JSON object");
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) fields;
        return result;
    }
}

private static String template(String value, boolean url) {
    var expressions = new ArrayList<String>();
    var matcher = VARIABLE.matcher(value);
    int end = 0;
    int authorityEnd = authorityEnd(value);
    while (matcher.find()) {
        if (end < matcher.start()) {
            expressions.add(literal(value.substring(end, matcher.start())));
        }
        String expression = "variable(" + literal(matcher.group(1)) + ")";
        boolean component = url && matcher.start() >= authorityEnd
                            && (authorityEnd > 0 || end > 0 || matcher.start() > 0);
        expressions.add(component ? "encode(" + expression + ").replace(\"+\", \"%20\")" : expression);
        end = matcher.end();
    }
    if (end < value.length() || expressions.isEmpty()) {
        expressions.add(literal(value.substring(end)));
    }
    return String.join(" + ", expressions);
}

private static String identifier(String label) {
    var name = new StringBuilder();
    for (String word : label.split("[^a-zA-Z0-9]+")) {
        if (word.isEmpty()) {
            continue;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        name.append(name.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
    }
    if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0)) || SourceVersion.isKeyword(name)) {
        name.insert(0, '_');
    }
    return name.toString();
}

private static String unique(String base, Set<String> used) {
    String name = base;
    for (int suffix = 2; !used.add(name); suffix++) {
        name = base + suffix;
    }
    return name;
}

private static String capitalize(String name) {
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
}

private static String literal(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n") + "\"";
}