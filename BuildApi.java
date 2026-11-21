import javax.lang.model.SourceVersion;

private static final Pattern REQUEST = Pattern.compile("([A-Z]+)\\s+(.+?)(?:\\s+HTTP/\\S+)?");
private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");
private static final Pattern RESPONSE = Pattern.compile(
        "(?:#|//)?\\s*@response\\s*([A-Z][\\w]*)?\\(([^()]*)\\)");
private static final Pattern RESPONSE_COMPONENT = Pattern.compile(
        "[A-Za-z][\\w.]*(?:<[A-Za-z][\\w.]*>)?\\s+[A-Za-z_][\\w]*");

private record ResponseRecord(String name, String components) {
}

private record Endpoint(String title, String method, String url,
                        List<String> headers, String body, List<ResponseRecord> responses) {
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
    Files.writeString(output, generate(className, parse(Files.readString(source))));
    IO.println(output);
}

private static List<Endpoint> parse(String source) {
    var endpoints = new ArrayList<Endpoint>();
    String[] sections = source.replaceFirst("^\\uFEFF", "").split("(?m)^###(?=\\s|$)", -1);
    for (int section = 0; section < sections.length; section++) {
        String[] lines = sections[section].split("\\R", -1);
        String title = section == 0 ? "" : lines[0].strip();
        int index = section == 0 ? 0 : 1;
        var responses = new ArrayList<ResponseRecord>();
        while (index < lines.length) {
            String line = lines[index].strip();
            if (line.startsWith("={%")) {
                while (!lines[index].contains("%}")) {
                    if (++index == lines.length) {
                        throw new IllegalArgumentException("Unclosed shared HTTP script block");
                    }
                }
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
        endpoints.add(new Endpoint(title, request.group(1), request.group(2), headers,
                body.toString().strip(), responses));
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

private static String generate(String className, List<Endpoint> endpoints) {
    var java = new StringBuilder("""
            import java.io.IOException;
            import java.net.URI;
            import java.net.URLEncoder;
            import java.net.http.HttpClient;
            import java.net.http.HttpRequest;
            import java.net.http.HttpResponse;
            import java.nio.charset.StandardCharsets;
            import java.util.List;
            import java.util.Map;
            import java.util.Objects;
            import java.util.StringJoiner;
            
            """);
    java.append("public final class ").append(className).append(" {\n");
    java.append("""
                private final HttpClient client;
                private final Map<String, String> variables;
            
            """);
    java.append("    public ").append(className).append("(Map<String, String> variables) {\n");
    java.append("        this(HttpClient.newHttpClient(), variables);\n    }\n\n");
    java.append("    public ").append(className).append("(HttpClient client, Map<String, String> variables) {\n");
    java.append("""
                    this.client = Objects.requireNonNull(client);
                    this.variables = Map.copyOf(variables);
                }
            """);
    var names = new HashSet<>(Set.of("variable", "encode", "hashCode", "toString", "clone",
            "finalize", "getClass", "notify", "notifyAll", "wait"));
    var typeNames = new HashSet<>(Set.of(className));
    for (Endpoint endpoint : endpoints) {
        appendEndpoint(java, endpoint, names, typeNames);
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

private static void appendEndpoint(StringBuilder java, Endpoint endpoint, Set<String> names,
                                   Set<String> typeNames) {
    String label = endpoint.title().isBlank() ? endpoint.method() + " " + endpoint.url() : endpoint.title();
    String methodName = unique(identifier(label), names);
    String recordName = unique(capitalize(methodName) + "Request", typeNames);
    boolean form = false;
    for (String header : endpoint.headers()) {
        int colon = header.indexOf(':');
        String name = header.substring(0, colon).strip();
        String value = header.substring(colon + 1).strip();
        if (name.equalsIgnoreCase("Content-Type")) {
            form = value.split(";", 2)[0].strip().equalsIgnoreCase("application/x-www-form-urlencoded");
        }
    }
    var params = new ArrayList<String>();
    var queryFields = new ArrayList<String>();
    var queryParameters = new ArrayList<String>();
    var fields = new ArrayList<String>();
    var paramNames = new HashSet<>(Set.of("clone", "finalize", "getClass", "hashCode",
            "notify", "notifyAll", "toString", "wait"));
    String url = endpoint.url();
    int fragmentIndex = url.indexOf('#');
    String fragment = fragmentIndex < 0 ? "" : url.substring(fragmentIndex);
    String urlWithoutFragment = fragmentIndex < 0 ? url : url.substring(0, fragmentIndex);
    int queryIndex = urlWithoutFragment.indexOf('?');
    String baseUrl = url;
    if (queryIndex >= 0) {
        baseUrl = urlWithoutFragment.substring(0, queryIndex);
        for (String field : urlWithoutFragment.substring(queryIndex + 1).split("&")) {
            if (field.isEmpty()) {
                continue;
            }
            String key = URLDecoder.decode(field.split("=", 2)[0], StandardCharsets.UTF_8);
            String parameter = unique(identifier(key), paramNames);
            params.add("String " + parameter);
            queryFields.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=");
            queryParameters.add(parameter);
        }
        if (queryFields.isEmpty()) {
            baseUrl = url;
        }
    }
    String publisher = "HttpRequest.BodyPublishers.noBody()";
    if (form && !endpoint.body().isEmpty()) {
        for (String field : endpoint.body().split("&", -1)) {
            String key = URLDecoder.decode(field.split("=", 2)[0], StandardCharsets.UTF_8);
            String parameter = unique(identifier(key), paramNames);
            params.add("String " + parameter);
            fields.add(literal(URLEncoder.encode(key, StandardCharsets.UTF_8) + "=")
                       + " + encode(arguments." + parameter + "())");
        }
        publisher = "HttpRequest.BodyPublishers.ofString(\n                        "
                    + String.join("\n                        + \"&\" + ", fields) + ")";
    } else if (!endpoint.body().isEmpty()) {
        String parameter = unique("body", paramNames);
        params.add("String " + parameter);
        publisher = "HttpRequest.BodyPublishers.ofString(arguments." + parameter + "())";
    }
    String responseType = endpoint.responses().isEmpty() ? null
            : unique(capitalize(methodName) + "Response", typeNames);
    boolean variableUrl = VARIABLE.matcher(baseUrl).find();
    String uriField = methodName.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                              .toUpperCase(Locale.ROOT) + "_URI";
    String uriExpression = "URI.create(" + template(baseUrl, true) + ")";
    if (variableUrl) {
        uriField = methodName + "Uri";
        java.append("\n    private volatile URI ").append(uriField).append(";\n");
    } else {
        java.append("\n    private static final URI ").append(uriField)
                .append(" = ").append(uriExpression).append(";\n");
    }
    java.append("\n    public record ").append(recordName).append("(")
            .append(String.join(", ", params)).append(") {}\n");
    if (responseType != null) {
        appendResponse(java, endpoint.responses(), responseType, methodName);
        java.append("\n    /**\n")
                .append("     * @return the HTTP response. A successful JSON body has the shape of {@link ")
                .append(responseType).append("}.\n")
                .append("     */");
    }
    java.append("\n    public HttpResponse<String> ").append(methodName).append("(")
            .append(recordName).append(" arguments) throws IOException, InterruptedException {\n");
    java.append("        Objects.requireNonNull(arguments);\n");
    String requestUri = uriField;
    if (variableUrl) {
        java.append("        URI uri = ").append(uriField).append(";\n")
                .append("        if (uri == null) {\n")
                .append("            uri = ").append(uriExpression).append(";\n")
                .append("            ").append(uriField).append(" = uri;\n")
                .append("        }\n");
        requestUri = "uri";
    }
    if (!queryFields.isEmpty()) {
        java.append("        StringJoiner query = new StringJoiner(\"&\");\n");
        for (int index = 0; index < queryFields.size(); index++) {
            String parameter = queryParameters.get(index);
            java.append("        String ").append("queryValue").append(index)
                    .append(" = arguments.").append(parameter).append("();\n")
                    .append("        if (queryValue").append(index).append(" != null) {\n")
                    .append("            query.add(").append(literal(queryFields.get(index)))
                    .append(" + encode(queryValue").append(index).append(").replace(\"+\", \"%20\"));\n")
                    .append("        }\n");
        }
        String suffix = fragment.isEmpty() ? "" : " + " + template(fragment, true);
        java.append("        URI requestUri = query.length() == 0")
                .append(fragment.isEmpty() ? " ? " + requestUri : " ? URI.create(" + requestUri + suffix + ")")
                .append("\n                : URI.create(").append(requestUri)
                .append(" + \"?\" + query").append(suffix).append(");\n");
        requestUri = "requestUri";
    }
    java.append("        HttpRequest request = HttpRequest.newBuilder(")
            .append(requestUri).append(")\n");
    for (String header : endpoint.headers()) {
        int colon = header.indexOf(':');
        java.append("                .header(").append(literal(header.substring(0, colon).strip()))
                .append(", ").append(template(header.substring(colon + 1).strip(), false)).append(")\n");
    }
    java.append("                .method(").append(literal(endpoint.method())).append(", ")
            .append(publisher).append(")\n                .build();\n");
    java.append("        return client.send(request, HttpResponse.BodyHandlers.ofString());\n    }\n");
}

private static void appendResponse(StringBuilder java, List<ResponseRecord> responses,
                                   String name, String methodName) {
    var root = responses.stream().filter(response -> response.name() == null).toList();
    if (root.size() != 1) {
        throw new IllegalArgumentException("Expected one unnamed @response for " + methodName);
    }
    java.append("\n    public record ").append(name).append("(")
            .append(root.get(0).components()).append(") {");
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

private static String template(String value, boolean url) {
    var expressions = new ArrayList<String>();
    var matcher = VARIABLE.matcher(value);
    int end = 0;
    int authorityEnd = value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")
            ? value.indexOf("://") + 3 : 0;
    while (authorityEnd > 0 && authorityEnd < value.length()
           && "/?#".indexOf(value.charAt(authorityEnd)) < 0) {
        authorityEnd++;
    }
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
