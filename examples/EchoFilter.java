import org.emergence.emfilter.AgentConfig;
import org.emergence.emfilter.Filter;
import org.emergence.emfilter.FilterResult;
import org.emergence.emfilter.FilterRunner;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * echo_filter — exemple minimal d'un agent em_filter Java.
 *
 * Connecte à em_disco, annonce ["search","query","echo"] et retourne
 * chaque query sous forme d'embryo URL.
 *
 * Compilation et exécution (depuis filters/em_filter_java) :
 *   mvn package -q
 *   java -cp target/em-filter-0.1.0.jar:target/dependency/* EchoFilter
 */
public class EchoFilter implements Filter {

    private static final Logger log = Logger.getLogger(EchoFilter.class.getName());

    @Override
    public FilterResult handle(String body, Map<String, Object> memory) {
        log.info("query: " + body);
        List<Map<String, Object>> result = List.of(Map.of(
            "type", "url",
            "properties", Map.of(
                "url",   "https://example.com",
                "title", "Echo: " + body
            )
        ));
        return new FilterResult(result, memory);
    }

    @Override
    public List<String> capabilities() {
        return List.of("search", "query", "echo");
    }

    public static void main(String[] args) throws Exception {
        new FilterRunner("echo_filter", new EchoFilter(), new AgentConfig()).run();
    }
}
