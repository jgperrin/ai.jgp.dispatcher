package ai.jgp.gha.dataproduct;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class App {

    public static void main(String[] args) {
        System.out.println("Data Product Uploader v" + K.VERSION);
        System.out.println();

        CliConfig config = CliConfig.parse(args);

        if (config.isDebug()) {
            Logger root = Logger.getLogger("ai.jgp.gha.dataproduct");
            root.setLevel(Level.FINE);
            ConsoleHandler handler = new ConsoleHandler();
            handler.setLevel(Level.FINE);
            root.addHandler(handler);
        }

        ZeeneaClient client = new ZeeneaClient(config);

        boolean success = client.upload();
        System.exit(success ? 0 : 1);
    }
}
