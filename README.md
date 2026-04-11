# em_filter_java

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Java SDK for building [Emergence](https://github.com/EmergenceSystem) network agents.

`em_filter_java` lets any Java process join the Emergence distributed discovery network
as a **filter agent** — a service that receives search queries from the `em_disco`
broker, processes them (web search, DNS lookup, LLM call, database query, …), and
returns structured results.

This library is the Java equivalent of the Erlang `em_filter` library: same WebSocket
protocol, same configuration contract, idiomatic Java API.

---

## How it works

```
 ┌─────────────┐    WebSocket     ┌───────────────┐    WebSocket     ┌─────────────┐
 │  em_disco   │ ◄─────────────── │ FilterRunner  │ ───────────────► │  em_disco   │
 │  (broker)   │  query / result  │ (your agent)  │  (multi-node)    │  (replica)  │
 └─────────────┘                  └───────────────┘                  └─────────────┘
                                         │
                                 Thread per node
                                         │
                                  ┌──────┴──────┐
                                  │ your Filter │
                                  │    impl     │
                                  └─────────────┘
```

1. `FilterRunner` resolves disco nodes and spawns one thread per node.
2. Each thread maintains a persistent WebSocket connection with automatic reconnection.
3. On a `query` frame, the thread calls your `Filter.handle()` and sends back a `result` frame.

---

## Requirements

Java 17+ and Maven 3.6+.

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>org.emergence</groupId>
    <artifactId>em-filter</artifactId>
    <version>0.1.0</version>
</dependency>
```

---

## Quick start

```java
import org.emergence.emfilter.Filter;
import org.emergence.emfilter.FilterResult;
import org.emergence.emfilter.FilterRunner;
import org.emergence.emfilter.AgentConfig;

import java.util.List;
import java.util.Map;

public class MyFilter implements Filter {

    @Override
    public FilterResult handle(String body, Map<String, Object> memory) {
        List<Map<String, Object>> result = List.of(Map.of(
            "type", "url",
            "properties", Map.of(
                "url",   "https://example.com",
                "title", "Result for: " + body
            )
        ));
        return new FilterResult(result, memory);
    }

    @Override
    public List<String> capabilities() {
        return List.of("search", "query");
    }

    public static void main(String[] args) throws Exception {
        new FilterRunner("my_filter", new MyFilter(), new AgentConfig()).run();
    }
}
```

By default the agent connects to `localhost:8080`. Override via environment
variables or `AgentConfig` — see [Configuration](#configuration).

---

## Try the built-in example

```bash
mvn package -q
java -cp "target/em-filter-0.1.0.jar:target/dependency/*" EchoFilter
```

With a custom broker:

```bash
EM_DISCO_HOST=disco.example.com \
EM_DISCO_PORT=443 \
EM_FILTER_JWT_TOKEN=eyJ... \
java -cp "target/em-filter-0.1.0.jar:target/dependency/*" EchoFilter
```

---

## The Filter interface

`org.emergence.emfilter.Filter`

```java
public interface Filter {
    FilterResult handle(String body, Map<String, Object> memory);

    default List<String> capabilities() {
        return List.of("search", "query");
    }
}
```

`handle` is called for every `query` frame from `em_disco`.
- `body` — raw query string (e.g. `"erlang otp"`)
- `memory` — persists between queries within a connection; reset to an empty map on reconnect

Return a `FilterResult(Object data, Map<String,Object> newMemory)`.
`data` is JSON-serialisable — typically a `List<Map<String,Object>>` of embryo objects.

### Result format

| Type | Required properties |
|------|---------------------|
| `"url"` | `url`, `title` |
| `"dns"` | `domain`, `ips` |
| `"text"` | `content` |

An empty list or `null` means "no results for this query".

### Capabilities

`capabilities()` returns the list of capabilities your agent advertises.
`em_disco` uses these to route queries. Default: `["search", "query"]`.

---

## Configuration

### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `EM_DISCO_HOST` | — | Disco broker hostname |
| `EM_DISCO_PORT` | — | Disco broker port |
| `EM_FILTER_JWT_TOKEN` | — | JWT for authenticated brokers |
| `EM_FILTER_RECONNECT_MS` | `5000` | Reconnect delay in milliseconds |

### Node resolution order

1. `AgentConfig.discoNodes` — explicit list (highest priority)
2. `EM_DISCO_HOST` / `EM_DISCO_PORT` env vars
3. `[em_disco] nodes = …` in `emergence.conf`
4. `localhost:8080` — built-in default

### TLS inference

| Host | Port | Transport |
|------|------|-----------|
| `localhost`, `127.0.0.1`, `::1` | any | `ws://` (plain) |
| any other | 443 | `wss://` (TLS) |
| any other | other | `ws://` (plain) |

### `emergence.conf`

```ini
[em_disco]
nodes = localhost:8080, disco.example.com, [::1]:9000
```

Platform paths:
- **Linux / macOS:** `~/.config/emergence/emergence.conf`
- **Windows:** `%APPDATA%\emergence\emergence.conf`

### Programmatic configuration

```java
import org.emergence.emfilter.AgentConfig;
import org.emergence.emfilter.DiscoNode;

AgentConfig config = new AgentConfig(
    "eyJ...",   // JWT token
    List.of(
        new DiscoNode("disco.example.com",  443, true),
        new DiscoNode("disco2.example.com", 443, true)
    )
);
new FilterRunner("my_filter", new MyFilter(), config).run();
```

---

## Multi-node

`FilterRunner` connects to all resolved nodes simultaneously, one thread per node.
Memory is local to each connection thread — starts empty and resets on reconnect
(same as Erlang `em_filter` RAM mode).

---

## HTML utilities

`org.emergence.emfilter.html.HtmlUtils` — helpers for processing web pages:

```java
import org.emergence.emfilter.html.HtmlUtils;

String html   = fetchPage(url);
String clean  = HtmlUtils.stripScripts(html);         // remove <script>…</script>
String text   = HtmlUtils.getText(clean);             // strip all tags → plain text
String decoded = HtmlUtils.decodeHtmlEntities(text);  // caf&eacute; → café

List<String> items = HtmlUtils.extractElements(html, "li.b_algo");
for (String item : items) {
    String href = HtmlUtils.extractAttribute(item, "href");
    if (href != null && !HtmlUtils.shouldSkipLink(href, List.of("ads.", "tracking."))) {
        // process href
    }
}
```

| Method | Description |
|--------|-------------|
| `stripScripts(html)` | Remove all `<script>` blocks |
| `getText(html)` | Strip all HTML tags, return plain text |
| `extractElements(html, selector)` | Extract inner HTML of matching elements (tag, `.class`, `#id`, `tag.class`) |
| `extractAttribute(element, attr)` | Extract an attribute value |
| `decodeHtmlEntities(text)` | Decode `&#N;`, `&#xHH;`, `&name;` entities |
| `shouldSkipLink(url, excluded)` | `true` if URL is not HTTP or matches an excluded substring |

---

## WebSocket protocol

The agent speaks a minimal JSON-over-WebSocket protocol to `em_disco`.

**Agent → Disco:**
```json
{ "action": "register",    "name": "<agent_name>" }
{ "action": "agent_hello", "capabilities": ["search", "query"] }
{ "action": "result",      "id": "<query_id>", "data": <result> }
```

**Disco → Agent:**
```json
{ "action": "query", "id": "<query_id>", "body": "<query_string>" }
```

The library handles the handshake and reconnection automatically.
Your code only implements `Filter.handle`.

---

## License

[MIT](LICENSE)
