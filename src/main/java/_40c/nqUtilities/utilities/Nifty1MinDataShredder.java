package _40c.nqUtilities.utilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nifty1MinDataShredder
 *
 * <p>Standalone utility for processing ("shredding") Nifty 1-minute OHLCV data.
 * A typical job is to take one large CSV of 1-minute bars and split it into
 * smaller files — for example one file per trading day or per symbol.
 *
 * <p>Usage:
 * <pre>
 *   java _40c.nqUtilities.utilities.Nifty1MinDataShredder &lt;inputFile&gt; [outputDir]
 * </pre>
 */
public class Nifty1MinDataShredder {

    private static final Logger log = LoggerFactory.getLogger(Nifty1MinDataShredder.class);

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            return;
        }

        String inputFile = args[0];
        String outputDir = args.length > 1 ? args[1] : ".";

        log.info("Nifty1MinDataShredder");
        log.info("  input file : {}", inputFile);
        log.info("  output dir : {}", outputDir);

        // TODO: implement shredding logic here.
    }

    private static void printUsage() {
        log.info("Usage: java _40c.nqUtilities.utilities.Nifty1MinDataShredder <inputFile> [outputDir]");
    }
}
