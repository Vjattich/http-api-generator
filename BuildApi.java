import java.io.IOException;
import java.util.function.Supplier;
import javax.lang.model.SourceVersion;

private static final Pattern REQUEST = Pattern.compile("([A-Z]+)\\s+(.+?)(?:\\s+HTTP/\\S+)?");
private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");
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
    var instanceVariables = new HashSet<String>();
    var endpoints = parse(Files.readString(source), instanceVariables);
    Files.writeString(output, generate(className, endpoints, instanceVariables));
    IO.println(output);
}

private static List<Endpoint> parse(String source, Set<String> instanceVariables) {
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
            if (instanceVariable.matches()) {
                instanceVariables.add(instanceVariable.group(1));
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
                               Set<String> instanceVariables) throws IOException {
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
    boolean typedResponses = endpoints.stream().anyMatch(endpoint -> endpoint.jsonResponse() != null);
    if (typedResponses) {
        java.append("import java.util.ArrayList;\n")
                .append("import java.util.function.Supplier;\n\n");
    }
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
            """);
    int constructorEnd = java.length();
    java.append("    }\n");
    var instanceUris = new LinkedHashMap<String, String>();
    var names = new HashSet<>(Set.of("variable", "encode", "hashCode", "toString", "clone",
            "finalize", "getClass", "notify", "notifyAll", "wait"));
    var typeNames = new HashSet<>(Set.of(className));
    for (Endpoint endpoint : endpoints) {
        appendEndpoint(java, endpoint, names, typeNames, instanceVariables, instanceUris);
    }
    var assignments = new StringBuilder();
    instanceUris.forEach((url, field) -> assignments.append("        this.").append(field)
            .append(" = URI.create(").append(template(url, true)).append(");\n"));
    java.insert(constructorEnd, assignments);
    if (typedResponses) {
        java.append(responseParser());
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
                                   Set<String> typeNames, Set<String> instanceVariables,
                                   Map<String, String> instanceUris) {
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
    var fieldParameters = new LinkedHashMap<String, String>();
    var queryFields = new ArrayList<String>();
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
            queryFields.add(parameterField(field, params, paramNames, fieldParameters, true));
        }
        if (queryFields.isEmpty()) {
            baseUrl = url;
        }
    }
    String publisher = "HttpRequest.BodyPublishers.noBody()";
    if (form && !endpoint.body().isEmpty()) {
        for (String field : endpoint.body().split("&", -1)) {
            fields.add(parameterField(field, params, paramNames, fieldParameters, false));
        }
        publisher = "HttpRequest.BodyPublishers.ofString(\n                        "
                    + String.join("\n                        + \"&\" + ", fields) + ")";
    } else if (!endpoint.body().isEmpty()) {
        String parameter = unique("body", paramNames);
        params.add("String " + parameter);
        publisher = "HttpRequest.BodyPublishers.ofString(arguments." + parameter + "())";
    }
    boolean typedResponse = endpoint.jsonResponse() != null;
    String responseType = endpoint.responses().isEmpty() && !typedResponse ? null
            : unique(capitalize(methodName) + "Response", typeNames);
    boolean variableUrl = VARIABLE.matcher(baseUrl).find();
    boolean instanceUrl = variableUrl && VARIABLE.matcher(baseUrl).results()
            .allMatch(match -> instanceVariables.contains(match.group(1)));
    String uriField = methodName.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                              .toUpperCase(Locale.ROOT) + "_URI";
    String uriExpression = "URI.create(" + template(baseUrl, true) + ")";
    if (instanceUrl) {
        uriField = instanceUris.get(baseUrl);
        if (uriField == null) {
            uriField = methodName + "Uri";
            instanceUris.put(baseUrl, uriField);
            java.append("\n    private final URI ").append(uriField).append(";\n");
        }
    } else if (variableUrl) {
        uriField = methodName + "Uri";
        java.append("\n    private volatile URI ").append(uriField).append(";\n");
    } else {
        java.append("\n    private static final URI ").append(uriField)
                .append(" = ").append(uriExpression).append(";\n");
    }
    java.append("\n    public record ").append(recordName).append("(")
            .append(String.join(", ", params)).append(") {}\n");
    if (typedResponse) {
        appendJsonResponse(java, responseType, endpoint.jsonResponse());
    } else if (responseType != null) {
        appendResponse(java, endpoint.responses(), responseType, methodName);
        java.append("\n    /**\n")
                .append("     * @return the HTTP response. A successful JSON body has the shape of {@link ")
                .append(responseType).append("}.\n")
                .append("     */");
    }
    java.append("\n    public ").append(typedResponse ? responseType : "HttpResponse<String>")
            .append(" ").append(methodName).append("(")
            .append(recordName).append(" arguments) throws IOException, InterruptedException {\n");
    java.append("        Objects.requireNonNull(arguments);\n");
    String requestUri = uriField;
    if (variableUrl && !instanceUrl) {
        java.append("        URI uri = ").append(uriField).append(";\n")
                .append("        if (uri == null) {\n")
                .append("            uri = ").append(uriExpression).append(";\n")
                .append("            ").append(uriField).append(" = uri;\n")
                .append("        }\n");
        requestUri = "uri";
    }
    if (!queryFields.isEmpty()) {
        java.append("        StringJoiner query = new StringJoiner(\"&\");\n");
        queryFields.forEach(java::append);
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
    if (typedResponse) {
        java.append("""
                        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            throw new IOException("HTTP " + response.statusCode());
                        }
                        try {
                """);
        java.append("            var json = new ResponseJson(response.body());\n")
                .append("            var result = ").append(responseType)
                .append(".readJson(json);\n")
                .append("            json.finish();\n            return result;\n");
        java.append("""
                        } catch (IllegalArgumentException | ArithmeticException e) {
                            throw new IOException("Invalid JSON response", e);
                        }
                    }
                """);
    } else {
        java.append("        return client.send(request, HttpResponse.BodyHandlers.ofString());\n    }\n");
    }
}

private static String parameterField(String field, List<String> params, Set<String> paramNames,
                                     Map<String, String> fieldParameters, boolean query) {
    var matcher = VARIABLE.matcher(field);
    var expressions = new ArrayList<String>();
    var arguments = new LinkedHashSet<String>();
    int end = 0;
    while (matcher.find()) {
        if (matcher.start() > end) {
            expressions.add(literal(field.substring(end, matcher.start())));
        }
        String variable = matcher.group(1);
        String base = SourceVersion.isIdentifier(variable) && !SourceVersion.isKeyword(variable)
                ? variable : identifier(variable);
        String parameter = fieldParameters.computeIfAbsent(variable, key -> {
            String name = unique(base, paramNames);
            params.add("String " + name);
            return name;
        });
        String argument = "arguments." + parameter + "()";
        arguments.add(argument);
        String encoded = "encode(" + argument + ")";
        expressions.add(query ? encoded + ".replace(\"+\", \"%20\")" : encoded);
        end = matcher.end();
    }
    if (end < field.length() || expressions.isEmpty()) {
        expressions.add(literal(field.substring(end)));
    }
    String expression = String.join(" + ", expressions);
    if (!query) {
        return expression;
    }
    String add = "query.add(" + expression + ");\n";
    if (arguments.isEmpty()) {
        return "        " + add;
    }
    String condition = String.join(" != null && ", arguments) + " != null";
    return "        if (" + condition + ") {\n            " + add + "        }\n";
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


private record JsonType(String name, String reader) {
    String read() {
        return "json.nullValue() ? null : " + reader;
    }
}

private static void appendJsonResponse(StringBuilder java, String name, Map<String, Object> sample) {
    var nested = new StringBuilder();
    var names = new HashSet<>(Set.of("ResponseJson", "List", "Map", "Object", "String",
            "Long", "Boolean", "BigDecimal"));
    names.add(name);
    appendJsonRecord(java, nested, names, name, sample, "    ");
    java.append(nested).append("    }\n");
}

private static void appendJsonRecord(StringBuilder java, StringBuilder nested, Set<String> names,
                                     String name, Map<String, Object> sample, String indent) {
    var components = new ArrayList<String>();
    var locals = new ArrayList<String>();
    var declarations = new ArrayList<String>();
    var cases = new LinkedHashMap<Integer, List<String>>();
    var fields = new HashSet<>(Set.of("clone", "finalize", "getClass", "hashCode",
            "notify", "notifyAll", "toString", "wait"));
    sample.forEach((key, example) -> {
        String field = unique(identifier(key.replaceAll("([a-z0-9])([A-Z])", "$1 $2")), fields);
        JsonType type = jsonType(nested, names, capitalize(field), example);
        String local = "field" + components.size();
        components.add(type.name() + " " + field);
        locals.add(local);
        declarations.add(type.name() + " " + local + " = null;");
        cases.computeIfAbsent(key.hashCode(), ignored -> new ArrayList<>())
                .add("if (json.keyEquals(" + literal(key) + ")) {\n"
                        + indent + "                            " + local + " = " + type.read() + ";\n"
                        + indent + "                            continue;\n"
                        + indent + "                        }");
    });
    java.append('\n').append(indent).append("public record ").append(name).append("(")
            .append(String.join(", ", components)).append(") {\n")
            .append(indent).append("    private static ").append(name)
            .append(" readJson(ResponseJson json) {\n");
    for (String declaration : declarations) {
        java.append(indent).append("        ").append(declaration).append('\n');
    }

    java.append(indent).append("        json.expect('{');\n")
            .append(indent).append("        if (!json.take('}')) {\n")
            .append(indent).append("            do {\n")
            .append(indent).append("                int hash = json.keyHash();\n")
            .append(indent).append("                json.expect(':');\n")
            .append(indent).append("                switch (hash) {\n");
    cases.forEach((hash, branches) -> {
        java.append(indent).append("                    case ").append(hash).append(" -> {\n");
        for (String branch : branches) {
            java.append(indent).append("                        ").append(branch).append('\n');
        }
        java.append(indent).append("                    }\n");
    });
    java.append(indent).append("                }\n")
            .append(indent).append("                json.skipValue();\n")
            .append(indent).append("            } while (json.take(','));\n")
            .append(indent).append("            json.expect('}');\n")
            .append(indent).append("        }\n")
            .append(indent).append("        return new ").append(name).append("(")
            .append(String.join(", ", locals)).append(");\n")
            .append(indent).append("    }\n");
    if (java == nested) java.append(indent).append("}\n");
}

private static JsonType jsonType(StringBuilder nested, Set<String> names,
                                 String label, Object sample) {
    return switch (sample) {
        case Map<?, ?> object -> {
            String name = unique(label, names);
            appendJsonRecord(nested, nested, names, name, ResponseExample.fields(object), "        ");
            yield new JsonType(name, name + ".readJson(json)");
        }
        case List<?> list -> {
            if (list.size() != 1 || list.getFirst() == null) {
                throw new IllegalArgumentException("Use one non-null array example for " + label);
            }
            JsonType element = jsonType(nested, names, label, list.getFirst());
            yield new JsonType("List<" + element.name() + ">",
                    "json.list(() -> " + element.read() + ")");
        }
        case String ignored -> new JsonType("String", "json.string()");
        case Boolean ignored -> new JsonType("Boolean", "json.bool()");
        case java.math.BigDecimal number -> number.scale() <= 0 && number.precision() - number.scale() < 19
                ? new JsonType("Long", "json.longValue()")
                : new JsonType("java.math.BigDecimal", "json.decimal()");
        case null -> new JsonType("Object", "json.rawValue()");
        default -> throw new IllegalArgumentException("Unsupported JSON example for " + label);
    };
}

private static class ResponseJson {
    private int keyStart;
    private int keyLength;
    private String escapedKey;
    final String input;
    int position;

    ResponseJson(String input) {
        this.input = input;
    }

    Object keyword(String token, Object value) {
        if (!input.startsWith(token, position)) throw invalid();
        position += token.length();
        return value;
    }

    private void integerPart() {
        if (position < input.length() && input.charAt(position) == '-') position++;
        if (position < input.length() && input.charAt(position) == '0') {
            position++;
        } else {
            digits();
        }
    }

    private void digits() {
        int start = position;
        while (position < input.length()) {
            char c = input.charAt(position);
            if (c < '0' || c > '9') break;
            position++;
        }
        if (position == start) throw invalid();
    }

    private boolean numberSuffix() {
        int start = position;
        if (position < input.length() && input.charAt(position) == '.') {
            position++;
            digits();
        }
        if (position < input.length() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
            position++;
            if (position < input.length() && (input.charAt(position) == '+' || input.charAt(position) == '-')) {
                position++;
            }
            digits();
        }
        return position != start;
    }

    java.math.BigDecimal number() {
        int start = position;
        integerPart();
        numberSuffix();
        return new java.math.BigDecimal(input.substring(start, position));
    }

    long longValue() {
        // The generated null check has already consumed leading whitespace.
        int start = position;
        integerPart();
        if (numberSuffix()) {
            return new java.math.BigDecimal(input.substring(start, position)).longValueExact();
        }
        return Long.parseLong(input, start, position, 10);
    }

    int keyHash() {
        expect('"');
        keyStart = position;
        escapedKey = null;
        int hash = 0;
        while (position < input.length()) {
            char c = input.charAt(position++);
            if (c == '"') {
                keyLength = position - keyStart - 1;
                return hash;
            }
            if (c == '\\') {
                position = keyStart - 1;
                escapedKey = string();
                return escapedKey.hashCode();
            }
            if (c < ' ') throw invalid();
            hash = 31 * hash + c;
        }
        throw invalid();
    }

    boolean keyEquals(String expected) {
        if (escapedKey != null) return escapedKey.equals(expected);
        return keyLength == expected.length() && input.startsWith(expected, keyStart);
    }

    String string() {
        return readString(true);
    }

    private String readString(boolean retain) {
        expect('"');
        int start = position;
        StringBuilder decoded = null;
        while (position < input.length()) {
            char c = input.charAt(position++);
            if (c == '"') {
                if (!retain) return null;
                if (decoded == null) return input.substring(start, position - 1);
                return decoded.append(input, start, position - 1).toString();
            }
            if (c < ' ') throw invalid();
            if (c == '\\') {
                if (retain) {
                    if (decoded == null) decoded = new StringBuilder();
                    decoded.append(input, start, position - 1);
                }
                char escaped = escape();
                if (retain) decoded.append(escaped);
                start = position;
            }
        }
        throw invalid();
    }

    private char escape() {
        if (position == input.length()) throw invalid();
        return switch (input.charAt(position++)) {
            case '"', '\\', '/' -> input.charAt(position - 1);
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
        int value = 0;
        for (int i = 0; i < 4; i++) {
            if (position == input.length()) throw invalid();
            int hex = "0123456789abcdefABCDEF".indexOf(input.charAt(position++));
            if (hex < 0) throw invalid();
            value = 16 * value + (hex > 15 ? hex - 6 : hex);
        }
        return (char) value;
    }

    void whitespace() {
        while (position < input.length() && " \t\r\n".indexOf(input.charAt(position)) >= 0) position++;
    }

    boolean take(char c) {
        whitespace();
        if (position == input.length() || input.charAt(position) != c) return false;
        position++;
        return true;
    }

    void expect(char c) {
        if (!take(c)) throw invalid();
    }

    IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid JSON at character " + position);
    }

    boolean nullValue() {
        whitespace();
        if (!input.startsWith("null", position)) return false;
        keyword("null", null);
        return true;
    }

    boolean bool() {
        whitespace();
        if (input.startsWith("true", position)) {
            keyword("true", true);
            return true;
        }
        keyword("false", false);
        return false;
    }

    java.math.BigDecimal decimal() {
        whitespace();
        return number();
    }

    <T> List<T> list(Supplier<T> readItem) {
        expect('[');
        var items = new ArrayList<T>();
        if (take(']')) return items;
        do {
            items.add(readItem.get());
        } while (take(','));
        expect(']');
        return items;
    }

    void skipValue() {
        whitespace();
        if (position == input.length()) throw invalid();
        switch (input.charAt(position)) {
            case '{' -> {
                expect('{');
                if (take('}')) return;
                do {
                    readString(false);
                    expect(':');
                    skipValue();
                } while (take(','));
                expect('}');
            }
            case '[' -> {
                expect('[');
                if (take(']')) return;
                do {
                    skipValue();
                } while (take(','));
                expect(']');
            }
            case '"' -> readString(false);
            case 't', 'f' -> bool();
            case 'n' -> keyword("null", null);
            default -> {
                integerPart();
                numberSuffix();
            }
        }
    }

    String rawValue() {
        whitespace();
        int start = position;
        skipValue();
        return input.substring(start, position);
    }

    void finish() {
        whitespace();
        if (position != input.length()) throw invalid();
    }
}

private static final class ResponseExample extends ResponseJson {
    ResponseExample(String input) {
        super(input);
    }

    Map<String, Object> object() {
        var result = fields(value());
        whitespace();
        if (position != input.length()) throw invalid();
        return result;
    }

    private Object value() {
        whitespace();
        if (position == input.length()) throw invalid();
        return switch (input.charAt(position)) {
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
        // The parser constructs objects with string keys.
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) fields;
        return result;
    }

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
